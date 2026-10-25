package dev.iskandern.kotin.landxml

import dev.iskandern.kotin.io.ByteInput
import dev.iskandern.kotin.model.CoordinateSystemInfo
import dev.iskandern.kotin.model.Journal
import dev.iskandern.kotin.model.SceneGeometry
import dev.iskandern.kotin.model.UiText
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/**
 * Читает LandXML 1.0–1.2 в [SceneGeometry] за один проход. Корень: версия, `Units`, `Project`, `Application` —
 * одной строкой в консоль, в сцену не попадают (геометрия не пересчитывается; из `Units` берётся только масштаб размеров сечения труб и колодцев).
 * `CoordinateSystem` — как записан, для показа в меню и строкой в консоль на элемент; геометрию не меняет.
 * Разделы верхнего уровня читают классы из [SECTIONS] в любом порядке, неизвестные пропускаются;
 * отложенные шаги разделов выполняет [LandXmlSceneBuilder.finish].
 */
class LandXmlReader internal constructor(
  input: ByteInput,
  private val sections: List<LandXmlSection>,
  private val log: (String) -> Unit = Journal::log,
  /** Размер файла, если известен, — для оценки ёмкости массивов ([LandXmlSceneBuilder.sizeBytes]). */
  private val sizeBytes: Long? = null,
  /** Запись пикетов в подписях ([LandXmlSceneBuilder.stationNotation]). */
  private val stationNotation: StationNotation = StationNotation(),
) {
  constructor(input: ByteInput, sizeBytes: Long? = null, stationNotation: StationNotation = StationNotation()) :
    this(input, SECTIONS.map { it() }, sizeBytes = sizeBytes, stationNotation = stationNotation)

  /** Сведения о файле после [read]: версия, единицы, проект, программа. */
  var summary = ""
    private set

  private val rootNames = XmlNames(*ROOT_NAMES, *Array(sections.size) { sections[it].element })
  private val xml = XmlTokenizer(input, rootNames)

  fun read(onProgress: (bytesRead: Long) -> Unit = {}): SceneGeometry {
    // прогресс — не чаще 20 раз в секунду, время проверяется раз в [LandXmlSceneBuilder.TICK_EVERY] элементов
    var last = TimeSource.Monotonic.markNow()
    val progress = {
      if (last.elapsedNow() >= PROGRESS_PERIOD) {
        last = TimeSource.Monotonic.markNow()
        onProgress(xml.bytesRead)
      }
    }
    val scene = LandXmlSceneBuilder(onTick = progress, log = log, sizeBytes = sizeBytes, stationNotation = stationNotation)
    val commasBefore = LandXmlNumbers.commaCount

    while (xml.next() != XmlTokenizer.START) Unit
    if (xml.nameId != LANDXML) throw XmlFormatException(UiText.key("xml_not_landxml", xml.localName()), 0)
    val version = xml.attrString(VERSION)?.takeIf { it.isNotBlank() } ?: namespaceVersion(xml.namespaces()) ?: "?"
    var units = ""
    var project = ""
    var application = ""
    val root = xml.depth
    while (xml.nextChild(root)) {
      val id = xml.nameId
      when {
        id == UNITS -> units = readUnits(scene)
        id == COORDINATE_SYSTEM -> CoordinateSystemElement.read(xml).let { if (it.isNotEmpty()) scene.coordinateSystems += it }
        id == PROJECT -> {
          project = xml.attrString(NAME).orEmpty()
          xml.skipElement()
        }
        id == APPLICATION -> {
          application = listOfNotNull(xml.attrString(NAME), xml.attrString(VERSION)).joinToString(" ")
          xml.skipElement()
        }
        id >= ROOT_NAMES.size -> readSection(sections[id - ROOT_NAMES.size], scene)
        else -> xml.skipElement()
      }
      progress()
    }
    summary = if (units.isEmpty()) UiText.key("log_landxml_no_units", version, project, application)
    else UiText.key("log_landxml", version, units, project, application)
    log(summary)
    for (attributes in scene.coordinateSystems) {
      val system = CoordinateSystemInfo(attributes, null)
      val code = system.code
      log(if (code != null) UiText.key("log_landxml_crs", system.name, code) else UiText.key("log_landxml_crs_no_code", system.name))
    }
    val commas = LandXmlNumbers.commaCount - commasBefore
    if (commas > 0) log(UiText.key("log_landxml_decimal_comma", commas))
    onProgress(xml.bytesRead)
    return scene.finish()
  }

  private fun readSection(section: LandXmlSection, scene: LandXmlSceneBuilder) {
    val depth = xml.depth
    xml.names = section.names
    try {
      section.read(xml, scene)
    } finally {
      xml.names = rootNames
    }
    check(xml.event == XmlTokenizer.END && xml.depth == depth) { UiText.key("error_section_unfinished", section.element) }
  }

  // <Units><Metric linearUnit="meter" …/></Units> → «Metric (meter)»
  private fun readUnits(scene: LandXmlSceneBuilder): String {
    val parts = ArrayList<String>()
    val depth = xml.depth
    while (xml.nextChild(depth)) {
      val linear = xml.attrString(LINEAR_UNIT)
      if (scene.sectionScale.isNaN()) scene.sectionScale = LandXmlUnits.sectionScale(linear, xml.attrString(DIAMETER_UNIT))
      if (scene.fileUnits == null) scene.fileUnits = linear?.takeIf { it.isNotBlank() }
      parts.add(if (linear != null) "${xml.localName()} ($linear)" else xml.localName())
      xml.skipElement()
    }
    return parts.joinToString(", ")
  }

  companion object {
    /**
     * Реестр разделов верхнего уровня: по строке на раздел (`::SurfacesSection`, `::CgPointsSection`, …).
     * Раздел создаётся заново на каждый файл. Разделы не знают друг друга: что нужно другим (трассы для `Roadways`,
     * точки для ссылок) лежит в [LandXmlSceneBuilder].
     */
    internal val SECTIONS: List<() -> LandXmlSection> = listOf(
      { AlignmentsSection() },
      { CgPointsSection() },
      { ParcelsSection() },
      { PlanFeaturesSection() },
      { SurfacesSection() },
      { PipeNetworksSection() },
      { RoadwaysSection() },
      { MonumentsSection() },
      { SurveySection() },
    )

    private val ROOT_NAMES = arrayOf("LandXML", "Units", "Project", "Application", CoordinateSystemElement.ELEMENT)
    private const val LANDXML = 0
    private const val UNITS = 1
    private const val PROJECT = 2
    private const val APPLICATION = 3
    private const val COORDINATE_SYSTEM = 4

    private val VERSION = "version".encodeToByteArray()
    private val NAME = "name".encodeToByteArray()
    private val LINEAR_UNIT = "linearUnit".encodeToByteArray()
    private val DIAMETER_UNIT = "diameterUnit".encodeToByteArray()
    private val PROGRESS_PERIOD = 50.milliseconds

    // http://www.landxml.org/schema/LandXML-1.2 → «1.2»
    internal fun namespaceVersion(namespaces: List<String>): String? {
      for (ns in namespaces) {
        val at = ns.indexOf("LandXML-")
        if (at < 0) continue
        val version = ns.substring(at + 8).takeWhile { it.isDigit() || it == '.' }
        if (version.isNotEmpty()) return version
      }
      return null
    }
  }
}
