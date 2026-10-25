package dev.iskandern.kotin.landxml

import dev.iskandern.kotin.model.SceneGeometry
import dev.iskandern.kotin.model.SceneMesh
import dev.iskandern.kotin.ru
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.test.fail

/**
 * Сводка файла LandXML для сверки (KoTIN-75): её выдают эталон [LandXmlReference] и сцена читателя ([summaryOf]),
 * её же хранят золотые `expected/<имя>.json`. Каждая задача LandXML добавляет свой раздел (CgPoints, Alignments, …).
 * [fromScene] — сводка из сцены: чего в сцене нет (число точек файла, невидимые и битые грани), там null.
 */
class LandXmlSummary(
  val surfaces: List<SurfaceSummary>,
  val fromScene: Boolean = false,
  /** Ситуация и участки (KoTIN-65): по записи на `PlanFeature` и `Parcel` в порядке файла; в золотых файлах их нет. */
  val contours: List<ContourSummary> = emptyList(),
  /** Исходные данные поверхностей — по строке на поверхность и вид, по порядку (поверхность, вид). */
  val sourceData: List<SourceDataSummary> = emptyList(),
  /** Сети по порядку файла. */
  val pipeNetworks: List<PipeNetworkSummary> = emptyList(),
  /** Поперечники (KoTIN-69) по порядку файла; в золотых файлах их нет. */
  val crossSects: List<CrossSectSummary> = emptyList(),
  /** Профили трасс (KoTIN-66): по записи на трассу с `ProfAlign`, в порядке файла; в золотых файлах их нет. */
  val profiles: List<ProfileSummary> = emptyList(),
  /** Профили земли (KoTIN-87): по записи на трассу с `ProfSurf`, в порядке файла; в золотых файлах их нет. */
  val grounds: List<GroundSummary> = emptyList(),
)

/** Земля [profile] трассы [alignment]: куски — по `PntList2D`, в каждом пары «пикет, отметка» подряд, как в файле. */
class GroundSummary(val alignment: String, val profile: String, val runs: List<DoubleArray>)

const val CROSS_SECTS = "CrossSects"

/**
 * Поперечник трассы [alignment] на пикете [station]: ломаные земли [ground] и проекта [design] по порядку файла,
 * точки — (восток, север, отметка).
 */
class CrossSectSummary(
  val alignment: String,
  val station: Double,
  val ground: List<List<DoubleArray>>,
  val design: List<List<DoubleArray>>,
)

/**
 * Профиль [profile] трассы [alignment]: отметки [elevations] на пикетах [stations] — PVI, концы вертикальных кривых и
 * середины между ними (только на трассе).
 */
class ProfileSummary(val alignment: String, val profile: String, val stations: List<Double>, val elevations: List<Double>)

/** Разделы сводки: какие из них сверять ([assertSummaryMatches]). */
const val SURFACES = "Surfaces"
const val CONTOURS = "Contours"

/**
 * `PlanFeature` или `Parcel` ([kind]): [contours] — связных ломаных (новая начинается, где начало элемента не совпало
 * с концом предыдущего), [closed] — одна ломаная и конец сошёлся с началом (участок замыкается всегда),
 * [area] — площадь со знаком по Гауссу (+ против часовой) у замкнутого, иначе NaN; [locations] — точек `Location` с координатами.
 */
class ContourSummary(
  val kind: String,
  val name: String,
  val contours: Int,
  val closed: Boolean,
  val area: Double,
  val locations: Int,
)

const val SOURCE_DATA = "SourceData"
const val PIPE_NETWORKS = "PipeNetworks"

/** Сеть [name] типа [type]: [structs] — колодцев, [pipes] — трубы с найденными колодцами на концах, по порядку файла. */
class PipeNetworkSummary(val name: String, val type: String, val structs: Int, val pipes: List<PipeEnds>)

/** Отметки концов осевой линии трубы [name] — лотки по `Invert` или дно колодца. */
class PipeEnds(val name: String, val start: Double, val end: Double)

/**
 * Исходные данные поверхности [surface] вида [kind] (`Boundary`, `Breakline`, `Contour`, `DataPoints`): [lists] — списков
 * точек не меньше чем из двух точек (у `DataPoints` — групп `PntList`), [points] — точек в них, [segments] — отрезков
 * ломаных в плане (у границы — с замыкающим, если первая точка не повторена; соседние, продолжающие друг друга по прямой,
 * — один отрезок), [length] — их суммарная длина в плане. В плане — потому что 2D-линии сцена сажает на поверхность
 * (KoTIN-67): дробит по рёбрам треугольников и даёт им отметки. Из сцены число точек линий и групп точек данных
 * неизвестно — null.
 */
class SourceDataSummary(
  val surface: String,
  val kind: String,
  val lists: Int?,
  val points: Int?,
  val segments: Int,
  val length: Double,
)

