package dev.iskandern.kotin.landxml

import dev.iskandern.kotin.model.Journal
import dev.iskandern.kotin.model.SceneAttribute
import dev.iskandern.kotin.model.SceneObject
import dev.iskandern.kotin.model.UiText
import kotlin.math.abs

/**
 * Раздел `Roadways`: паспорт дороги по пикетам трассы. `Roadway[name, alignmentRefs]` читает:
 * - `Speeds`: `DesignSpeed`, `DesignSpeed85th`, `PostedSpeed[speedLimit, sideofRoad]`;
 * - `TrafficVolume/DailyTrafficVolume[ADT]`;
 * - `Roadside/RoadSign[MUTCDCode, station, offset, sideofRoad, type]` — значок со смещением от оси.
 * Участок — параллельная полоса вдоль трассы: у каждого элемента свой слой (имя элемента), свой оттенок и своё смещение;
 * цвет — положение значения между минимумом и максимумом этого элемента в файле. Подпись «имя значение» стоит в середине
 * участка (точки применения у него нет), подписи слоёв разнесены вдоль трассы. Названия и атрибуты — как в файле, своих не вводим.
 * Остальное (`Lanes`, `Classification`, `CrashData`, …) пропускается. Трасса — по имени из [LandXmlSceneBuilder.alignments];
 * рисует `finish()` после построения трасс ([LandXmlSceneBuilder.STAGE_DRAPE]), поэтому раздел может стоять в файле до
 * `Alignments`. Единицы не пересчитываются. Объект сцены — на каждый участок и знак.
 */
