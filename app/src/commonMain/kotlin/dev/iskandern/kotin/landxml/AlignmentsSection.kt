package dev.iskandern.kotin.landxml

import dev.iskandern.kotin.model.GrowingDoubleArray
import dev.iskandern.kotin.model.Journal
import dev.iskandern.kotin.model.PointMark
import dev.iskandern.kotin.model.SceneAttribute
import dev.iskandern.kotin.model.SceneObject
import dev.iskandern.kotin.model.UiText
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Раздел `Alignments`: трассы `Alignment[name, length, staStart, desc]` → ломаная на слое «Трассы», объект и подпись
 * с именем у начала. С профилем (первый `Profile/ProfAlign`, [AlignmentProfile]) — в 3D на его отметках, без профиля —
 * в плане (`flat`: накопитель сажает линию и подпись на поверхность файла, вне её сцена кладёт на уровень).
 * Профиль земли (первый `Profile/ProfSurf`) — своей линией на слое «Трассы: земля» со стойками до проектной линии
 * ([drawGround]). Геометрия — [CoordGeom], пикетаж — `staStart` и `StaEquation`, поперечники — [CrossSects];
 * `Alignment/Start` и прочее пропускаются. Разбиение дуг — в `finish()`; там же готовятся [geometries] и [profiles].
 * `Feature/Property` трассы — в панель объекта; `Feature name="SpeedStation"` (проектная скорость с пикета) — полосы
 * вдоль трассы цветом скорости и подписи в местах смены ([speedStations]); `AlignPIs` — значки углов поворота.
 */