/** Виды исходных данных в сводке — как `kind` объектов сцены. */
const val DATA_POINTS_KIND = "DataPoints"
private val SOURCE_KINDS = mapOf(
  "Boundary" to ": границы",
  "Breakline" to ": структурные линии",
  "Contour" to ": горизонтали",
)
private const val DATA_POINTS_SUFFIX = ": точки данных"

/** Габариты (восток, север, отметка) и суммы координат точек; точек нет — NaN. */
class Box {
  var count = 0
  var minE = Double.NaN; var minN = Double.NaN; var minZ = Double.NaN
  var maxE = Double.NaN; var maxN = Double.NaN; var maxZ = Double.NaN
  var sumE = 0.0; var sumN = 0.0; var sumZ = 0.0

  fun add(p: DoubleArray) = add(p[0], p[1], p[2])

  fun add(e: Double, n: Double, z: Double) {
    if (count++ == 0) {
      minE = e; minN = n; minZ = z; maxE = e; maxN = n; maxZ = z
    } else {
      minE = minOf(minE, e); minN = minOf(minN, n); minZ = minOf(minZ, z)
      maxE = maxOf(maxE, e); maxN = maxOf(maxN, n); maxZ = maxOf(maxZ, z)
    }
    sumE += e; sumN += n; sumZ += z
  }
}

/**
 * Поверхность: [points] — все P, [faces] — все F, [visibleFaces] — видимые (`i≠1`) с верными ссылками, [badFaces] — со ссылкой
 * на несуществующую точку, [usedPoints] — точки видимых граней; по ним же [box]. Площади — по видимым граням.
 * `file*` — контрольные числа Civil 3D из `Definition` (`area2DSurf`, `area3DSurf`, `elevMin`, `elevMax`), нет — null.
 */
class SurfaceSummary(
  val name: String,
  val points: Int?,
  val faces: Int?,
  val visibleFaces: Int,
  val badFaces: Int?,
  val usedPoints: Int,
  val box: Box,
  val area2D: Double,
  val area3D: Double,
  val fileArea2D: Double? = null,
  val fileArea3D: Double? = null,
  val fileElevMin: Double? = null,
  val fileElevMax: Double? = null,
)

/**
 * Та же сводка из сцены читателя: поверхность — сетки слоя с её именем, вершины сетки — точки видимых граней,
 * x — восток, y — север. Числа считаются здесь, а не берутся из полей сетки (`minX` и т. п.).
 */
fun summaryOf(geometry: SceneGeometry): LandXmlSummary {
  val byName = LinkedHashMap<String, MutableList<SceneMesh>>()
  // призмы колодцев и трубы объёмом — не поверхность
  for (mesh in geometry.meshes) if (!PipeNetworksSection.isNetworkLayer(mesh.layer)) byName.getOrPut(ru(mesh.layer)) { ArrayList() }.add(mesh)
  val surfaces = byName.map { (name, meshes) ->
    val box = Box()
    var triangles = 0
    var area2D = 0.0
    var area3D = 0.0
    for (mesh in meshes) {
      val c = mesh.coords
      for (v in 0 until mesh.vertexCount) box.add(c[v * 3], c[v * 3 + 1], c[v * 3 + 2])
      val t = mesh.triangles
      for (k in 0 until mesh.triangleCount) {
        val a = t[k * 3] * 3; val b = t[k * 3 + 1] * 3; val d = t[k * 3 + 2] * 3
        val ux = c[b] - c[a]; val uy = c[b + 1] - c[a + 1]; val uz = c[b + 2] - c[a + 2]
        val vx = c[d] - c[a]; val vy = c[d + 1] - c[a + 1]; val vz = c[d + 2] - c[a + 2]
        val cx = uy * vz - uz * vy
        val cy = uz * vx - ux * vz
        val cz = ux * vy - uy * vx
        area2D += abs(cz) / 2
        area3D += sqrt(cx * cx + cy * cy + cz * cz) / 2
      }
      triangles += mesh.triangleCount - quadHalves(mesh)
    }
    SurfaceSummary(name, null, null, triangles, null, box.count, box, area2D, area3D)
  }
  return LandXmlSummary(
    surfaces, fromScene = true, contours = contoursOf(geometry), sourceData = sourceDataOf(geometry),
    pipeNetworks = pipeNetworksOf(geometry), crossSects = crossSectsOf(geometry),
  )
}

/**
 * Поперечники из сцены: объекты `CrossSect` (трасса и пикет — из атрибутов), их отрезки на слоях земли и проекта;
 * новая ломаная — где начало отрезка не совпало с концом предыдущего.
 */
