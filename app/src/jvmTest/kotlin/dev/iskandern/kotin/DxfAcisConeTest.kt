package dev.iskandern.kotin

import dev.iskandern.kotin.dxf.AcisCone
import dev.iskandern.kotin.dxf.DxfDocumentReader
import dev.iskandern.kotin.dxf.DxfFlattener
import dev.iskandern.kotin.io.asByteInput
import dev.iskandern.kotin.dxf.AcisSurface
import dev.iskandern.kotin.dxf.ParamFace
import dev.iskandern.kotin.model.SceneGeometry
import dev.iskandern.kotin.model.SceneMesh
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 3DSOLID: конические и цилиндрические грани (файл — `scripts/gen_test_dxf.py`, task94). */
class DxfAcisConeTest {

  private val cone: SceneGeometry by lazy { readGeometry(File(testDxfDir, "acis-cone.dxf")) }

  // треугольники сетки: 9 координат и биты видимых рёбер
  private fun SceneMesh.triangles(): List<Pair<DoubleArray, Int>> = (0 until triangleCount).map { t ->
    DoubleArray(9) { coords[triangles[t * 3 + it / 3] * 3 + it % 3] } to (edgeMask?.get(t)?.toInt() ?: 7)
  }

  private fun area(p: DoubleArray): Double {
    val ax = p[3] - p[0]; val ay = p[4] - p[1]; val az = p[5] - p[2]
    val bx = p[6] - p[0]; val by = p[7] - p[1]; val bz = p[8] - p[2]
    return hypot(hypot(ay * bz - az * by, az * bx - ax * bz), ax * by - ay * bx) / 2
  }

  private fun flatZ(p: DoubleArray) = abs(p[2] - p[5]) < 1e-9 && abs(p[2] - p[8]) < 1e-9

  // боковые (не горизонтальные) треугольники тела около оси (cx, cy)
  private fun side(mesh: SceneMesh, cx: Double, cy: Double, reach: Double) = mesh.triangles().filter { (p, _) ->
    !flatZ(p) && (0..2).all { hypot(p[it * 3] - cx, p[it * 3 + 1] - cy) < reach }
  }

  @Test
  fun `цилиндр - 2 плоские грани и боковая, вершины на поверхности, каркас только по торцам`() {
    val mesh = cone.meshes.single { it.layer == "Cylinders" }
    val wall = side(mesh, 0.0, 0.0, 5.5)
    assertTrue(wall.isNotEmpty())
    // вписанный многогранник чуть меньше цилиндра: 2π·5·10
    val s = wall.sumOf { area(it.first) }
    assertEquals(2 * PI * 5 * 10, s, 2 * PI * 5 * 10 * 0.01)
    assertTrue(s < 2 * PI * 5 * 10)
    for ((p, _) in wall) for (k in 0..2) assertEquals(5.0, hypot(p[k * 3], p[k * 3 + 1]), 1e-9)
    // торцы: два круга
    val caps = mesh.triangles().filter { (p, _) -> flatZ(p) && hypot(p[0], p[1]) < 5.5 }.sumOf { area(it.first) }
    assertEquals(2 * PI * 25, caps, 2 * PI * 25 * 0.01)
    // в каркас боковой грани идут только окружности торцов, без образующих и диагоналей
    var visible = 0
    for ((p, edges) in wall) for (e in 0..2) {
      if (edges and (1 shl e) == 0) continue
      val a = e * 3
      val b = (e + 1) % 3 * 3
      assertEquals(p[a + 2], p[b + 2], 1e-9, "видимое ребро не на торце")
      visible++
    }
    assertTrue(visible >= 32, "окружности торцов: $visible")
    assertEquals(0, cone.hiddenBodyFaces)
  }

