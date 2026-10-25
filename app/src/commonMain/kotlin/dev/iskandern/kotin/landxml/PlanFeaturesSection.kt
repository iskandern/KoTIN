package dev.iskandern.kotin.landxml

import dev.iskandern.kotin.model.GrowingDoubleArray
import dev.iskandern.kotin.model.Journal
import dev.iskandern.kotin.model.PointMark
import dev.iskandern.kotin.model.SceneAttribute
import dev.iskandern.kotin.model.SceneObject
import dev.iskandern.kotin.model.UiText

/**
 * Раздел `PlanFeatures`: ситуация — бровки, ограждения, контуры зданий, опоры, знаки. `PlanFeature[name, desc]` —
 * в любом порядке `CoordGeom` ([CoordGeom]) → ломаные и `Location` («север восток [отметка]» или `pntRef`) → значок
 * точки и подпись с именем; `Feature/Property` — в панель объекта, `FieldNote` пропускается. Слой — имя `PlanFeature`, без имени — его `desc` без номеров («Curb Back of Curb»), своим цветом ([LandXmlPalette.planFeature]); без обоих — «Ситуация».
 * `Chain` берёт отметки `CgPoint` ([CoordGeom] с `chainElevations`).
 * Линии в плане и `Location` без отметки — `flat` (накопитель сажает их на поверхность файла, вне её сцена кладёт на
 * уровень), `IrregularLine` с `PntList3D` — со своими отметками.
 * Ссылки и дуги — в `finish()`; объект сцены — один на `PlanFeature`, атрибуты — только непустые.
 */