private fun crossSectsOf(geometry: SceneGeometry): List<CrossSectSummary> {
  val ground = HashMap<Int, MutableList<MutableList<DoubleArray>>>()
  val design = HashMap<Int, MutableList<MutableList<DoubleArray>>>()
  for (batch in geometry.lines) {
    val target = when (batch.layer) {
      CrossSects.GROUND_LAYER -> ground
      CrossSects.DESIGN_LAYER -> design
      else -> continue
    }
    val ids = batch.objectIds ?: continue
    val c = batch.coords
    for (k in 0 until batch.segmentCount) {
      val lists = target.getOrPut(ids[k]) { ArrayList() }
      val last = lists.lastOrNull()?.last()
      if (last == null || !samePoint(last[0], last[1], c[k * 6], c[k * 6 + 1]) || abs(last[2] - c[k * 6 + 2]) > COORD_ABS) {
        lists.add(arrayListOf(doubleArrayOf(c[k * 6], c[k * 6 + 1], c[k * 6 + 2])))
      }
      lists.last().add(doubleArrayOf(c[k * 6 + 3], c[k * 6 + 4], c[k * 6 + 5]))
    }
  }
  return geometry.objects.withIndex().filter { it.value.kind == CrossSects.KIND }.map { (id, obj) ->
    fun attr(name: String) = obj.attributes.first { it.name == name }.value
    CrossSectSummary(attr("@attr_alignment"), attr("@attr_station").toDouble(), ground[id].orEmpty(), design[id].orEmpty())
  }
}

/**
 * Сети из сцены: объекты `Struct` и `Pipe` по атрибуту «Сеть» (тип — «Тип сети»), отметки концов трубы — z первой
 * и последней вершины её отрезков.
 */
private fun pipeNetworksOf(geometry: SceneGeometry): List<PipeNetworkSummary> {
  fun attr(id: Int, name: String) = geometry.objects[id].attributes.firstOrNull { it.name == name }?.value.orEmpty()
  val ends = HashMap<Int, DoubleArray>()
  for (batch in geometry.lines) {
    val ids = batch.objectIds ?: continue
    val c = batch.coords
    for (i in 0 until c.size / 6) {
      val id = ids[i]
      if (geometry.objects.getOrNull(id)?.kind != PipeNetworksSection.PIPE_KIND) continue
      val e = ends.getOrPut(id) { doubleArrayOf(c[i * 6 + 2], 0.0) }
      e[1] = c[i * 6 + 5]
    }
  }
  val networks = LinkedHashMap<String, Triple<String, IntArray, MutableList<PipeEnds>>>()
  for ((id, obj) in geometry.objects.withIndex()) {
    if (obj.kind != PipeNetworksSection.STRUCT_KIND && obj.kind != PipeNetworksSection.PIPE_KIND) continue
    val n = networks.getOrPut(attr(id, "@attr_network")) { Triple(attr(id, "@attr_network_type"), IntArray(1), ArrayList()) }
    if (obj.kind == PipeNetworksSection.STRUCT_KIND) n.second[0]++
    else n.third.add(PipeEnds(obj.name, ends[id]!![0], ends[id]!![1]))
  }
  return networks.map { (name, n) -> PipeNetworkSummary(name, n.first, n.second[0], n.third) }
}

/**
 * Исходные данные из сцены: отрезки с объектом вида `Boundary`/`Breakline`/`Contour` на слое «<поверхность>: …»
 * (ломаная — объект), точки слоёв «<поверхность>: точки данных». Куски одного отрезка после посадки на поверхность
 * (могут лежать в разных пакетах: на рельефе и вне его) сливаются обратно — отрезки и длина в плане, как у эталона.
 */
private fun sourceDataOf(geometry: SceneGeometry): List<SourceDataSummary> {
  class Acc {
    val objects = HashSet<Int>()
    var segments = 0
    var length = 0.0
  }
  val acc = HashMap<Pair<String, String>, Acc>()
  // куски объекта: x1 y1 x2 y2
  val pieces = HashMap<Int, MutableList<DoubleArray>>()
  for (batch in geometry.lines) {
    val ids = batch.objectIds ?: continue
    val c = batch.coords
    for (i in 0 until c.size / 6) {
      val obj = geometry.objects.getOrNull(ids[i]) ?: continue
      val suffix = SOURCE_KINDS[obj.kind] ?: continue
      val layer = ru(batch.layer)
      if (!layer.endsWith(suffix)) continue
      val a = acc.getOrPut(layer.removeSuffix(suffix) to obj.kind) { Acc() }
      a.objects.add(ids[i])
      val k = i * 6
      val dx = c[k + 3] - c[k]; val dy = c[k + 4] - c[k + 1]
      a.length += sqrt(dx * dx + dy * dy)
      pieces.getOrPut(ids[i]) { ArrayList() }.add(doubleArrayOf(c[k], c[k + 1], c[k + 3], c[k + 4]))
    }
  }
  for (a in acc.values) for (id in a.objects) a.segments += planSegments(pieces.getValue(id))
  val result = acc.map { (key, a) -> SourceDataSummary(key.first, key.second, a.objects.size, null, a.segments, a.length) }
    .toMutableList()
  geometry.points.map { ru(it.layer) }.filter { it.endsWith(DATA_POINTS_SUFFIX) }.groupingBy { it.removeSuffix(DATA_POINTS_SUFFIX) }
    .eachCount().forEach { (surface, n) -> result.add(SourceDataSummary(surface, DATA_POINTS_KIND, null, n, 0, 0.0)) }
  return result.sortedWith(compareBy({ it.surface }, { it.kind }))
}

