package dev.iskandern.kotin.landxml

import dev.iskandern.kotin.model.GrowingDoubleArray
import dev.iskandern.kotin.model.Journal
import dev.iskandern.kotin.model.PointMark
import dev.iskandern.kotin.model.SceneAttribute
import dev.iskandern.kotin.model.SceneObject
import dev.iskandern.kotin.model.UiText

/**
 * `Surface/SourceData` — исходные данные поверхности ([SurfacesSection]); слои — «<поверхность>: …»:
 * - `Boundaries/Boundary[bndType, edgeTrim, name, area]` → замкнутый контур (замыкающий отрезок, если первая точка
 *   не повторена), цвет по `bndType`; `edgeTrim` и прочее — в атрибуты; слой «…: границы»;
 * - `Breaklines/Breakline[brkType, name, desc]` → ломаная, цвет по `brkType`; слой «…: структурные линии»;
 * - `Contours/Contour[elev]` → ломаная на высоте `elev`, объект без атрибутов (имя — отметка); слой «…: горизонтали»;
 * - `DataPoints` (группы `PntList3D`) → значки без подписей и объектов на слое «…: точки данных», не больше
 *   [MAX_DATA_POINTS] на `SourceData` (больше — не создаются, строка в консоль);
 * - `Chain` (имена `CgPoint` через пробел) → ломаная на слое структурных линий, имена — в `finish()`.
 * `PointFiles` и прочее пропускаются.
 *
 * Список — `PntList2D` («N E …») или `PntList3D` («N E Z …»), длина точки — по имени элемента; читается потоком
 * в один переиспользуемый массив, текст не накапливается. Меньше двух точек — пропуск со счётчиком. Ломаная с отметками —
 * `flat = false`; 2D-список (кроме горизонтали) — `flat = true`, сцена кладёт его на уровень поверхности.
 * Отрезки — в пакет слоя и цвета ([LandXmlSceneBuilder.lines]), номера объектов — сериями; лимит отрезков — у накопителя.
 */