internal class RoadwaysSection(
  private val log: (String) -> Unit = Journal::log,
) : LandXmlSection("Roadways", NAMES) {
  private val roadways = ArrayList<Roadway>()
  private var deferred = false

  override fun read(xml: XmlTokenizer, scene: LandXmlSceneBuilder) {
    if (!deferred) {
      deferred = true
      scene.defer(LandXmlSceneBuilder.STAGE_DRAPE) { build(scene) }
    }
    val depth = xml.depth
    while (xml.nextChild(depth)) {
      if (xml.nameId == ROADWAY) readRoadway(xml) else xml.skipElement()
      scene.tick()
    }
  }

  private fun readRoadway(xml: XmlTokenizer) {
    val road = Roadway(
      xml.attrString(NAME).orEmpty(),
      xml.attrString(ALIGNMENT_REFS).orEmpty().trim(),
      xml.attrString(DESC).orEmpty(),
    )
    val depth = xml.depth
    while (xml.nextChild(depth)) {
      when (xml.nameId) {
        SPEEDS, TRAFFIC_VOLUME, ROADSIDE -> readGroup(xml, road)
        FEATURE -> if (road.features.size < Features.MAX_FEATURES) road.features.add(Features.read(xml)) else xml.skipElement()
        else -> xml.skipElement()
      }
    }
    roadways.add(road)
  }

  // Speeds, TrafficVolume, Roadside: по интересным детям; остальные пропускаются
  private fun readGroup(xml: XmlTokenizer, road: Roadway) {
    val depth = xml.depth
    while (xml.nextChild(depth)) {
      val kind = when (xml.nameId) {
        DESIGN_SPEED -> Span.DESIGN
        DESIGN_SPEED_85 -> Span.DESIGN_85
        POSTED_SPEED -> Span.POSTED
        DAILY_TRAFFIC -> Span.TRAFFIC
        ROAD_SIGN_ELEMENT -> {
          road.signs.add(Sign(
            xml.attrString(MUTCD_CODE).orEmpty().trim(), xml.attrDouble(STATION), xml.attrDouble(OFFSET),
            xml.attrString(SIDE).orEmpty(), xml.attrString(TYPE).orEmpty(),
          ))
          xml.skipElement()
          continue
        }
        else -> {
          xml.skipElement()
          continue
        }
      }
      val value = xml.attrDouble(if (kind == Span.TRAFFIC) ADT else if (kind == Span.POSTED) SPEED_LIMIT else SPEED)
      road.spans.add(Span(kind, xml.attrDouble(STA_START), xml.attrDouble(STA_END), value, xml.attrString(SIDE).orEmpty()))
      xml.skipElement()
    }
  }

  private fun build(scene: LandXmlSceneBuilder) {
    if (roadways.isEmpty()) return
    val sceneSize = CoordGeom.sceneSize(scene)
    // шкала цвета каждого показателя — от минимума до максимума значений в файле
    val low = DoubleArray(SHOWN.size) { Double.POSITIVE_INFINITY }
    val high = DoubleArray(SHOWN.size) { Double.NEGATIVE_INFINITY }
    for (road in roadways) for (span in road.spans) {
      if (span.value.isNaN()) continue
      low[span.kind] = minOf(low[span.kind], span.value)
      high[span.kind] = maxOf(high[span.kind], span.value)
    }
    var spans = 0
    var segments = 0
    var signs = 0
    var missing = 0
    for (road in roadways) {
      // alignmentRefs — имена через пробел, а в именах пробелы бывают («Трасса 1»): ищем имя целиком между пробелами
      val padded = " ${road.alignmentRefs} "
      val found = scene.alignments.filter { it.name.isNotEmpty() && padded.contains(" ${it.name} ") }
      if (found.isEmpty()) {
        missing++
        continue
      }
      for (built in found) {
        for (span in road.spans) {
          val drawn = drawSpan(scene, built, road, span, sceneSize, low[span.kind], high[span.kind])
          if (drawn > 0) spans++
          segments += drawn
        }
        for (sign in road.signs) signs += drawSign(scene, built, road, sign)
      }
    }
    log(UiText.key("log_roadways", roadways.size, spans, segments, signs))
    if (missing > 0) log(UiText.key("log_roadways_missing", missing))
  }

  private fun drawSpan(
    scene: LandXmlSceneBuilder, built: LandXmlSceneBuilder.BuiltAlignment, road: Roadway, span: Span, sceneSize: Double,
    low: Double, high: Double,
  ): Int {
    if (span.from.isNaN() || span.to.isNaN() || span.to <= span.from) return 0
    val shown = SHOWN[span.kind]
    val color = LandXmlPalette.scale(shown.scale, if (span.value.isNaN()) 1.0 else LandXmlPalette.position(span.value, low, high))
    val layer = scene.layer(shown.layer, LandXmlPalette.scale(shown.scale, 1.0), LandXmlGroup.ALIGNMENTS)
    // PostedSpeed справа — по правую руку от трассы
    val lane = if (span.side.equals("right", true)) -shown.lane else shown.lane
    val value = if (span.value.isNaN()) "" else AlignmentsSection.format(span.value)
    val text = if (value.isEmpty()) shown.label else "${shown.label} $value"
    val attributes = ArrayList<SceneAttribute>()
    attributes.add(SceneAttribute("Roadway", road.name))
    attributes.add(SceneAttribute("Alignment", built.name))
    if (value.isNotEmpty()) attributes.add(SceneAttribute(shown.attribute, value))
    attributes.add(SceneAttribute("staStart", AlignmentsSection.format(span.from)))
    attributes.add(SceneAttribute("staEnd", AlignmentsSection.format(span.to)))
    if (span.side.isNotEmpty()) attributes.add(SceneAttribute("sideofRoad", span.side))
    if (high > low) attributes.add(SceneAttribute("range", "${AlignmentsSection.format(low)} … ${AlignmentsSection.format(high)}"))
    val id = scene.addObject(SceneObject(shown.element, text, layer, color, attributes))
    val drawn = StationOverlay.drawSpan(scene, built.geometry, built.profile, layer, color, span.from, span.to, lane, sceneSize, id)
    StationOverlay.labelSpan(scene, built.geometry, built.profile, layer, span.from, span.to, lane, text, sceneSize, id,
      slot = StationOverlay.labelShift(shown.lane, sceneSize))
    return drawn.coerceAtLeast(1)
  }

  private fun drawSign(scene: LandXmlSceneBuilder, built: LandXmlSceneBuilder.BuiltAlignment, road: Roadway, sign: Sign): Int {
    if (sign.station.isNaN()) return 0
    val layer = scene.layer(ROAD_SIGN_LAYER, LandXmlPalette.ROAD_SIGN, LandXmlGroup.ALIGNMENTS)
    // смещение: сторона задаёт знак (слева +), без стороны — знак смещения (минус — слева, как в поперечниках)
    val offset = if (sign.offset.isNaN()) 0.0 else sign.offset
    val left = when {
      sign.side.equals("left", true) -> abs(offset)
      sign.side.equals("right", true) -> -abs(offset)
      else -> -offset
    }
    val name = sign.code.ifEmpty { sign.type.ifEmpty { ROAD_SIGN } }
    val attributes = ArrayList<SceneAttribute>()
    attributes.add(SceneAttribute("Roadway", road.name))
    attributes.add(SceneAttribute("Alignment", built.name))
    if (sign.code.isNotEmpty()) attributes.add(SceneAttribute("MUTCDCode", sign.code))
    if (sign.type.isNotEmpty()) attributes.add(SceneAttribute("type", sign.type))
    attributes.add(SceneAttribute("station", AlignmentsSection.format(sign.station)))
    if (!sign.offset.isNaN()) attributes.add(SceneAttribute("offset", AlignmentsSection.format(sign.offset)))
    if (sign.side.isNotEmpty()) attributes.add(SceneAttribute("sideofRoad", sign.side))
    val id = scene.addObject(SceneObject(ROAD_SIGN, name, layer, LandXmlPalette.ROAD_SIGN, attributes))
    return if (StationOverlay.mark(scene, built.geometry, built.profile, layer, LandXmlPalette.ROAD_SIGN, sign.station, left, id, name)) 1 else 0
  }

  /** Показатель по пикетам: элемент файла (он же вид объекта), слой, подпись, атрибут со значением, шкала цвета, номер полосы. */
  private class Shown(
    val element: String, val layer: String, val label: String, val attribute: String, val scale: Int, val lane: Double,
  )

  private class Span(val kind: Int, val from: Double, val to: Double, val value: Double, val side: String) {
    companion object {
      const val DESIGN = 0
      const val DESIGN_85 = 1
      const val POSTED = 2
      const val TRAFFIC = 3
    }
  }

  private class Sign(val code: String, val station: Double, val offset: Double, val side: String, val type: String)

  private class Roadway(val name: String, val alignmentRefs: String, val desc: String) {
    val spans = ArrayList<Span>()
    val signs = ArrayList<Sign>()
    val features = ArrayList<FeatureData>()
  }

  companion object {
    // вид объекта — имя элемента файла; подпись и атрибут — имена из файла (DailyTrafficVolume подписан по ADT);
    // слой — в общей форме списка «Трассы: <что>»
    const val ROAD_SIGN = "RoadSign"
    const val ROAD_SIGN_LAYER = "@layer_road_signs"
    private val SHOWN = arrayOf(
      Shown("DesignSpeed", "@layer_design_speed", "DesignSpeed", "speed", LandXmlPalette.SCALE_SPEED, 1.0),
      Shown("DesignSpeed85th", "@layer_design_speed_85", "DesignSpeed85th", "speed", LandXmlPalette.SCALE_SPEED_85, 2.0),
      Shown("PostedSpeed", "@layer_posted_speed", "PostedSpeed", "speedLimit", LandXmlPalette.SCALE_POSTED, 3.0),
      Shown("DailyTrafficVolume", "@layer_traffic", "ADT", "ADT", LandXmlPalette.SCALE_TRAFFIC, 4.0),
    )

    private val NAMES = XmlNames(
      "Roadway", "Speeds", "TrafficVolume", "Roadside", "DesignSpeed", "DesignSpeed85th", "PostedSpeed", "DailyTrafficVolume",
      "RoadSign", "Feature",
    )
    private const val ROADWAY = 0
    private const val SPEEDS = 1
    private const val TRAFFIC_VOLUME = 2
    private const val ROADSIDE = 3
    private const val DESIGN_SPEED = 4
    private const val DESIGN_SPEED_85 = 5
    private const val POSTED_SPEED = 6
    private const val DAILY_TRAFFIC = 7
    private const val ROAD_SIGN_ELEMENT = 8
    private const val FEATURE = 9

    private val NAME = "name".encodeToByteArray()
    private val DESC = "desc".encodeToByteArray()
    private val ALIGNMENT_REFS = "alignmentRefs".encodeToByteArray()
    private val STA_START = "staStart".encodeToByteArray()
    private val STA_END = "staEnd".encodeToByteArray()
    private val SPEED = "speed".encodeToByteArray()
    private val SPEED_LIMIT = "speedLimit".encodeToByteArray()
    private val ADT = "ADT".encodeToByteArray()
    private val SIDE = "sideofRoad".encodeToByteArray()
    private val STATION = "station".encodeToByteArray()
    private val OFFSET = "offset".encodeToByteArray()
    private val MUTCD_CODE = "MUTCDCode".encodeToByteArray()
    private val TYPE = "type".encodeToByteArray()
  }
}