/**
 * Отрезков в плане из кусков x1 y1 x2 y2 одного объекта в любом порядке: куски минус стыки, где следующий кусок
 * начинается в конце предыдущего и идёт по той же прямой в ту же сторону; куски-точки (посадка на вершине TIN) не
 * считаются. Ищется по сетке ячеек [COORD_ABS].
 */
fun planSegments(all: List<DoubleArray>): Int {
  val pieces = all.filter { !samePoint(it[0], it[1], it[2], it[3]) }
  fun cell(v: Double) = floor(v / COORD_ABS).toLong()
  val starts = HashMap<Pair<Long, Long>, MutableList<DoubleArray>>()
  for (p in pieces) starts.getOrPut(cell(p[0]) to cell(p[1])) { ArrayList() }.add(p)
  var joints = 0
  for (p in pieces) {
    val cx = cell(p[2]); val cy = cell(p[3])
    var found = false
    for (i in -1L..1L) for (j in -1L..1L) {
      if (found) break
      found = starts[cx + i to cy + j].orEmpty().any { q ->
        q !== p && samePoint(p[2], p[3], q[0], q[1]) && continuesInPlan(p, q)
      }
    }
    if (found) joints++
  }
  return pieces.size - joints
}

/** Отрезки [a] и [b] (x1 y1 x2 y2) в плане на одной прямой и в одну сторону. */
fun continuesInPlan(a: DoubleArray, b: DoubleArray): Boolean {
  val ax = a[2] - a[0]; val ay = a[3] - a[1]
  val bx = b[2] - b[0]; val by = b[3] - b[1]
  val la = sqrt(ax * ax + ay * ay); val lb = sqrt(bx * bx + by * by)
  if (la == 0.0 || lb == 0.0) return false
  return abs(ax * by - ay * bx) <= COLLINEAR_REL * la * lb && ax * bx + ay * by > 0
}

/**
 * Контуры из сцены: отрезки объекта `PlanFeature`/`Parcel` по порядку в пакетах (x — восток, y — север), точки — значки
 * с номером объекта. Дуги в сцене — ломаные, поэтому площадь чуть меньше точной ([CONTOUR_AREA_REL]).
 */
private fun contoursOf(geometry: SceneGeometry): List<ContourSummary> {
  val kinds = setOf("PlanFeature", "Parcel")
  val segments = HashMap<Int, MutableList<DoubleArray>>()
  for (batch in geometry.lines) {
    val ids = batch.objectIds ?: continue
    val c = batch.coords
    for (k in 0 until batch.segmentCount) {
      if (geometry.objects.getOrNull(ids[k])?.kind !in kinds) continue
      segments.getOrPut(ids[k]) { ArrayList() }.add(doubleArrayOf(c[k * 6], c[k * 6 + 1], c[k * 6 + 3], c[k * 6 + 4]))
    }
  }
  val locations = geometry.points.groupingBy { it.objectId }.eachCount()
  return geometry.objects.withIndex().filter { it.value.kind in kinds }.map { (id, obj) ->
    val list = segments[id].orEmpty()
    var contours = 0
    var area = 0.0
    for ((k, s) in list.withIndex()) {
      if (k == 0 || !samePoint(list[k - 1][2], list[k - 1][3], s[0], s[1])) contours++
      area += (s[0] * s[3] - s[2] * s[1]) / 2
    }
    val closed = contours == 1 && samePoint(list.last()[2], list.last()[3], list[0][0], list[0][1])
    ContourSummary(obj.kind, obj.name, contours, closed, if (closed) area else Double.NaN, locations[id] ?: 0)
  }
}

private fun samePoint(x1: Double, y1: Double, x2: Double, y2: Double) = abs(x1 - x2) + abs(y1 - y2) <= COORD_ABS

/**
 * Сколько треугольников — вторые половины четырёхугольных граней (ячеек grid): два соседних треугольника, у каждого
 * скрыто ровно одно ребро ([SceneMesh.edgeMask]), и это одно и то же ребро. В эталоне такая грань — одна.
 */
private fun quadHalves(mesh: SceneMesh): Int {
  val mask = mesh.edgeMask ?: return 0
  val t = mesh.triangles
  fun hidden(k: Int): Long {
    val bits = mask[k].toInt()
    val edge = when (bits) {
      6 -> 0 // скрыто a-b
      5 -> 1 // b-c
      3 -> 2 // c-a
      else -> return -1
    }
    val p = t[k * 3 + edge].toLong()
    val q = t[k * 3 + (edge + 1) % 3].toLong()
    return minOf(p, q) shl 32 or maxOf(p, q)
  }
  var halves = 0
  var k = 0
  while (k + 1 < mesh.triangleCount) {
    val e = hidden(k)
    if (e >= 0 && e == hidden(k + 1)) {
      halves++
      k += 2
    } else {
      k++
    }
  }
  return halves
}