internal class AlignmentsSection(
  private val log: (String) -> Unit = Journal::log,
  /** Пикетаж (слой «Пикетаж»: подписи и засечки, [StationLabels]). */
  private val stationing: Boolean = true,
) : LandXmlSection("Alignments", NAMES) {
  private val geom = CoordGeom()
  // по трассе: имя, описание, атрибуты из файла, диапазон элементов, уравнения пикетажа
  private val pending = ArrayList<Pending>()
  // Feature и точки AlignPI читаемой трассы (x, y, пикет подряд)
  private val features = ArrayList<FeatureData>()
  private val pis = GrowingDoubleArray()
  private val point = DoubleArray(3)
  private val equations = GrowingDoubleArray()
  // PVI профилей всех трасс пятёрками ([AlignmentProfile.PVI_SIZE]); профиль читаемой трассы
  private val pvis = GrowingDoubleArray()
  private var profileName: String? = null
  private var pvisFrom = 0
  private var extraProfiles = 0
  // точки ProfSurf всех трасс парами «пикет, отметка», куски PntList2D разделены парой NaN; земля читаемой трассы
  private val grounds = GrowingDoubleArray()
  private var groundName: String? = null
  private var groundFrom = 0
  private var groundPoints = 0
  private var deferred = false
  private val crossSects = CrossSects(CROSS_SECTS, log)

  /** Трассы по порядку файла — после `finish()`. */
  val geometries = ArrayList<AlignmentGeometry>()

  /** Профили трасс (null — нет профиля или в нём меньше двух PVI) — в том же порядке, что [geometries]. */
  val profiles = ArrayList<AlignmentProfile?>()

  /** Имена трасс — в том же порядке, что [geometries]. */
  val alignmentNames: List<String> get() = pending.map { it.name }

  override fun read(xml: XmlTokenizer, scene: LandXmlSceneBuilder) {
    if (!deferred) {
      deferred = true
      scene.defer(LandXmlSceneBuilder.STAGE_REFERENCES) { geom.resolve(scene.pointNames, scene) }
      scene.defer(LandXmlSceneBuilder.STAGE_CURVES) { build(scene) }
    }
    val depth = xml.depth
    while (xml.nextChild(depth)) {
      if (xml.nameId == ALIGNMENT) readAlignment(xml, scene) else xml.skipElement()
    }
  }

  private fun readAlignment(xml: XmlTokenizer, scene: LandXmlSceneBuilder) {
    val name = xml.attrString(NAME).orEmpty()
    val desc = xml.attrString(DESC).orEmpty()
    val length = xml.attrDouble(LENGTH)
    val staStart = xml.attrDouble(STA_START).let { if (it.isNaN()) 0.0 else it }
    val first = geom.size
    val equationsFrom = equations.size
    features.clear()
    pis.clear()
    val depth = xml.depth
    while (xml.nextChild(depth)) {
      when (xml.nameId) {
        CoordGeom.COORD_GEOM -> geom.read(xml, scene)
        FEATURE -> {
          val feature = Features.read(xml)
          if (features.size < Features.MAX_FEATURES) features.add(feature)
        }
        ALIGN_PIS -> readAlignPis(xml)
        STA_EQUATION -> {
          val internal = xml.attrDouble(STA_INTERNAL)
          val ahead = xml.attrDouble(STA_AHEAD)
          val sign = if (xml.attrEquals(STA_INCREMENT, DECREASING)) -1.0 else 1.0
          if (!internal.isNaN() && !ahead.isNaN()) {
            equations.add(internal)
            equations.add(ahead)
            equations.add(sign)
          }
          xml.skipElement()
        }
        PROFILE -> readProfile(xml)
        CROSS_SECTS -> crossSects.read(xml, scene, pending.size)
        // Alignment/Start (у Trimble — с отметкой) и прочее
        else -> xml.skipElement()
      }
    }
    pending.add(Pending(
      name, desc, length, staStart, first, geom.size, equationsFrom, equations.size,
      profileName, pvisFrom, pvis.size, extraProfiles, features.toList(), pis.toArray(),
      groundName, groundFrom, grounds.size, groundPoints,
    ))
    profileName = null
    extraProfiles = 0
    groundName = null
    groundPoints = 0
  }

  // AlignPIs/AlignPI: Station (пикет), PI «север восток»; кривые внутри пропускаются
  private fun readAlignPis(xml: XmlTokenizer) {
    val depth = xml.depth
    while (xml.nextChild(depth)) {
      if (xml.nameId != ALIGN_PI) {
        xml.skipElement()
        continue
      }
      var station = Double.NaN
      var x = Double.NaN
      var y = Double.NaN
      val at = xml.depth
      while (xml.nextChild(at)) {
        when (xml.nameId) {
          STATION -> if (xml.nextNumber()) station = xml.double()
          CoordGeom.PI_POINT -> if (LandXmlCoord.readPoint(xml, point) >= 2) {
            x = point[0]
            y = point[1]
          }
          else -> xml.skipElement()
        }
      }
      if (!x.isNaN() && !y.isNaN() && pis.size < MAX_PIS * 3) {
        pis.add(x)
        pis.add(y)
        pis.add(station)
      }
    }
  }

  // Profile: первый ProfAlign трассы — в [pvis], остальные только считаются; первый ProfSurf (земля) — в [grounds]
  private fun readProfile(xml: XmlTokenizer) {
    val depth = xml.depth
    while (xml.nextChild(depth)) {
      if (xml.nameId == PROF_SURF && groundName == null) {
        readGround(xml)
        continue
      }
      if (xml.nameId != PROF_ALIGN) {
        xml.skipElement()
        continue
      }
      if (profileName != null) {
        extraProfiles++
        xml.skipElement()
        continue
      }
      profileName = xml.attrString(NAME).orEmpty()
      pvisFrom = pvis.size
      val at = xml.depth
      while (xml.nextChild(at)) {
        val kind = when (xml.nameId) {
          PVI -> AlignmentProfile.PVI
          PARA_CURVE -> AlignmentProfile.PARA
          UNSYM_PARA_CURVE -> AlignmentProfile.UNSYM
          CIRC_CURVE -> AlignmentProfile.CIRC
          else -> -1
        }
        if (kind < 0) {
          xml.skipElement()
          continue
        }
        val a = when (kind) {
          AlignmentProfile.UNSYM -> xml.attrDouble(LENGTH_IN)
          AlignmentProfile.PVI -> Double.NaN
          else -> xml.attrDouble(LENGTH)
        }
        val b = when (kind) {
          AlignmentProfile.UNSYM -> xml.attrDouble(LENGTH_OUT)
          AlignmentProfile.CIRC -> xml.attrDouble(RADIUS)
          else -> Double.NaN
        }
        // текст «пикет отметка»
        if (!xml.nextNumber()) continue
        val station = xml.double()
        if (!xml.nextNumber()) continue
        pvis.add(station)
        pvis.add(xml.double())
        pvis.add(kind.toDouble())
        pvis.add(a)
        pvis.add(b)
      }
    }
  }

  // ProfSurf: точки PntList2D «пикет отметка»; каждый список — свой кусок (между ними земли нет)
  private fun readGround(xml: XmlTokenizer) {
    groundName = xml.attrString(NAME).orEmpty()
    groundFrom = grounds.size
    val depth = xml.depth
    while (xml.nextChild(depth)) {
      if (xml.nameId != CoordGeom.PNT_LIST_2D) {
        xml.skipElement()
        continue
      }
      if (grounds.size > groundFrom) {
        grounds.add(Double.NaN)
        grounds.add(Double.NaN)
      }
      while (grounds.size < MAX_GROUND * 2 && xml.nextNumber()) {
        val station = xml.double()
        if (!xml.nextNumber()) break
        grounds.add(station)
        grounds.add(xml.double())
        groundPoints++
      }
    }
  }

  private fun build(scene: LandXmlSceneBuilder) {
    geom.prepare(log)
    if (pending.isEmpty()) return
    val sceneSize = CoordGeom.sceneSize(scene)
    val layer = scene.layer(LAYER, LandXmlPalette.ALIGNMENT, LandXmlGroup.ALIGNMENTS)
    // трасса — главная линия файла: вес 0,8 мм (2,5 dp на экране, как в эталоне), толще линий без веса
    val lines = scene.lines(layer, LandXmlPalette.ALIGNMENT, flat = true, lineWeight = ALIGNMENT_WEIGHT)
    val lines3d = scene.lines(layer, LandXmlPalette.ALIGNMENT, flat = false, lineWeight = ALIGNMENT_WEIGHT)
    // слой земли — сразу за слоем трасс, до пикетажа; только если земля есть
    if (pending.any { it.groundPoints > 0 }) scene.layer(GROUND_LAYER, LandXmlPalette.ALIGNMENT_GROUND, LandXmlGroup.ALIGNMENTS)
    var segments = 0
    var withProfile = 0
    for (a in pending) {
      // уравнения по возрастанию staInternal
      val sorted = (a.equationsFrom until a.equationsTo step 3)
        .sortedBy { equations[it] }
        .flatMap { listOf(equations[it], equations[it + 1], equations[it + 2]) }
        .toDoubleArray()
      val geometry = AlignmentGeometry(geom, a.first, a.last, a.staStart, sorted)
      geometries.add(geometry)
      val profile = a.profileName?.let { name ->
        val values = DoubleArray(a.pvisTo - a.pvisFrom) { pvis[a.pvisFrom + it] }
        AlignmentProfile(name, geometry.stationing, values).takeIf { it.size >= 2 }
      }
      profiles.add(profile)
      scene.alignments.add(LandXmlSceneBuilder.BuiltAlignment(a.name, geometry, profile))
      if (a.profileName != null) {
        val pviCount = (a.pvisTo - a.pvisFrom) / AlignmentProfile.PVI_SIZE
        if (profile == null) log(UiText.key("log_profile_short", a.name, a.profileName!!))
        else if (profile.skipped > 0) log(UiText.key("log_profile_skipped", a.name, profile.skipped, pviCount))
        if (a.extraProfiles > 0) log(UiText.key("log_profile_extra", a.name, a.extraProfiles, a.profileName!!))
      }
      val attributes = ArrayList<SceneAttribute>()
      attributes.add(SceneAttribute("@attr_length_m", format(geometry.length)))
      if (!a.length.isNaN() && abs(a.length - geometry.length) > 1e-3 * a.length.coerceAtLeast(1.0)) {
        attributes.add(SceneAttribute("@attr_file_length_m", format(a.length)))
      }
      attributes.add(SceneAttribute("@attr_start_station", format(a.staStart)))
      attributes.add(SceneAttribute("@attr_elements", (a.last - a.first).toString()))
      if (a.desc.isNotBlank()) attributes.add(SceneAttribute("@attr_description", a.desc))
      if (profile != null) {
        attributes.add(SceneAttribute("@attr_profile", profile.name))
        attributes.add(SceneAttribute("PVI", profile.size.toString()))
      }
      if (a.groundPoints > 0) attributes.add(SceneAttribute("@attr_ground_points", a.groundPoints.toString()))
      val speeds = speedStations(a.features)
      if (speeds.isNotEmpty()) attributes.add(SceneAttribute(Features.SPEED_STATION, speedSummary(speeds)))
      Features.attributes(a.features, attributes, skip = Features.SPEED_STATION)
      val id = scene.addObject(SceneObject(KIND, a.name, layer, LandXmlPalette.ALIGNMENT, attributes))
      if (profile != null) {
        withProfile++
        segments += drawProfiled(geometry, profile, lines3d, sceneSize, id)
      } else {
        for (i in a.first until a.last) segments += geom.draw(i, lines, sceneSize, id)
      }
      if (stationing) StationLabels.add(scene, geometry, id, profile)
      drawSpeeds(scene, geometry, profile, a.name, speeds, sceneSize)
      drawPis(scene, geometry, profile, a.name, a.pis)
      // имя — курсивом вдоль линии посередине трассы, на её слое: видно и без пикетажа
      StationLabels.addName(scene, layer, geometry, a.name, id, profile)
    }
    // земля — после всех трасс: номера объектов трасс идут подряд, как трассы в файле
    for ((k, a) in pending.withIndex()) if (a.groundPoints > 0) drawGround(scene, geometries[k], profiles[k], a, sceneSize)
    log(UiText.key("log_alignments", pending.size, withProfile, geom.size, geom.spirals, segments))
    if (geom.unresolved > 0) log(UiText.key("log_alignments_unresolved", geom.unresolved))
    crossSects.build(scene, geometries, alignmentNames)
  }

  /**
   * Земля трассы [a]: куски `PntList2D` — прореженные ломаные ([AlignmentProfile.ground]) вдоль плана, умброй 1,5 dp.
   * С проектным [profile] — стойки 1 dp от земли до проекта на кратных шагу пикетажа пикетах (продолжают его засечки
   * вниз), где разница больше [MIN_STAND]. У земли свой объект: выбор трассы её не обводит.
   */
  private fun drawGround(
    scene: LandXmlSceneBuilder, geometry: AlignmentGeometry, profile: AlignmentProfile?, a: Pending, sceneSize: Double,
  ) {
    val name = a.groundName.orEmpty()
    val runs = ArrayList<AlignmentProfile>()
    var from = a.groundFrom
    for (i in a.groundFrom..a.groundTo step 2) {
      if (i < a.groundTo && !grounds[i].isNaN()) continue
      val points = DoubleArray(i - from) { grounds[from + it] }
      AlignmentProfile.ground(name, geometry.stationing, points, sceneSize)?.let { runs.add(it) }
      from = i + 2
    }
    if (runs.isEmpty()) return
    val attributes = ArrayList<SceneAttribute>()
    attributes.add(SceneAttribute("@attr_alignment", a.name))
    attributes.add(SceneAttribute("@attr_points", a.groundPoints.toString()))
    attributes.add(SceneAttribute("@attr_drawn_points", runs.sumOf { it.size }.toString()))
    val id = scene.addObject(SceneObject(GROUND_KIND, name.ifEmpty { a.name }, GROUND_LAYER, LandXmlPalette.ALIGNMENT_GROUND, attributes))
    val lines = scene.lines(GROUND_LAYER, LandXmlPalette.ALIGNMENT_GROUND, flat = false, lineWeight = GROUND_WEIGHT)
    for (run in runs) drawProfiled(geometry, run, lines, sceneSize, id)
    if (profile == null) return
    val stands = scene.lines(GROUND_LAYER, LandXmlPalette.ALIGNMENT_GROUND, flat = false)
    // шаг — как у подписей пикетажа (StationLabels.add): не больше ~100 стоек на трассу
    val length = geometry.length
    val step = StationLabels.labelStep(if (scene.size > 0.0) scene.size else length, length)
    geometry.stationing.forEachMultiple(step) { d, _ ->
      val top = profile.elevation(d)
      var bottom = Double.NaN
      for (run in runs) {
        bottom = run.elevation(d)
        if (!bottom.isNaN()) break
      }
      // NaN в сравнении — false: стойки нет
      if (!(abs(top - bottom) > MIN_STAND)) return@forEachMultiple
      val p = geometry.atDistance(d) ?: return@forEachMultiple
      stands.add(p.x, p.y, bottom, p.x, p.y, top, id)
    }
  }

  /** Проектные скорости из `Feature name="SpeedStation"`: пары «пикет, скорость» по возрастанию пикета. */
  private fun speedStations(features: List<FeatureData>): List<Pair<Double, Double>> =
    features.filter { it.name == Features.SPEED_STATION }.mapNotNull {
      val station = it.property("station")?.toDoubleOrNull()
      val speed = it.property("speed")?.toDoubleOrNull()
      if (station != null && speed != null && station.isFinite() && speed.isFinite()) station to speed else null
    }.sortedBy { it.first }

  private fun speedSummary(speeds: List<Pair<Double, Double>>): String =
    speeds.joinToString("; ") { "station ${format(it.first)} speed ${format(it.second)}" }

  // полоса цветом скорости на каждом участке от пикета смены до следующей; одинаковые подряд сливаются, подпись — у смены
  private fun drawSpeeds(
    scene: LandXmlSceneBuilder, geometry: AlignmentGeometry, profile: AlignmentProfile?, alignment: String,
    speeds: List<Pair<Double, Double>>, sceneSize: Double,
  ) {
    if (speeds.isEmpty() || geometry.length <= 0.0) return
    val layer = scene.layer(SPEED_LAYER, LandXmlPalette.scale(LandXmlPalette.SCALE_SPEED, 1.0), LandXmlGroup.ALIGNMENTS)
    val low = speeds.minOf { it.second }
    val high = speeds.maxOf { it.second }
    val end = geometry.stationing.station(geometry.length)
    var i = 0
    while (i < speeds.size) {
      val (from, speed) = speeds[i]
      var j = i + 1
      while (j < speeds.size && speeds[j].second == speed) j++
      val to = if (j < speeds.size) speeds[j].first else end
      i = j
      if (to <= from) continue
      val color = LandXmlPalette.scale(LandXmlPalette.SCALE_SPEED, LandXmlPalette.position(speed, low, high))
      val text = "${Features.SPEED_STATION} ${format(speed)}"
      val attributes = ArrayList<SceneAttribute>()
      attributes.add(SceneAttribute("Alignment", alignment))
      attributes.add(SceneAttribute("speed", format(speed)))
      attributes.add(SceneAttribute("staStart", format(from)))
      attributes.add(SceneAttribute("staEnd", format(to)))
      // шкала цвета: от минимума до максимума SpeedStation трассы
      if (high > low) attributes.add(SceneAttribute("range", "${format(low)} … ${format(high)}"))
      val id = scene.addObject(SceneObject(SPEED_KIND, text, layer, color, attributes))
      StationOverlay.drawSpan(scene, geometry, profile, layer, color, from, to, lane = 1.0, sceneSize = sceneSize, objectId = id)
      StationOverlay.labelSpan(scene, geometry, profile, layer, from, to, 1.0, text, sceneSize, id)
    }
  }

  // углы поворота: значок на PI, подпись «ВУ n» (с пикетом, если он есть)
  private fun drawPis(
    scene: LandXmlSceneBuilder, geometry: AlignmentGeometry, profile: AlignmentProfile?, alignment: String, data: DoubleArray,
  ) {
    if (data.isEmpty()) return // слой без объектов в списке не нужен
    val layer = scene.layer(PI_LAYER, LandXmlPalette.ALIGN_PI, LandXmlGroup.ALIGNMENTS)
    for (k in 0 until data.size / 3) {
      val x = data[k * 3]
      val y = data[k * 3 + 1]
      val station = data[k * 3 + 2]
      val name = "PI${k + 1}"
      val attributes = ArrayList<SceneAttribute>()
      attributes.add(SceneAttribute("Alignment", alignment))
      if (!station.isNaN()) attributes.add(SceneAttribute("Station", format(station)))
      attributes.add(SceneAttribute("X", format(x)))
      attributes.add(SceneAttribute("Y", format(y)))
      val id = scene.addObject(SceneObject(PI_KIND, name, layer, LandXmlPalette.ALIGN_PI, attributes))
      // отметка — по профилю на пикете, если он на трассе; PI лежит вне трассы, а пикет на ней
      val z = if (!station.isNaN() && StationOverlay.contains(geometry, station)) {
        profile?.elevation(geometry.stationing.distance(station)) ?: Double.NaN
      } else {
        Double.NaN
      }
      val flat = z.isNaN()
      scene.addPoint(PointMark(layer, LandXmlPalette.ALIGN_PI, x, y, if (flat) 0.0 else z, objectId = id), flat)
      scene.addLabel(layer, LandXmlPalette.LABEL, x, y, if (flat) 0.0 else z, name, objectId = id, flat = flat, alignV = 2, shiftDp = LabelScale.MARK_SHIFT_DP)
    }
  }

  private class Pending(
    val name: String, val desc: String, val length: Double, val staStart: Double,
    val first: Int, val last: Int, val equationsFrom: Int, val equationsTo: Int,
    val profileName: String?, val pvisFrom: Int, val pvisTo: Int, val extraProfiles: Int,
    val features: List<FeatureData>, val pis: DoubleArray,
    val groundName: String?, val groundFrom: Int, val groundTo: Int, val groundPoints: Int,
  )

  companion object {
    /** Вес линии трассы, 1/100 мм: [dev.iskandern.kotin.scene.lineWidthPx] даёт 2,5. */
    const val ALIGNMENT_WEIGHT = 80

    /** Вес линии земли: 1,5 dp на экране; стойки — без веса, 1 dp. */
    const val GROUND_WEIGHT = 50

    const val LAYER = "@layer_alignments"
    const val KIND = "Alignment"
    const val SPEED_KIND = "SpeedStation"
    const val PI_KIND = "AlignPI"
    const val SPEED_LAYER = "@layer_alignment_speed"
    const val PI_LAYER = "@layer_alignment_pi"
    const val GROUND_LAYER = "@layer_alignment_ground"
    const val GROUND_KIND = "ProfSurf"
    private const val MAX_PIS = 5000
    // точек земли на файл; стойка — где проект и земля расходятся больше, м
    private const val MAX_GROUND = 2_000_000
    private const val MIN_STAND = 0.05

    private val NAMES = XmlNames(
      *CoordGeom.NAMES, "Alignment", "StaEquation", "Profile", "ProfAlign", "PVI", "ParaCurve", "UnsymParaCurve", "CircCurve",
      *CrossSects.NAMES, "Feature", "AlignPIs", "AlignPI", "Station", "ProfSurf",
    )
    private val ALIGNMENT = CoordGeom.NAMES.size
    private val STA_EQUATION = CoordGeom.NAMES.size + 1
    private val PROFILE = CoordGeom.NAMES.size + 2
    private val PROF_ALIGN = CoordGeom.NAMES.size + 3
    private val PVI = CoordGeom.NAMES.size + 4
    private val PARA_CURVE = CoordGeom.NAMES.size + 5
    private val UNSYM_PARA_CURVE = CoordGeom.NAMES.size + 6
    private val CIRC_CURVE = CoordGeom.NAMES.size + 7
    private val CROSS_SECTS = CoordGeom.NAMES.size + 8
    private val FEATURE = CoordGeom.NAMES.size + 8 + CrossSects.NAMES.size
    private val ALIGN_PIS = FEATURE + 1
    private val ALIGN_PI = FEATURE + 2
    private val STATION = FEATURE + 3
    private val PROF_SURF = FEATURE + 4
    private val LENGTH_IN = "lengthIn".encodeToByteArray()
    private val LENGTH_OUT = "lengthOut".encodeToByteArray()
    private val RADIUS = "radius".encodeToByteArray()

    private val NAME = "name".encodeToByteArray()
    private val DESC = "desc".encodeToByteArray()
    private val LENGTH = "length".encodeToByteArray()
    private val STA_START = "staStart".encodeToByteArray()
    private val STA_INTERNAL = "staInternal".encodeToByteArray()
    private val STA_AHEAD = "staAhead".encodeToByteArray()
    private val STA_INCREMENT = "staIncrement".encodeToByteArray()
    private val DECREASING = "decreasing".encodeToByteArray()

    // число с тремя знаками после запятой, без экспоненты
    internal fun format(value: Double): String {
      if (!value.isFinite()) return value.toString()
      val milli = (abs(value) * 1000).roundToLong()
      val sign = if (value < 0 && milli != 0L) "-" else ""
      val fraction = (milli % 1000).toString().padStart(3, '0').trimEnd('0')
      return sign + (milli / 1000) + if (fraction.isEmpty()) "" else ".$fraction"
    }
  }
}