  @Test
  fun `цилиндр вдоль x - вершины на расстоянии радиуса от оси`() {
    val mesh = cone.meshes.single { it.layer == "Cylinders" }
    val tris = mesh.triangles().filter { (p, _) -> (0..2).all { p[it * 3 + 1] < -10 } }
    var wall = 0.0
    for ((p, _) in tris) {
      val onWall = (0..2).all { abs(hypot(p[it * 3 + 1] + 15, p[it * 3 + 2] - 3) - 3) < 1e-9 }
      if (onWall && !(0..2).all { abs(p[it * 3] - p[0]) < 1e-9 }) wall += area(p)
    }
    assertEquals(2 * PI * 3 * 12, wall, 2 * PI * 3 * 12 * 0.01)
  }

  @Test
  fun `конус и усечённый конус - вершины на образующей, площадь боковой поверхности`() {
    val mesh = cone.meshes.single { it.layer == "Cones" }
    // конус R5 высотой 10 в (20, 0): r = 5 − z/2, вершина в z = 10
    val full = side(mesh, 20.0, 0.0, 5.5)
    for ((p, _) in full) for (k in 0..2) assertEquals(5 - p[k * 3 + 2] / 2, hypot(p[k * 3] - 20, p[k * 3 + 1]), 1e-9)
    assertEquals(10.0, full.maxOf { (p, _) -> maxOf(p[2], p[5], p[8]) }, 1e-9)
    val lateral = PI * 5 * sqrt(25.0 + 100.0)
    assertEquals(lateral, full.sumOf { area(it.first) }, lateral * 0.01)
    // усечённый R4 → R2 высотой 8 в (35, 0): r = 4 − z/4
    val cut = side(mesh, 35.0, 0.0, 4.5)
    for ((p, _) in cut) for (k in 0..2) assertEquals(4 - p[k * 3 + 2] / 4, hypot(p[k * 3] - 35, p[k * 3 + 1]), 1e-9)
    val frustum = PI * (4 + 2) * sqrt(4.0 + 64.0)
    assertEquals(frustum, cut.sumOf { area(it.first) }, frustum * 0.01)
    // основание конуса, торцы усечённого
    val caps = mesh.triangles().filter { (p, _) -> flatZ(p) }.sumOf { area(it.first) }
    assertEquals(PI * (25 + 16 + 4), caps, PI * 45 * 0.01)
  }

  @Test
  fun `труба - наружная и внутренняя стенки, кольца торцов`() {
    val mesh = cone.meshes.single { it.layer == "Tubes" }
    val walls = side(mesh, 20.0, -20.0, 5.5)
    val outer = walls.filter { (p, _) -> hypot(p[0] - 20, p[1] + 20) > 4 }.sumOf { area(it.first) }
    val inner = walls.filter { (p, _) -> hypot(p[0] - 20, p[1] + 20) < 4 }.sumOf { area(it.first) }
    assertEquals(2 * PI * 5 * 8, outer, 2 * PI * 5 * 8 * 0.01)
    assertEquals(2 * PI * 3 * 8, inner, 2 * PI * 3 * 8 * 0.01)
    val rings = mesh.triangles().filter { (p, _) -> flatZ(p) }.sumOf { area(it.first) }
    assertEquals(2 * PI * (25 - 9), rings, 2 * PI * 16 * 0.01)
  }

  @Test
  fun `плита с отверстием - стенка отверстия построена`() {
    val sat = readGeometry(File(testDxfDir, "acis-sat.dxf"))
    assertEquals(0, sat.hiddenBodyFaces)
    val plates = sat.meshes.single { it.layer == "Plates" }
    // плита в модели: отверстие R4 в (35, 10), высота 4
    val hole = plates.triangles().filter { (p, _) -> (0..2).all { hypot(p[it * 3] - 35, p[it * 3 + 1] - 10) < 4.5 && p[it * 3 + 2] <= 4.0 } }
      .filter { (p, _) -> !flatZ(p) }
    for ((p, _) in hole) for (k in 0..2) assertEquals(4.0, hypot(p[k * 3] - 35, p[k * 3 + 1] - 10), 1e-9)
    assertEquals(2 * PI * 4 * 4, hole.sumOf { area(it.first) }, 2 * PI * 16 * 0.01)
  }