// допуски сверки
private const val AREA_REL = 1e-6
private const val COORD_ABS = 1e-6
// суммы десятков тысяч координат порядка 1e6 зависят от порядка сложения в последних знаках
private const val SUM_REL = 1e-12
// площадь контура с дугами: в сцене дуга — ломаная с допуском размер/10000, в эталоне — точная
const val CONTOUR_AREA_REL = 1e-2
private const val LENGTH_REL = 1e-9
// стык кусков «по прямой»: синус угла между ними
private const val COLLINEAR_REL = 1e-9

private fun closeRel(a: Double, b: Double, rel: Double) =
  a == b || (a.isNaN() && b.isNaN()) || abs(a - b) <= rel * max(abs(a), abs(b))

private fun closeAbs(a: Double, b: Double, eps: Double) = a == b || (a.isNaN() && b.isNaN()) || abs(a - b) <= eps

/**
 * Расхождения [actual] с [expected] по разделам [sections]; пусто — совпали. У сводки из сцены поверхности без
 * видимых граней не ищутся (в сцену они не попадают), числа, которых в сцене нет (null), не сравниваются.
 */
fun summaryMismatches(expected: LandXmlSummary, actual: LandXmlSummary, sections: Set<String> = setOf(SURFACES)): List<String> {
  val result = ArrayList<String>()
  if (SURFACES in sections) {
    fun shown(s: LandXmlSummary) = if (expected.fromScene || actual.fromScene) s.surfaces.filter { it.visibleFaces > 0 } else s.surfaces
    val want = shown(expected)
    val got = shown(actual)
    if (want.map { it.name } != got.map { it.name }) {
      result.add("поверхности: ожидались ${want.map { it.name }}, есть ${got.map { it.name }}")
    } else {
      for ((e, a) in want.zip(got)) result.addAll(surfaceMismatches(e, a).map { "«${e.name}»: $it" })
    }
  }
  if (CONTOURS in sections) {
    // порядок разделов в сцене — порядок их чтения, поэтому сверяем по виду, внутри вида — по порядку файла
    for (kind in listOf("PlanFeature", "Parcel")) {
      val want = expected.contours.filter { it.kind == kind }
      val got = actual.contours.filter { it.kind == kind }
      if (want.map { it.name } != got.map { it.name }) {
        result.add("$kind: ожидались ${want.map { it.name }}, есть ${got.map { it.name }}")
        continue
      }
      for ((e, a) in want.zip(got)) {
        val what = "$kind «${e.name}»"
        if (e.contours != a.contours) result.add("$what: ломаных ${e.contours} ≠ ${a.contours}")
        if (e.closed != a.closed) result.add("$what: замкнут ${e.closed} ≠ ${a.closed}")
        if (!closeRel(e.area, a.area, CONTOUR_AREA_REL)) result.add("$what: площадь ${e.area} ≠ ${a.area}")
        if (e.locations != a.locations) result.add("$what: точек ${e.locations} ≠ ${a.locations}")
      }
    }
  }
  if (SOURCE_DATA in sections) {
    fun keys(s: LandXmlSummary) = s.sourceData.map { "${it.surface} / ${it.kind}" }
    if (keys(expected) != keys(actual)) {
      result.add("исходные данные: ожидались ${keys(expected)}, есть ${keys(actual)}")
    } else {
      for ((e, a) in expected.sourceData.zip(actual.sourceData)) {
        val what = "«${e.surface}» ${e.kind}:"
        if (e.lists != null && a.lists != null && e.lists != a.lists) result.add("$what списков ${e.lists} ≠ ${a.lists}")
        if (e.points != null && a.points != null && e.points != a.points) result.add("$what точек ${e.points} ≠ ${a.points}")
        if (e.segments != a.segments) result.add("$what отрезков ${e.segments} ≠ ${a.segments}")
        if (!closeRel(e.length, a.length, LENGTH_REL)) result.add("$what длина ${e.length} ≠ ${a.length}")
      }
    }
  }
  if (PIPE_NETWORKS in sections) {
    if (expected.pipeNetworks.map { it.name } != actual.pipeNetworks.map { it.name }) {
      result.add("сети: ожидались ${expected.pipeNetworks.map { it.name }}, есть ${actual.pipeNetworks.map { it.name }}")
    } else {
      for ((e, a) in expected.pipeNetworks.zip(actual.pipeNetworks)) {
        val what = "сеть «${e.name}»:"
        if (e.type != a.type) result.add("$what тип ${e.type} ≠ ${a.type}")
        if (e.structs != a.structs) result.add("$what колодцев ${e.structs} ≠ ${a.structs}")
        if (e.pipes.map { it.name } != a.pipes.map { it.name }) {
          result.add("$what трубы ${e.pipes.map { it.name }} ≠ ${a.pipes.map { it.name }}")
          continue
        }
        for ((p, q) in e.pipes.zip(a.pipes)) {
          if (!closeAbs(p.start, q.start, COORD_ABS)) result.add("$what труба «${p.name}» лоток в начале ${p.start} ≠ ${q.start}")
          if (!closeAbs(p.end, q.end, COORD_ABS)) result.add("$what труба «${p.name}» лоток в конце ${p.end} ≠ ${q.end}")
        }
      }
    }
  }
  if (CROSS_SECTS in sections) {
    // пикет в сцене — атрибут с тремя знаками
    fun keys(s: LandXmlSummary) = s.crossSects.map { "${it.alignment} ПК ${AlignmentsSection.format(it.station)}" }
    if (keys(expected) != keys(actual)) {
      result.add("поперечники: ожидались ${keys(expected)}, есть ${keys(actual)}")
    } else {
      for ((k, e) in expected.crossSects.withIndex()) {
        val a = actual.crossSects[k]
        val what = "поперечник ${keys(expected)[k]}:"
        for ((kind, want, got) in listOf(Triple("земля", e.ground, a.ground), Triple("проект", e.design, a.design))) {
          if (want.map { it.size } != got.map { it.size }) {
            result.add("$what $kind — точек в ломаных ${want.map { it.size }} ≠ ${got.map { it.size }}")
            continue
          }
          for ((p, q) in want.flatten().zip(got.flatten())) {
            if ((0 until 3).any { !closeAbs(p[it], q[it], COORD_ABS) }) result.add("$what $kind — точка ${p.toList()} ≠ ${q.toList()}")
          }
        }
      }
    }
  }
  return result
}

