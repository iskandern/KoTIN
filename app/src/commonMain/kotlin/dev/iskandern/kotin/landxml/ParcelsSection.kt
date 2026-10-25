package dev.iskandern.kotin.landxml

import dev.iskandern.kotin.model.GrowingIntArray
import dev.iskandern.kotin.model.Journal
import dev.iskandern.kotin.model.SceneAttribute
import dev.iskandern.kotin.model.SceneObject
import dev.iskandern.kotin.model.UiText
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Раздел `Parcels`: участки `Parcel[name, area, parcelType, class, desc]` → замкнутый контур на слое «Участки»
 * (не замкнут в файле — замыкается отрезком от конца последнего элемента к началу первого), объект (площадь из
 * атрибута `area` — в свойствах) и заголовок-имя в `Center` (текст или `pntRef`), иначе в центре габаритов контура;
 * заголовки соседних центров укладываются столбиком ([LandXmlSceneBuilder.addTitle]). Геометрия — [CoordGeom]; вложенные `Parcels` читаются так же, `VolumeGeom`, `Title`, `Exclusions`,
 * `LocationAddress` пропускаются. Ссылки и дуги — в `finish()`.
 */
internal class ParcelsSection(
  private val log: (String) -> Unit = Journal::log,
) : LandXmlSection("Parcels", NAMES) {
  private val geom = CoordGeom()
  // участки в порядке начала в файле: внешний раньше вложенных
  private val pending = ArrayList<Pending>()
  private val point = DoubleArray(3)
  private var deferred = false
  private var unresolved = 0

  override fun read(xml: XmlTokenizer, scene: LandXmlSceneBuilder) {
    if (!deferred) {
      deferred = true
      scene.defer(LandXmlSceneBuilder.STAGE_REFERENCES) { resolve(scene) }
      scene.defer(LandXmlSceneBuilder.STAGE_CURVES) { build(scene) }
    }
    readGroup(xml, scene)
  }

  private fun readGroup(xml: XmlTokenizer, scene: LandXmlSceneBuilder) {
    val depth = xml.depth
    while (xml.nextChild(depth)) {
      if (xml.nameId == PARCEL) readParcel(xml, scene) else xml.skipElement()
      scene.tick()
    }
  }

  private fun readParcel(xml: XmlTokenizer, scene: LandXmlSceneBuilder) {
    val parcel = Pending(
      name = xml.attrString(NAME)?.trim().orEmpty(),
      area = xml.attrDouble(AREA),
      type = xml.attrString(PARCEL_TYPE)?.trim().orEmpty(),
      parcelClass = xml.attrString(CLASS)?.trim().orEmpty(),
      desc = xml.attrString(DESC)?.trim().orEmpty(),
    )
    pending.add(parcel)
    val depth = xml.depth
    while (xml.nextChild(depth)) {
      when (xml.nameId) {
        CoordGeom.COORD_GEOM -> {
          // свои элементы — диапазонами: вложенные участки пишут свои элементы в тот же [geom] между ними
          val first = geom.size
          geom.read(xml, scene)
          parcel.ranges.add(first)
          parcel.ranges.add(geom.size)
        }
        CoordGeom.CENTER -> {
          val ref = xml.attrString(PNT_REF)
          if (ref != null) {
            parcel.centerRef = ref.trim()
            xml.skipElement()
          } else if (LandXmlCoord.readPoint(xml, point) >= 2) {
            parcel.centerX = point[0]
            parcel.centerY = point[1]
          }
        }
        PARCELS -> readGroup(xml, scene)
        // VolumeGeom, Title, Exclusions, LocationAddress, Feature
        else -> xml.skipElement()
      }
    }
  }

  private fun resolve(scene: LandXmlSceneBuilder) {
    geom.resolve(scene.pointNames, scene)
    for (p in pending) {
      val ref = p.centerRef ?: continue
      if (scene.pointNames.find(ref, point)) {
        p.centerX = point[0]
        p.centerY = point[1]
      } else {
        unresolved++
      }
    }
  }

  private fun build(scene: LandXmlSceneBuilder) {
    geom.prepare()
    if (pending.isEmpty()) return
    val sceneSize = CoordGeom.sceneSize(scene)
    val color = LandXmlPalette.PARCEL
    val layer = scene.layer(LAYER, color, LandXmlGroup.PLANIMETRY)
    val flat = scene.lines(layer, color, flat = true)
    val elevated = scene.lines(layer, color, flat = false)
    val start = DoubleArray(3)
    val end = DoubleArray(3)
    val box = DoubleArray(4)
    var segments = 0
    var closed = 0
    var labels = 0
    for (p in pending) {
      val attributes = ArrayList<SceneAttribute>(4)
      if (!p.area.isNaN()) attributes.add(SceneAttribute("@attr_area", AlignmentsSection.format(p.area)))
      if (p.type.isNotEmpty()) attributes.add(SceneAttribute("@attr_type", p.type))
      if (p.parcelClass.isNotEmpty()) attributes.add(SceneAttribute("@attr_class", p.parcelClass))
      if (p.desc.isNotEmpty()) attributes.add(SceneAttribute("@attr_description", p.desc))
      val id = scene.addObject(SceneObject(KIND, p.name, layer, color, if (attributes.isEmpty()) emptyList() else attributes))
      // контур: элементы по порядку; первое начало и последний конец — для замыкания
      var firstElement = -1
      var lastElement = -1
      box[0] = Double.POSITIVE_INFINITY
      box[1] = Double.POSITIVE_INFINITY
      box[2] = Double.NEGATIVE_INFINITY
      box[3] = Double.NEGATIVE_INFINITY
      for (r in 0 until p.ranges.size step 2) {
        for (i in p.ranges[r] until p.ranges[r + 1]) {
          val drawn = geom.draw(i, if (geom.hasElevations(i)) elevated else flat, sceneSize, id, elevations = true)
          if (drawn == 0) continue
          segments += drawn
          if (firstElement < 0) firstElement = i
          lastElement = i
          geom.extend(i, box)
        }
      }
      if (firstElement >= 0 && geom.start(firstElement, start) && geom.end(lastElement, end) &&
        abs(start[0] - end[0]) + abs(start[1] - end[1]) > CLOSE_TOLERANCE
      ) {
        val withZ = geom.hasElevations(firstElement) && geom.hasElevations(lastElement)
        (if (withZ) elevated else flat).add(end[0], end[1], end[2], start[0], start[1], start[2], id)
        segments++
        closed++
      }
      if (p.name.isEmpty() || labels >= MAX_LABELS) continue
      val x: Double
      val y: Double
      if (!p.centerX.isNaN() && !p.centerY.isNaN()) {
        x = p.centerX
        y = p.centerY
      } else if (box[0] <= box[2]) {
        x = (box[0] + box[2]) / 2
        y = (box[1] + box[3]) / 2
      } else {
        continue
      }
      // радиус «того же места» — доля диагонали габаритов: у участка и его части (Carlson: «13» и «13_BA») центры
      // в 0,4–7,5 м при размере участков 200–300 м
      val reach = if (box[0] <= box[2]) TITLE_REACH * hypot(box[2] - box[0], box[3] - box[1]) else 0.0
      scene.addTitle(layer, LandXmlPalette.LABEL, x, y, p.name, id, reach)
      labels++
    }
    log(UiText.key("log_parcels", pending.size, geom.size, segments, closed))
    if (labels >= MAX_LABELS) log(UiText.key("log_parcels_labels", MAX_LABELS))
    if (geom.spirals > 0) log(UiText.key("log_parcels_spirals", geom.spirals))
    val missing = geom.unresolved + unresolved
    if (missing > 0) log(UiText.key("log_parcels_missing", missing))
  }

  private class Pending(
    val name: String, val area: Double, val type: String, val parcelClass: String, val desc: String,
  ) {
    // элементы [geom] участка: пары «первый, за последним»
    val ranges = GrowingIntArray()
    var centerX = Double.NaN
    var centerY = Double.NaN
    var centerRef: String? = null
  }

  companion object {
    const val LAYER = "@layer_parcels"
    const val KIND = "Parcel"

    /** Подписей участков — не больше стольких (как у точек CgPoint). */
    const val MAX_LABELS = CgPointsSection.MAX_DETAILED

    // контур считается замкнутым, если конец сходится с началом точнее (м)
    private const val CLOSE_TOLERANCE = 1e-6
    // заголовки ближе этой доли диагонали габаритов участка — столбиком
    private const val TITLE_REACH = 0.1

    private val NAMES = XmlNames(*CoordGeom.NAMES, "Parcels", "Parcel")
    private val PARCELS = CoordGeom.NAMES.size
    private val PARCEL = CoordGeom.NAMES.size + 1

    private val NAME = "name".encodeToByteArray()
    private val AREA = "area".encodeToByteArray()
    private val PARCEL_TYPE = "parcelType".encodeToByteArray()
    private val CLASS = "class".encodeToByteArray()
    private val DESC = "desc".encodeToByteArray()
    private val PNT_REF = "pntRef".encodeToByteArray()
  }
}