internal class PlanFeaturesSection(
  private val log: (String) -> Unit = Journal::log,
) : LandXmlSection("PlanFeatures", NAMES) {
  private val geom = CoordGeom(chainElevations = true)
  private val pending = ArrayList<Pending>()
  // точки Location подряд: x, y, z (x, y NaN — координат нет или ссылка не найдена; z NaN — без отметки)
  private val locations = GrowingDoubleArray()
  // ссылки pntRef у Location: номер точки и имя; редки — список объектов
  private val refs = ArrayList<Pair<Int, String>>()
  private val point = DoubleArray(3)
  private var deferred = false
  private var unresolved = 0

  override fun read(xml: XmlTokenizer, scene: LandXmlSceneBuilder) {
    if (!deferred) {
      deferred = true
      scene.defer(LandXmlSceneBuilder.STAGE_REFERENCES) { resolve(scene) }
      scene.defer(LandXmlSceneBuilder.STAGE_CURVES) { build(scene) }
    }
    val depth = xml.depth
    while (xml.nextChild(depth)) {
      if (xml.nameId == PLAN_FEATURE) readFeature(xml, scene) else xml.skipElement()
      scene.tick()
    }
  }

  private fun readFeature(xml: XmlTokenizer, scene: LandXmlSceneBuilder) {
    val name = xml.attrString(NAME)?.trim().orEmpty()
    val code = xml.attrString(CODE)?.trim().orEmpty()
    val desc = xml.attrString(DESC)?.trim().orEmpty()
    val features = ArrayList<FeatureData>()
    val first = geom.size
    val locationsFrom = locations.size / 3
    val depth = xml.depth
    while (xml.nextChild(depth)) {
      when (xml.nameId) {
        CoordGeom.COORD_GEOM -> geom.read(xml, scene)
        LOCATION -> readLocation(xml)
        FEATURE -> if (features.size < Features.MAX_FEATURES) features.add(Features.read(xml)) else xml.skipElement()
        // FieldNote
        else -> xml.skipElement()
      }
    }
    pending.add(Pending(name, code, desc, first, geom.size, locationsFrom, locations.size / 3, features))
  }

  private fun readLocation(xml: XmlTokenizer) {
    val ref = xml.attrString(PNT_REF)
    if (ref != null) {
      refs.add(locations.size / 3 to ref.trim())
      point.fill(Double.NaN)
      xml.skipElement()
    } else {
      val read = LandXmlCoord.readPoint(xml, point)
      if (read < 2) point.fill(Double.NaN)
      // без отметки — z NaN: в build() точка плоская, её сажает посадка (KoTIN-67)
      else if (read == 2) point[2] = Double.NaN
    }
    locations.add(point[0])
    locations.add(point[1])
    locations.add(point[2])
  }

  private fun resolve(scene: LandXmlSceneBuilder) {
    geom.resolve(scene.pointNames, scene)
    for ((index, name) in refs) {
      if (scene.pointNames.find(name, point)) {
        locations[index * 3] = point[0]
        locations[index * 3 + 1] = point[1]
        locations[index * 3 + 2] = point[2]
      } else {
        unresolved++
      }
    }
    refs.clear()
  }

  private fun build(scene: LandXmlSceneBuilder) {
    geom.prepare()
    if (pending.isEmpty()) return
    val sceneSize = CoordGeom.sceneSize(scene)
    // без имени объект идёт на слой своего вида (`desc` без номеров) со своим цветом, без `desc` — на «Ситуацию»
    val kinds = LinkedHashMap<String, Int>()
    var segments = 0
    var marks = 0
    var labels = 0
    for (f in pending) {
      val kind = if (f.name.isEmpty()) kindOf(f.desc) else ""
      val color = if (kind.isEmpty()) LandXmlPalette.PLAN_FEATURE else LandXmlPalette.planFeature(kinds.getOrPut(kind) { kinds.size })
      // имя из файла — как есть; без имени — вид по описанию в общей форме «Ситуация: <вид>»
      val name = f.name.ifEmpty { if (kind.isEmpty()) DEFAULT_LAYER else UiText.key(KIND_LAYER, kind) }
      val layer = scene.layer(name, color, LandXmlGroup.PLANIMETRY)
      val attributes = ArrayList<SceneAttribute>()
      if (f.code.isNotEmpty()) attributes.add(SceneAttribute("@attr_code", f.code))
      if (f.desc.isNotEmpty()) attributes.add(SceneAttribute("@attr_description", f.desc))
      Features.attributes(f.features, attributes)
      val id = scene.addObject(SceneObject(KIND, f.name, layer, color, attributes))
      if (f.first < f.last) {
        val flat = scene.lines(layer, color, flat = true)
        val elevated = scene.lines(layer, color, flat = false)
        for (i in f.first until f.last) {
          segments += geom.draw(i, if (geom.hasElevations(i)) elevated else flat, sceneSize, id, elevations = true)
        }
      }
      for (k in f.locationsFrom until f.locationsTo) {
        val x = locations[k * 3]
        val y = locations[k * 3 + 1]
        val flat = locations[k * 3 + 2].isNaN()
        val z = if (flat) 0.0 else locations[k * 3 + 2]
        if (x.isNaN() || y.isNaN()) continue
        scene.addPoint(PointMark(layer, color, x, y, z, objectId = id), flat)
        marks++
        if (f.name.isNotEmpty() && labels < MAX_LABELS) {
          // имя — правее значка, как у точек
          scene.addLabel(layer, LandXmlPalette.LABEL, x, y, z, f.name, objectId = id, flat = flat, alignV = 2, shiftDp = LabelScale.MARK_SHIFT_DP)
          labels++
        }
      }
    }
    log(UiText.key("log_planfeatures", pending.size, geom.size, segments, marks))
    if (labels >= MAX_LABELS) log(UiText.key("log_planfeatures_labels", MAX_LABELS))
    if (geom.spirals > 0) log(UiText.key("log_planfeatures_spirals", geom.spirals))
    val missing = geom.unresolved + unresolved
    if (missing > 0) log(UiText.key("log_planfeatures_missing", missing))
  }

  // «Curb 23 Back of Curb» → «Curb Back of Curb»: числа между словами — номера экземпляров, не вид
  private fun kindOf(desc: String): String = desc.split(' ').filter { it.isNotEmpty() && !it.all(Char::isDigit) }.joinToString(" ")

  private class Pending(
    val name: String, val code: String, val desc: String,
    val first: Int, val last: Int, val locationsFrom: Int, val locationsTo: Int, val features: List<FeatureData>,
  )

  companion object {
    const val DEFAULT_LAYER = "@layer_planimetry"
    /** Ключ слоя объектов без имени одного вида: «Ситуация: <вид>». */
    const val KIND_LAYER = "layer_planimetry_kind"
    const val KIND = "PlanFeature"

    /** Подписей точек ситуации — не больше стольких (как у точек CgPoint). */
    const val MAX_LABELS = CgPointsSection.MAX_DETAILED

    private val NAMES = XmlNames(*CoordGeom.NAMES, "PlanFeature", "Location", "Feature")
    private val PLAN_FEATURE = CoordGeom.NAMES.size
    private val LOCATION = CoordGeom.NAMES.size + 1
    private val FEATURE = CoordGeom.NAMES.size + 2

    private val NAME = "name".encodeToByteArray()
    private val CODE = "code".encodeToByteArray()
    private val DESC = "desc".encodeToByteArray()
    private val PNT_REF = "pntRef".encodeToByteArray()
  }
}