private fun surfaceMismatches(e: SurfaceSummary, a: SurfaceSummary): List<String> {
  val result = ArrayList<String>()
  fun int(what: String, x: Int?, y: Int?) {
    if (x != null && y != null && x != y) result.add("$what $x ≠ $y")
  }
  fun rel(what: String, x: Double?, y: Double?, eps: Double) {
    if (x != null && y != null && !closeRel(x, y, eps)) result.add("$what $x ≠ $y")
  }
  fun near(what: String, x: Double, y: Double) {
    if (!closeAbs(x, y, COORD_ABS)) result.add("$what $x ≠ $y")
  }
  int("точек", e.points, a.points)
  int("граней", e.faces, a.faces)
  int("видимых граней", e.visibleFaces, a.visibleFaces)
  int("граней с неверной ссылкой", e.badFaces, a.badFaces)
  int("точек видимых граней", e.usedPoints, a.usedPoints)
  near("мин. восток", e.box.minE, a.box.minE); near("мин. север", e.box.minN, a.box.minN); near("мин. отметка", e.box.minZ, a.box.minZ)
  near("макс. восток", e.box.maxE, a.box.maxE); near("макс. север", e.box.maxN, a.box.maxN); near("макс. отметка", e.box.maxZ, a.box.maxZ)
  rel("сумма восток", e.box.sumE, a.box.sumE, SUM_REL)
  rel("сумма север", e.box.sumN, a.box.sumN, SUM_REL)
  rel("сумма отметок", e.box.sumZ, a.box.sumZ, SUM_REL)
  rel("площадь 2D", e.area2D, a.area2D, AREA_REL)
  rel("площадь 3D", e.area3D, a.area3D, AREA_REL)
  rel("area2DSurf", e.fileArea2D, a.fileArea2D, 0.0)
  rel("area3DSurf", e.fileArea3D, a.fileArea3D, 0.0)
  rel("elevMin", e.fileElevMin, a.fileElevMin, 0.0)
  rel("elevMax", e.fileElevMax, a.fileElevMax, 0.0)
  return result
}

/** Сверка сводок: расхождение — падение со списком всех различий. */
fun assertSummaryMatches(expected: LandXmlSummary, actual: LandXmlSummary, sections: Set<String> = setOf(SURFACES), what: String = "") {
  val mismatches = summaryMismatches(expected, actual, sections)
  if (mismatches.isNotEmpty()) fail("сводки расходятся $what:\n  " + mismatches.joinToString("\n  "))
}

/** Расхождения поверхности с контрольными числами Civil 3D из её же `Definition`; чисел нет — пусто. */
fun controlMismatches(s: SurfaceSummary): List<String> {
  val result = ArrayList<String>()
  fun rel(what: String, file: Double?, ours: Double) {
    if (file != null && !closeRel(file, ours, AREA_REL)) result.add("«${s.name}»: $what в файле $file, по граням $ours")
  }
  fun near(what: String, file: Double?, ours: Double) {
    if (file != null && !closeAbs(file, ours, COORD_ABS)) result.add("«${s.name}»: $what в файле $file, по граням $ours")
  }
  rel("area2DSurf", s.fileArea2D, s.area2D)
  rel("area3DSurf", s.fileArea3D, s.area3D)
  near("elevMin", s.fileElevMin, s.box.minZ)
  near("elevMax", s.fileElevMax, s.box.maxZ)
  return result
}