  @Test
  fun `лимит отрезков - проверяется между гранями тела`() {
    val doc = File(testDxfDir, "acis-cone.dxf").inputStream().use { DxfDocumentReader(it.asByteInput()).read() }
    // первое тело — цилиндр: плоский торец, затем боковая грань на сотню треугольников
    val g = DxfFlattener(doc, maxSegments = 10).flatten()
    assertTrue(g.truncated)
    assertTrue(g.meshes.sumOf { it.triangleCount } < 100, "${g.meshes.sumOf { it.triangleCount }}")
  }

  /** Треугольники грани в координатах поверхности. */
  private fun faces(loops: List<DoubleArray>, surface: AcisSurface): List<DoubleArray> {
    val out = ArrayList<DoubleArray>()
    ParamFace.triangulate(loops, surface, { 64 }) { c, _ -> out.add(c.copyOf()) }
    return out
  }

  private fun circle(cx: Double, cy: Double, z: Double, r: Double, n: Int, ccw: Boolean) = DoubleArray(n * 3) {
    val a = 2 * PI * (it / 3) / n * (if (ccw) 1 else -1)
    when (it % 3) { 0 -> cx + r * cos(a); 1 -> cy + r * sin(a); else -> z }
  }

  @Test
  fun `цилиндр с прямоугольным окном - окно не закрыто`() {
    val surface = AcisCone(doubleArrayOf(0.0, 0.0, 0.0), doubleArrayOf(0.0, 0.0, 1.0), doubleArrayOf(2.0, 0.0, 0.0), 1.0, 0.0, 1.0, false)
    // окно: угол −0.5…0.5 (через шов u = 0), высота 2…4; по дуге 8 точек
    val window = ArrayList<Double>()
    fun at(a: Double, z: Double) { window.add(2 * cos(a)); window.add(2 * sin(a)); window.add(z) }
    for (i in 0..8) at(-0.5 + i / 8.0, 2.0)
    for (i in 0..8) at(0.5 - i / 8.0, 4.0)
    val tris = faces(listOf(circle(0.0, 0.0, 0.0, 2.0, 64, true), circle(0.0, 0.0, 6.0, 2.0, 64, false), window.toDoubleArray()), surface)
    val total = tris.sumOf { area(it) }
    // боковая поверхность минус окно (площадь окна на цилиндре — 2 · 1 · 2)
    assertEquals(2 * PI * 2 * 6 - 4, total, 0.1)
    for (p in tris) {
      val a = kotlin.math.atan2((p[1] + p[4] + p[7]) / 3, (p[0] + p[3] + p[6]) / 3)
      val z = (p[2] + p[5] + p[8]) / 3
      assertTrue(!(abs(a) < 0.45 && z > 2.05 && z < 3.95), "треугольник в окне: $a, $z")
    }
  }

  @Test
  fun `конус с петлёй-точкой в вершине - боковая поверхность до вершины`() {
    // r = 3 − z, вершина в z = 3; петля вершины — одна точка
    val s = 1 / sqrt(2.0)
    val surface = AcisCone(doubleArrayOf(0.0, 0.0, 0.0), doubleArrayOf(0.0, 0.0, 1.0), doubleArrayOf(3.0, 0.0, 0.0), 1.0, -s, s, false)
    val tris = faces(listOf(circle(0.0, 0.0, 0.0, 3.0, 64, true), doubleArrayOf(0.0, 0.0, 3.0)), surface)
    val lateral = PI * 3 * sqrt(18.0)
    assertEquals(lateral, tris.sumOf { area(it) }, lateral * 0.01)
    assertEquals(3.0, tris.maxOf { maxOf(it[2], it[5], it[8]) }, 1e-9)
  }
}