internal class SurfaceSourceData(
  private val log: (String) -> Unit = Journal::log,
) {
  /** Строк, созданных токенизатором при чтении списков точек; должно быть 0. */
  var hotStrings = 0L
    private set

  // точки текущего списка: x, y, z подряд
  private val list = GrowingDoubleArray()
  private val point = DoubleArray(3)
  private val data = GrowingDoubleArray()
  private val chains = ArrayList<Chain>()
  private var deferred = false

  /** Внешних границ (`bndType` outer или без типа) у текущей поверхности; сбрасывает раздел поверхностей. */
  var outerBoundaries = 0

  // счётчики текущего SourceData
  private var boundaries = 0
  private var breaklines = 0
  private var contours = 0
  private var dataPoints = 0
  private var short = 0
  private var noElev = 0

  /** На START `SourceData`; [surface] — слой поверхности, от него имена слоёв исходных данных. */
  fun read(xml: XmlTokenizer, scene: LandXmlSceneBuilder, surface: String) {
    boundaries = 0
    breaklines = 0
    contours = 0
    dataPoints = 0
    short = 0
    noElev = 0
    data.clear()
    val depth = xml.depth
    while (xml.nextChild(depth)) {
      when (xml.nameId) {
        BOUNDARIES, BREAKLINES, CONTOURS -> readGroup(xml, scene, surface)
        DATA_POINTS -> readDataPoints(xml)
        CHAIN -> readChain(xml, scene, surface)
        else -> xml.skipElement()
      }
    }
    log(UiText.key("log_source_data", surface, boundaries, breaklines, contours, dataPoints))
    if (short + noElev > 0) log(UiText.key("log_source_data_short", surface, short, noElev))
    addDataPoints(scene, surface)
  }

  // Boundaries, Breaklines, Contours: по элементу на линию, Feature и RetWall пропускаются
  private fun readGroup(xml: XmlTokenizer, scene: LandXmlSceneBuilder, surface: String) {
    val depth = xml.depth
    while (xml.nextChild(depth)) {
      when (xml.nameId) {
        BOUNDARY -> readBoundary(xml, scene, surface)
        BREAKLINE -> readBreakline(xml, scene, surface)
        CONTOUR -> readContour(xml, scene, surface)
        else -> xml.skipElement()
      }
      scene.tick()
    }
  }

  private fun readBoundary(xml: XmlTokenizer, scene: LandXmlSceneBuilder, surface: String) {
    // атрибуты — до чтения текста
    val name = xml.attrString(NAME).orEmpty()
    val type = xml.attrString(BND_TYPE).orEmpty()
    val edgeTrim = xml.attrString(EDGE_TRIM).orEmpty()
    val desc = xml.attrString(DESC).orEmpty()
    val area = xml.attrDouble(AREA)
    val dimensions = readList(xml)
    val n = list.size / 3
    if (n < 2) {
      short++
      return
    }
    boundaries++
    if (type.isEmpty() || type == "outer") outerBoundaries++
    val color = LandXmlPalette.boundary(type)
    val layer = scene.layer(UiText.key(BOUNDARY_LAYER, surface), LandXmlPalette.BOUNDARY, LandXmlGroup.SOURCE_DATA)
    val attributes = ArrayList<SceneAttribute>(5)
    if (type.isNotEmpty()) attributes.add(SceneAttribute("@attr_type", type))
    if (edgeTrim.isNotEmpty()) attributes.add(SceneAttribute("@attr_edge_trim", edgeTrim))
    if (!area.isNaN()) attributes.add(SceneAttribute("@attr_area", AlignmentsSection.format(area)))
    if (desc.isNotBlank()) attributes.add(SceneAttribute("@attr_description", desc))
    attributes.add(SceneAttribute("@attr_points", n.toString()))
    val id = scene.addObject(SceneObject(BOUNDARY_KIND, name, layer, color, attributes))
    val lines = scene.lines(layer, color, flat = dimensions == 2, lineWeight = BOUNDARY_WEIGHT, dashed = LandXmlPalette.boundaryDashed(type))
    addPolyline(lines, n, close = true, z = Double.NaN, objectId = id)
  }

  private fun readBreakline(xml: XmlTokenizer, scene: LandXmlSceneBuilder, surface: String) {
    val name = xml.attrString(NAME).orEmpty()
    val type = xml.attrString(BRK_TYPE).orEmpty()
    val desc = xml.attrString(DESC).orEmpty()
    val dimensions = readList(xml)
    val n = list.size / 3
    if (n < 2) {
      short++
      return
    }
    breaklines++
    val color = LandXmlPalette.breakline(type)
    val layer = scene.layer(UiText.key(BREAKLINE_LAYER, surface), LandXmlPalette.BREAKLINE, LandXmlGroup.SOURCE_DATA)
    val attributes = if (type.isEmpty() && desc.isBlank()) emptyList() else ArrayList<SceneAttribute>(2).apply {
      if (type.isNotEmpty()) add(SceneAttribute("@attr_type", type))
      if (desc.isNotBlank()) add(SceneAttribute("@attr_description", desc))
    }
    val id = scene.addObject(SceneObject(BREAKLINE_KIND, name, layer, color, attributes))
    addPolyline(scene.lines(layer, color, flat = dimensions == 2, dashed = true), n, close = false, z = Double.NaN, objectId = id)
  }

  private fun readContour(xml: XmlTokenizer, scene: LandXmlSceneBuilder, surface: String) {
    val elev = xml.attrDouble(ELEV)
    readList(xml)
    val n = list.size / 3
    if (elev.isNaN()) {
      noElev++
      return
    }
    if (n < 2) {
      short++
      return
    }
    contours++
    val layer = scene.layer(UiText.key(CONTOUR_LAYER, surface), LandXmlPalette.CONTOUR, LandXmlGroup.SOURCE_DATA)
    // горизонталей тысячи: имя — отметка, атрибутов нет
    val id = scene.addObject(SceneObject(CONTOUR_KIND, AlignmentsSection.format(elev), layer, LandXmlPalette.CONTOUR))
    addPolyline(scene.lines(layer, LandXmlPalette.CONTOUR, flat = false), n, close = false, z = elev, objectId = id)
  }

  /** Список точек элемента (`PntList2D` или `PntList3D`, первый из них) в [list]; возвращает его размерность, 0 — списка нет. */
  private fun readList(xml: XmlTokenizer): Int {
    list.clear()
    var dimensions = 0
    val depth = xml.depth
    while (xml.nextChild(depth)) {
      val id = xml.nameId
      if (dimensions == 0 && (id == PNT_LIST_2D || id == PNT_LIST_3D)) {
        dimensions = if (id == PNT_LIST_2D) 2 else 3
        val strings = xml.stringsCreated
        LandXmlCoord.readList(xml, dimensions, list)
        hotStrings += xml.stringsCreated - strings
      } else {
        xml.skipElement()
      }
    }
    return dimensions
  }

  /**
   * Ломаная из [n] точек [list] отрезками в [lines]; [z] не NaN — отметка всех точек (горизонталь);
   * [close] — замкнуть, если последняя точка не совпадает с первой. Без второго массива.
   */
  private fun addPolyline(lines: LandXmlSceneBuilder.Lines, n: Int, close: Boolean, z: Double, objectId: Int) {
    val fixed = !z.isNaN()
    val x0 = list[0]
    val y0 = list[1]
    val z0 = if (fixed) z else list[2]
    var px = x0
    var py = y0
    var pz = z0
    for (k in 1 until n) {
      val x = list[k * 3]
      val y = list[k * 3 + 1]
      val zk = if (fixed) z else list[k * 3 + 2]
      lines.add(px, py, pz, x, y, zk, objectId)
      px = x
      py = y
      pz = zk
    }
    if (close && (px != x0 || py != y0 || pz != z0)) lines.add(px, py, pz, x0, y0, z0, objectId)
  }

  // группы PntList3D подряд в [data] (x, y, z, как у [LandXmlCoord.readList]); сверх лимита — только счёт, массив не растёт
  private fun readDataPoints(xml: XmlTokenizer) {
    val depth = xml.depth
    while (xml.nextChild(depth)) {
      val id = xml.nameId
      if (id != PNT_LIST_3D && id != PNT_LIST_2D) {
        xml.skipElement()
        continue
      }
      val strings = xml.stringsCreated
      while (xml.nextNumber()) {
        val north = xml.double()
        if (!xml.nextNumber()) break
        val east = xml.double()
        var z = 0.0
        if (id == PNT_LIST_3D) {
          if (!xml.nextNumber()) break
          z = xml.double()
        }
        if (++dataPoints <= MAX_DATA_POINTS) {
          data.add(east)
          data.add(north)
          data.add(z)
        }
      }
      hotStrings += xml.stringsCreated - strings
    }
  }

  private fun addDataPoints(scene: LandXmlSceneBuilder, surface: String) {
    if (dataPoints == 0) return
    if (dataPoints > MAX_DATA_POINTS) {
      log(UiText.key("log_data_points_limit", surface, dataPoints, MAX_DATA_POINTS))
      return
    }
    val layer = scene.layer(UiText.key(DATA_POINTS_LAYER, surface), LandXmlPalette.DATA_POINT, LandXmlGroup.SOURCE_DATA)
    for (k in 0 until data.size / 3) {
      scene.addPoint(PointMark(layer, LandXmlPalette.DATA_POINT, data[k * 3], data[k * 3 + 1], data[k * 3 + 2]))
    }
    data.clear()
  }

  // Chain: имена CgPoint через пробел; координаты — в finish(), точки могут стоять в файле после поверхности
  private fun readChain(xml: XmlTokenizer, scene: LandXmlSceneBuilder, surface: String) {
    val name = xml.attrString(NAME).orEmpty()
    xml.readText()
    val names = xml.textString().split(' ', '\t', '\n', '\r').filter { it.isNotEmpty() }
    chains.add(Chain(name, UiText.key(BREAKLINE_LAYER, surface), names))
    if (!deferred) {
      deferred = true
      scene.defer(LandXmlSceneBuilder.STAGE_REFERENCES) { resolveChains(scene) }
    }
  }

  private fun resolveChains(scene: LandXmlSceneBuilder) {
    var unresolved = 0
    var skipped = 0
    for (chain in chains) {
      list.clear()
      var flat = true
      for (name in chain.names) {
        if (!scene.pointNames.find(name, point)) {
          unresolved++
          continue
        }
        list.add(point[0])
        list.add(point[1])
        list.add(point[2])
        if (point[2] != 0.0) flat = false
      }
      val n = list.size / 3
      if (n < 2) {
        skipped++
        continue
      }
      val layer = scene.layer(chain.layer, LandXmlPalette.BREAKLINE, LandXmlGroup.SOURCE_DATA)
      val id = scene.addObject(SceneObject(CHAIN_KIND, chain.name, layer, LandXmlPalette.BREAKLINE,
        listOf(SceneAttribute("@attr_points", n.toString()))))
      // точки без отметки (z = 0) — ломаная плоская, как 2D-список
      addPolyline(scene.lines(layer, LandXmlPalette.BREAKLINE, flat, dashed = true), n, close = false, z = Double.NaN, objectId = id)
    }
    if (unresolved > 0) log(UiText.key("log_chains_unresolved", unresolved))
    if (skipped > 0) log(UiText.key("log_chains_short", skipped))
    chains.clear()
  }

  private class Chain(val name: String, val layer: String, val names: List<String>)

  companion object {
    const val BOUNDARY_KIND = "Boundary"
    // граница — как обводка поверхности без границы в файле (OUTLINE_WIDTH_DP): 0,5 мм → 1,5 px
    const val BOUNDARY_WEIGHT = 50
    const val BREAKLINE_KIND = "Breakline"
    const val CONTOUR_KIND = "Contour"
    const val CHAIN_KIND = "Chain"

    // ключи слоёв «<поверхность>: <что>», аргумент — имя поверхности
    const val BOUNDARY_LAYER = "layer_boundaries"
    const val BREAKLINE_LAYER = "layer_breaklines"
    const val CONTOUR_LAYER = "layer_contours"
    const val DATA_POINTS_LAYER = "layer_data_points"

    /** Точек данных больше — значки не создаются. */
    const val MAX_DATA_POINTS = 200_000

    /** Имена элементов: в [SurfacesSection] идут первыми, номера — места в этом списке. */
    val NAMES = arrayOf(
      "SourceData", "Boundaries", "Boundary", "Breaklines", "Breakline", "Contours", "Contour",
      "DataPoints", "Chain", "PntList2D", "PntList3D",
    )
    const val SOURCE_DATA = 0
    private const val BOUNDARIES = 1
    private const val BOUNDARY = 2
    private const val BREAKLINES = 3
    private const val BREAKLINE = 4
    private const val CONTOURS = 5
    private const val CONTOUR = 6
    private const val DATA_POINTS = 7
    private const val CHAIN = 8
    private const val PNT_LIST_2D = 9
    private const val PNT_LIST_3D = 10

    private val NAME = "name".encodeToByteArray()
    private val DESC = "desc".encodeToByteArray()
    private val BND_TYPE = "bndType".encodeToByteArray()
    private val EDGE_TRIM = "edgeTrim".encodeToByteArray()
    private val AREA = "area".encodeToByteArray()
    private val BRK_TYPE = "brkType".encodeToByteArray()
    private val ELEV = "elev".encodeToByteArray()
  }
}