/** Сводка в JSON золотого файла (пишется вручную, без библиотек). */
fun LandXmlSummary.toJson(): String = buildString {
  append("{\n  \"surfaces\": [")
  surfaces.forEachIndexed { k, s ->
    append(if (k == 0) "\n" else ",\n")
    val fields = listOf(
      "name" to jsonString(s.name),
      "points" to s.points.toString(),
      "faces" to s.faces.toString(),
      "visibleFaces" to s.visibleFaces.toString(),
      "badFaces" to s.badFaces.toString(),
      "usedPoints" to s.usedPoints.toString(),
      "minE" to jsonNumber(s.box.minE), "minN" to jsonNumber(s.box.minN), "minZ" to jsonNumber(s.box.minZ),
      "maxE" to jsonNumber(s.box.maxE), "maxN" to jsonNumber(s.box.maxN), "maxZ" to jsonNumber(s.box.maxZ),
      "sumE" to jsonNumber(s.box.sumE), "sumN" to jsonNumber(s.box.sumN), "sumZ" to jsonNumber(s.box.sumZ),
      "area2D" to jsonNumber(s.area2D), "area3D" to jsonNumber(s.area3D),
      "area2DSurf" to jsonNumber(s.fileArea2D), "area3DSurf" to jsonNumber(s.fileArea3D),
      "elevMin" to jsonNumber(s.fileElevMin), "elevMax" to jsonNumber(s.fileElevMax),
    )
    append("    {\n")
    append(fields.joinToString(",\n") { (n, v) -> "      \"$n\": $v" })
    append("\n    }")
  }
  append(if (surfaces.isEmpty()) "]" else "\n  ]")
  // раздел исходных данных — только если они есть: старые золотые файлы без него остаются как были
  if (sourceData.isNotEmpty()) {
    append(",\n  \"sourceData\": [")
    sourceData.forEachIndexed { k, s ->
      append(if (k == 0) "\n" else ",\n")
      append("    {\"surface\": ${jsonString(s.surface)}, \"kind\": ${jsonString(s.kind)}, \"lists\": ${s.lists}, " +
        "\"points\": ${s.points}, \"segments\": ${s.segments}, \"length\": ${jsonNumber(s.length)}}")
    }
    append("\n  ]")
  }
  if (pipeNetworks.isNotEmpty()) {
    append(",\n  \"pipeNetworks\": [")
    pipeNetworks.forEachIndexed { k, n ->
      append(if (k == 0) "\n" else ",\n")
      append("    {\"name\": ${jsonString(n.name)}, \"type\": ${jsonString(n.type)}, \"structs\": ${n.structs}, \"pipes\": [")
      append(n.pipes.joinToString(", ") { "{\"name\": ${jsonString(it.name)}, \"start\": ${jsonNumber(it.start)}, \"end\": ${jsonNumber(it.end)}}" })
      append("]}")
    }
    append("\n  ]")
  }
  append("\n}\n")
}

private fun jsonNumber(x: Double?): String = if (x == null || x.isNaN()) "null" else x.toString()

private fun jsonString(s: String): String = buildString {
  append('"')
  for (ch in s) when {
    ch == '"' -> append("\\\"")
    ch == '\\' -> append("\\\\")
    ch < ' ' -> append("\\u%04x".format(ch.code))
    else -> append(ch)
  }
  append('"')
}

/** Сводка из JSON золотого файла. */
fun parseSummaryJson(text: String): LandXmlSummary {
  @Suppress("UNCHECKED_CAST")
  val root = JsonParser(text).parse() as Map<String, Any?>
  @Suppress("UNCHECKED_CAST")
  val surfaces = (root["surfaces"] as List<Map<String, Any?>>).map { m ->
    fun d(n: String) = m[n] as Double?
    fun i(n: String) = d(n)?.toInt()
    val box = Box().apply {
      count = i("usedPoints")!!
      minE = d("minE") ?: Double.NaN; minN = d("minN") ?: Double.NaN; minZ = d("minZ") ?: Double.NaN
      maxE = d("maxE") ?: Double.NaN; maxN = d("maxN") ?: Double.NaN; maxZ = d("maxZ") ?: Double.NaN
      sumE = d("sumE")!!; sumN = d("sumN")!!; sumZ = d("sumZ")!!
    }
    SurfaceSummary(
      m["name"] as String, i("points"), i("faces"), i("visibleFaces")!!, i("badFaces"), i("usedPoints")!!, box,
      d("area2D")!!, d("area3D")!!, d("area2DSurf"), d("area3DSurf"), d("elevMin"), d("elevMax"),
    )
  }
  @Suppress("UNCHECKED_CAST")
  val sourceData = (root["sourceData"] as List<Map<String, Any?>>?).orEmpty().map { m ->
    SourceDataSummary(
      m["surface"] as String, m["kind"] as String, (m["lists"] as Double?)?.toInt(), (m["points"] as Double?)?.toInt(),
      (m["segments"] as Double).toInt(), m["length"] as Double,
    )
  }
  @Suppress("UNCHECKED_CAST")
  val pipeNetworks = (root["pipeNetworks"] as List<Map<String, Any?>>?).orEmpty().map { m ->
    val pipes = (m["pipes"] as List<Map<String, Any?>>).map { PipeEnds(it["name"] as String, it["start"] as Double, it["end"] as Double) }
    PipeNetworkSummary(m["name"] as String, m["type"] as String, (m["structs"] as Double).toInt(), pipes)
  }
  return LandXmlSummary(surfaces, sourceData = sourceData, pipeNetworks = pipeNetworks)
}

