package dev.iskandern.kotin.landxml

import dev.iskandern.kotin.model.GrowingDoubleArray
import dev.iskandern.kotin.model.GrowingIntArray
import dev.iskandern.kotin.model.SceneAttribute
import dev.iskandern.kotin.model.SceneObject
import dev.iskandern.kotin.model.UiText
import kotlin.math.cos
import kotlin.math.sin

/**
 * Поперечники трассы `Alignment/CrossSects/CrossSect[sta]` → 3D-ломаные поперёк трассы (KoTIN-69).
 * `CrossSectSurf` — земля: каждый `PntList2D` (пары «смещение отметка») — своя ломаная, несколько списков — разрыв.
 * `DesignCrossSectSurf` — проект: точки `CrossSectPnt` одной ломаной; уклон–расстояние и точки по ссылкам пропускаются.
 * Точка: `P + смещение × правая нормаль` (направление трассы, повёрнутое на −90°), отметка — из файла.
 * Читаются в массивы вместе с трассой, строятся в [build] — когда готова геометрия трасс.
 */
internal class CrossSects(
  /** Номер имени `CrossSects` в [XmlNames] раздела трасс: за ним по порядку остальные [NAMES]. */
  base: Int,
  private val log: (String) -> Unit,
) {
  private val crossSect = base + 1
  private val crossSectSurf = base + 2
  private val designCrossSectSurf = base + 3
  private val crossSectPnt = base + 4

  // пары «смещение отметка» подряд
  private val values = GrowingDoubleArray()
  // ломаные тройками: первая пара в [values], число точек, вид (GROUND / DESIGN)
  private val polylines = GrowingIntArray()
  // поперечники тройками: трасса, первая и следующая за последней ломаная; пикеты — отдельно
  private val sections = GrowingIntArray()
  private val stations = GrowingDoubleArray()
  private var slopeDistance = 0
  private var references = 0

  /** `CrossSects` трассы номер [alignment] (по порядку файла). */
  fun read(xml: XmlTokenizer, scene: LandXmlSceneBuilder, alignment: Int) {
    val depth = xml.depth
    while (xml.nextChild(depth)) {
      if (xml.nameId == crossSect) readSection(xml, alignment) else xml.skipElement()
      scene.tick()
    }
  }

  private fun readSection(xml: XmlTokenizer, alignment: Int) {
    val sta = xml.attrDouble(STA)
    if (sta.isNaN()) {
      xml.skipElement()
      return
    }
    val first = polylines.size / 3
    val depth = xml.depth
    while (xml.nextChild(depth)) {
      when (xml.nameId) {
        crossSectSurf -> {
          val surfDepth = xml.depth
          while (xml.nextChild(surfDepth)) {
            if (xml.nameId == CoordGeom.PNT_LIST_2D) readList(xml) else xml.skipElement()
          }
        }
        designCrossSectSurf -> readDesign(xml)
        else -> xml.skipElement()
      }
    }
    val last = polylines.size / 3
    if (last == first) return
    sections.add(alignment)
    sections.add(first)
    sections.add(last)
    stations.add(sta)
  }

  // PntList2D земли: пары прямо в массив; одиночная точка остаётся в массиве без ломаной
  private fun readList(xml: XmlTokenizer) {
    val from = values.size / 2
    var count = 0
    while (xml.nextNumber()) {
      val offset = xml.double()
      if (!xml.nextNumber()) break
      values.add(offset)
      values.add(xml.double())
      count++
    }
    if (count >= 2) addPolyline(from, count, GROUND)
  }

  // точки проекта — одной ломаной; непрочитанное nextChild пропускает сам
  private fun readDesign(xml: XmlTokenizer) {
    val from = values.size / 2
    var count = 0
    val depth = xml.depth
    while (xml.nextChild(depth)) {
      if (xml.nameId != crossSectPnt) continue
      if (xml.attrEquals(DATA_FORMAT, SLOPE_DISTANCE)) {
        slopeDistance++
        continue
      }
      if (xml.findAttr(ALIGN_REF) || xml.findAttr(PLAN_FEATURE_REF) || xml.findAttr(PARCEL_REF)) {
        references++
        continue
      }
      if (!xml.nextNumber()) continue
      val offset = xml.double()
      if (!xml.nextNumber()) continue
      values.add(offset)
      values.add(xml.double())
      count++
    }
    if (count >= 2) addPolyline(from, count, DESIGN)
  }

  private fun addPolyline(from: Int, count: Int, kind: Int) {
    polylines.add(from)
    polylines.add(count)
    polylines.add(kind)
  }

  /** Ломаные в сцену: [geometries] и [names] — трассы по порядку файла. */
  fun build(scene: LandXmlSceneBuilder, geometries: List<AlignmentGeometry>, names: List<String>) {
    if (slopeDistance > 0) log(UiText.key("log_cross_slope", slopeDistance))
    if (references > 0) log(UiText.key("log_cross_refs", references))
    val count = stations.size
    if (count == 0) return
    val groundLayer = scene.layer(GROUND_LAYER, LandXmlPalette.CROSS_SECTION, LandXmlGroup.CROSS_SECTIONS)
    val designLayer = scene.layer(DESIGN_LAYER, LandXmlPalette.CROSS_SECTION_DESIGN, LandXmlGroup.CROSS_SECTIONS)
    val ground = scene.lines(groundLayer, LandXmlPalette.CROSS_SECTION, flat = false)
    val design = scene.lines(designLayer, LandXmlPalette.CROSS_SECTION_DESIGN, flat = false)
    var outside = 0
    for (s in 0 until count) {
      val alignment = sections[s * 3]
      val sta = stations[s]
      val p = geometries[alignment].at(sta)
      if (p == null) {
        outside++
        continue
      }
      // правая нормаль — направление, повёрнутое на −90°
      val nx = sin(p.direction)
      val ny = -cos(p.direction)
      val first = sections[s * 3 + 1]
      val last = sections[s * 3 + 2]
      var points = 0
      var hasGround = false
      for (k in first until last) {
        points += polylines[k * 3 + 1]
        if (polylines[k * 3 + 2] == GROUND) hasGround = true
      }
      val station = AlignmentsSection.format(sta)
      val attributes = listOf(
        SceneAttribute("@attr_alignment", names[alignment]),
        SceneAttribute("@attr_station", station),
        SceneAttribute("@attr_points", points.toString()),
      )
      val id = scene.addObject(
        SceneObject(
          KIND, UiText.key("cross_section_name", names[alignment], station), if (hasGround) groundLayer else designLayer,
          if (hasGround) LandXmlPalette.CROSS_SECTION else LandXmlPalette.CROSS_SECTION_DESIGN, attributes,
        )
      )
      for (k in first until last) {
        val lines = if (polylines[k * 3 + 2] == GROUND) ground else design
        val n = polylines[k * 3 + 1]
        var at = polylines[k * 3] * 2
        var x0 = p.x + values[at] * nx
        var y0 = p.y + values[at] * ny
        var z0 = values[at + 1]
        for (i in 1 until n) {
          at += 2
          val x1 = p.x + values[at] * nx
          val y1 = p.y + values[at] * ny
          val z1 = values[at + 1]
          lines.add(x0, y0, z0, x1, y1, z1, id)
          x0 = x1
          y0 = y1
          z0 = z1
        }
      }
    }
    log(UiText.key("log_cross", count - outside))
    if (outside > 0) log(UiText.key("log_cross_outside", outside))
  }

  companion object {
    const val GROUND_LAYER = "@layer_cross_ground"
    const val DESIGN_LAYER = "@layer_cross_design"
    const val KIND = "CrossSect"

    /** Имена элементов поперечников — в [XmlNames] раздела трасс подряд, с `base`. */
    val NAMES = arrayOf("CrossSects", "CrossSect", "CrossSectSurf", "DesignCrossSectSurf", "CrossSectPnt")

    private const val GROUND = 0
    private const val DESIGN = 1

    private val STA = "sta".encodeToByteArray()
    private val DATA_FORMAT = "dataFormat".encodeToByteArray()
    private val SLOPE_DISTANCE = "Slope Distance".encodeToByteArray()
    private val ALIGN_REF = "alignRef".encodeToByteArray()
    private val PLAN_FEATURE_REF = "planFeatureRef".encodeToByteArray()
    private val PARCEL_REF = "parcelRef".encodeToByteArray()
  }
}