/** Разбор JSON: объекты, массивы, строки, числа (Double), true/false, null — ровно то, что пишет [toJson]. */
private class JsonParser(private val s: String) {
  private var pos = 0

  fun parse(): Any? = value().also {
    space()
    require(pos == s.length) { "лишнее в конце JSON, позиция $pos" }
  }

  private fun space() {
    while (pos < s.length && s[pos].isWhitespace()) pos++
  }

  private fun expect(ch: Char) {
    space()
    require(pos < s.length && s[pos] == ch) { "ожидалось «$ch», позиция $pos" }
    pos++
  }

  private fun value(): Any? {
    space()
    require(pos < s.length) { "JSON оборван" }
    return when (s[pos]) {
      '{' -> {
        pos++
        val map = LinkedHashMap<String, Any?>()
        space()
        if (s[pos] == '}') pos++ else {
          do {
            space()
            val key = string()
            expect(':')
            map[key] = value()
            space()
          } while (s[pos++] == ',')
          require(s[pos - 1] == '}') { "ожидалось «}», позиция ${pos - 1}" }
        }
        map
      }
      '[' -> {
        pos++
        val list = ArrayList<Any?>()
        space()
        if (s[pos] == ']') pos++ else {
          do {
            list.add(value())
            space()
          } while (s[pos++] == ',')
          require(s[pos - 1] == ']') { "ожидалось «]», позиция ${pos - 1}" }
        }
        list
      }
      '"' -> string()
      else -> {
        val start = pos
        while (pos < s.length && s[pos] !in ",]} \t\r\n") pos++
        when (val word = s.substring(start, pos)) {
          "null" -> null
          "true" -> true
          "false" -> false
          else -> word.toDouble()
        }
      }
    }
  }

  private fun string(): String {
    require(s[pos] == '"') { "ожидалась строка, позиция $pos" }
    pos++
    val sb = StringBuilder()
    while (s[pos] != '"') {
      val ch = s[pos++]
      if (ch != '\\') {
        sb.append(ch)
        continue
      }
      when (val esc = s[pos++]) {
        'n' -> sb.append('\n')
        't' -> sb.append('\t')
        'r' -> sb.append('\r')
        'b' -> sb.append('\b')
        'f' -> sb.append('\u000C')
        'u' -> {
          sb.append(s.substring(pos, pos + 4).toInt(16).toChar())
          pos += 4
        }
        else -> sb.append(esc)
      }
    }
    pos++
    return sb.toString()
  }
}

/** Таблица сводок для владельца: по строке на поверхность, и расхождение с контрольными числами файла. */
fun summaryTable(rows: List<Pair<String, LandXmlSummary>>): String = buildString {
  append("%-44s %-24s %7s %7s %7s %5s %16s %16s  %s\n".format(
    "файл", "поверхность", "точек", "граней", "видим.", "битых", "площадь 2D", "площадь 3D", "контрольные числа"))
  for ((file, summary) in rows) {
    if (summary.surfaces.isEmpty()) append("%-44s %-24s\n".format(file, "—"))
    for (s in summary.surfaces) {
      val control = when {
        s.fileArea2D == null && s.fileArea3D == null && s.fileElevMin == null && s.fileElevMax == null -> "нет"
        controlMismatches(s).isEmpty() -> "совпали"
        else -> "РАСХОДЯТСЯ: " + controlMismatches(s).joinToString("; ")
      }
      append("%-44s %-24s %7d %7d %7d %5d %16.6f %16.6f  %s\n".format(
        file, s.name.take(24), s.points, s.faces, s.visibleFaces, s.badFaces, s.area2D, s.area3D, control))
    }
    for (d in summary.sourceData) {
      append("%-44s   «%s» %s: списков %s, точек %s, отрезков %d, длина %.3f\n".format(
        file, d.surface, d.kind, d.lists, d.points, d.segments, d.length))
    }
  }
}
