package dev.iskandern.kotin

import dev.iskandern.kotin.dxf.AcisSat
import dev.iskandern.kotin.model.SceneGeometry
import dev.iskandern.kotin.model.SceneMesh
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** 3DSOLID: торы и сферы (файл — `scripts/gen_test_dxf.py`, task95), рёбра-сплайны `intcurve-curve`. */
class DxfAcisTorusTest {

  private val geometry: SceneGeometry by lazy { readGeometry(File(testDxfDir, "acis-torus-sphere.dxf")) }

  private fun SceneMesh.triangles(): List<DoubleArray> = (0 until triangleCount).map { t ->
    DoubleArray(9) { coords[triangles[t * 3 + it / 3] * 3 + it % 3] }
  }

  private fun area(p: DoubleArray): Double {
    val ax = p[3] - p[0]; val ay = p[4] - p[1]; val az = p[5] - p[2]
    val bx = p[6] - p[0]; val by = p[7] - p[1]; val bz = p[8] - p[2]
    return hypot(hypot(ay * bz - az * by, az * bx - ax * bz), ax * by - ay * bx) / 2
  }

  // треугольники, у которых все вершины удовлетворяют [inside]
  private fun List<DoubleArray>.where(inside: (Double, Double, Double) -> Boolean) =
    filter { p -> (0..2).all { inside(p[it * 3], p[it * 3 + 1], p[it * 3 + 2]) } }

  private fun dist(x: Double, y: Double, z: Double, cx: Double, cy: Double, cz: Double) = sqrt((x - cx) * (x - cx) + (y - cy) * (y - cy) + (z - cz) * (z - cz))

  // расстояние от точки трубки тора (ось z) до окружности её центров
  private fun tube(x: Double, y: Double, z: Double, cx: Double, cy: Double, cz: Double, big: Double) = hypot(hypot(x - cx, y - cy) - big, z - cz)

  @Test
  fun `все грани построены`() {
    assertEquals(0, geometry.hiddenBodyFaces)
    assertEquals(0, geometry.unreadBodies)
  }

  @Test
  fun `сфера целиком - вершины на сфере, площадь, без каркаса`() {
    val mesh = geometry.meshes.single { it.layer == "Spheres" }
    val ball = mesh.triangles().where { x, y, z -> dist(x, y, z, 0.0, 0.0, 4.0) < 4.5 }
    for (p in ball) for (k in 0..2) assertEquals(4.0, dist(p[k * 3], p[k * 3 + 1], p[k * 3 + 2], 0.0, 0.0, 4.0), 1e-9)
    val s = ball.sumOf { area(it) }
    assertEquals(4 * PI * 16, s, 4 * PI * 16 * 0.01)
    assertTrue(s < 4 * PI * 16)
    // у грани без петель нет рёбер тела — в каркас ничего не идёт
    val mask = mesh.edgeMask!!
    val ballIds = mesh.triangles().withIndex().filter { (_, p) -> (0..2).all { dist(p[it * 3], p[it * 3 + 1], p[it * 3 + 2], 0.0, 0.0, 4.0) < 4.5 } }
    assertTrue(ballIds.all { mask[it.index].toInt() == 0 })
    // полюса: ровно точки (0, 0, 0) и (0, 0, 8)
    assertEquals(8.0, ball.maxOf { maxOf(it[2], it[5], it[8]) }, 1e-12)
    assertEquals(0.0, ball.minOf { minOf(it[2], it[5], it[8]) }, 1e-12)
  }

  @Test
  fun `купол - полусфера над экватором до полюса`() {
    val mesh = geometry.meshes.single { it.layer == "Spheres" }
    val dome = mesh.triangles().where { x, y, z -> hypot(x - 12, y) < 3.5 && z > 4 - 1e-9 }.filter { p -> !(abs(p[2] - 4) < 1e-9 && abs(p[5] - 4) < 1e-9 && abs(p[8] - 4) < 1e-9) }
    for (p in dome) for (k in 0..2) assertEquals(3.0, dist(p[k * 3], p[k * 3 + 1], p[k * 3 + 2], 12.0, 0.0, 4.0), 1e-9)
    assertEquals(2 * PI * 9, dome.sumOf { area(it) }, 2 * PI * 9 * 0.01)
    assertEquals(7.0, dome.maxOf { maxOf(it[2], it[5], it[8]) }, 1e-12)
  }

  @Test
  fun `тор целиком - вершины на трубке, площадь`() {
    val mesh = geometry.meshes.single { it.layer == "Tori" }
    val donut = mesh.triangles().where { x, y, _ -> hypot(x - 28, y) < 7 }
    for (p in donut) for (k in 0..2) assertEquals(1.5, tube(p[k * 3], p[k * 3 + 1], p[k * 3 + 2], 28.0, 0.0, 1.5, 5.0), 1e-9)
    val full = 4 * PI * PI * 5 * 1.5
    assertEquals(full, donut.sumOf { area(it) }, full * 0.01)
  }

  @Test
  fun `отвод - четверть тора между сечениями и плоские торцы`() {
    val mesh = geometry.meshes.single { it.layer == "Tori" }
    val elbow = mesh.triangles().where { x, y, _ -> x > 34.9 && y > -1.6 }
    val wall = elbow.filter { p -> (0..2).all { abs(tube(p[it * 3], p[it * 3 + 1], p[it * 3 + 2], 36.0, 0.0, 1.5, 6.0) - 1.5) < 1e-9 } }
      .filter { p -> !(abs(p[1] - p[4]) < 1e-9 && abs(p[1] - p[7]) < 1e-9) && !(abs(p[0] - p[3]) < 1e-9 && abs(p[0] - p[6]) < 1e-9) }
    val quarter = PI * PI * 6 * 1.5
    assertEquals(quarter, wall.sumOf { area(it) }, quarter * 0.01)
    // стенка не выходит за сечения u = 0 и u = 90°
    for (p in wall) for (k in 0..2) assertTrue(p[k * 3 + 1] >= -1e-9 && p[k * 3] >= 36 - 1e-9)
    val caps = elbow.sumOf { area(it) } - wall.sumOf { area(it) }
    assertEquals(2 * PI * 1.5 * 1.5, caps, 2 * PI * 2.25 * 0.01)
  }

  @Test
  fun `брусок со скруглёнными рёбрами - все вершины на расстоянии радиуса от внутреннего бруска`() {
    val mesh = geometry.meshes.single { it.layer == "Rounded" }
    val box = mesh.triangles().where { x, _, _ -> x < 12 }
    // внутренний брусок: центр (5, −20, 3), полуразмеры 5 − 1.5, 4 − 1.5, 3 − 1.5
    fun gap(c: Double, o: Double, a: Double) = maxOf(abs(c - o) - a, 0.0)
    for (p in box) for (k in 0..2) {
      val d = sqrt(gap(p[k * 3], 5.0, 3.5).let { it * it } + gap(p[k * 3 + 1], -20.0, 2.5).let { it * it } + gap(p[k * 3 + 2], 3.0, 1.5).let { it * it })
      assertEquals(1.5, d, 1e-9)
    }
    // плоскости + четверти цилиндров + 8 восьмушек сферы
    val flat = 2 * (7.0 * 5 + 7.0 * 3 + 5.0 * 3)
    val cylinders = 4 * (7.0 + 5 + 3) * PI * 1.5 / 2
    val spheres = 4 * PI * 1.5 * 1.5
    val total = flat + cylinders + spheres
    assertEquals(total, box.sumOf { area(it) }, total * 0.005)
    // углы закрыты: восьмушка сферы в углу (+, +, +) — около 1/8 площади сферы
    val corner = box.where { x, y, z -> x > 8.5 - 1e-9 && y > -17.5 - 1e-9 && z > 4.5 - 1e-9 }.sumOf { area(it) }
    assertEquals(spheres / 8, corner, spheres / 8 * 0.02)
  }

  @Test
  fun `цилиндр со скруглённым верхом - тор между стенкой и крышкой`() {
    val mesh = geometry.meshes.single { it.layer == "Rounded" }
    val can = mesh.triangles().where { x, _, _ -> x > 15 }
    val fillet = can.filter { p -> (0..2).all { abs(tube(p[it * 3], p[it * 3 + 1], p[it * 3 + 2], 25.0, -20.0, 4.5, 3.5) - 1.5) < 1e-9 && p[it * 3 + 2] >= 4.5 - 1e-9 } }
      .filter { p -> !(abs(p[2] - p[5]) < 1e-9 && abs(p[2] - p[8]) < 1e-9) }
    val torus = 2 * PI * 1.5 * (3.5 * PI / 2 + 1.5)
    assertEquals(torus, fillet.sumOf { area(it) }, torus * 0.01)
    val total = PI * 25 + 2 * PI * 5 * 4.5 + torus + PI * 3.5 * 3.5
    assertEquals(total, can.sumOf { area(it) }, total * 0.01)
  }

  // квадрат 10×10, нижнее ребро — сплайн степени 1 с прогибом до y = −2 (SAT 4.0, как у AutoCAD 2000); конец
  // ребра-сплайна — точка [end] (для короткого ребра — точка на сплайне около начала)
  private fun splineSquare(end: String = "10 0 0") = """
      400 0 1 0
      @5 KoTIN @8 ACIS 4.0 @24 Thu Jan 01 00:00:00 2026
      1 9.9999999999999995e-07 1e-10
      body ${'$'}-1 ${'$'}1 ${'$'}-1 ${'$'}-1 #
      lump ${'$'}-1 ${'$'}-1 ${'$'}2 ${'$'}0 #
      shell ${'$'}-1 ${'$'}-1 ${'$'}-1 ${'$'}3 ${'$'}-1 ${'$'}1 #
      face ${'$'}-1 ${'$'}-1 ${'$'}4 ${'$'}2 ${'$'}-1 ${'$'}5 forward single #
      loop ${'$'}-1 ${'$'}-1 ${'$'}6 ${'$'}3 #
      plane-surface ${'$'}-1 0 0 0 0 0 1 1 0 0 forward_v I I I I #
      coedge ${'$'}-1 ${'$'}7 ${'$'}9 ${'$'}-1 ${'$'}10 forward ${'$'}4 ${'$'}-1 #
      coedge ${'$'}-1 ${'$'}8 ${'$'}6 ${'$'}-1 ${'$'}11 forward ${'$'}4 ${'$'}-1 #
      coedge ${'$'}-1 ${'$'}9 ${'$'}7 ${'$'}-1 ${'$'}12 forward ${'$'}4 ${'$'}-1 #
      coedge ${'$'}-1 ${'$'}6 ${'$'}8 ${'$'}-1 ${'$'}13 forward ${'$'}4 ${'$'}-1 #
      edge ${'$'}-1 ${'$'}14 ${'$'}15 ${'$'}6 ${'$'}18 forward #
      edge ${'$'}-1 ${'$'}15 ${'$'}16 ${'$'}7 ${'$'}19 forward #
      edge ${'$'}-1 ${'$'}16 ${'$'}17 ${'$'}8 ${'$'}19 forward #
      edge ${'$'}-1 ${'$'}17 ${'$'}14 ${'$'}9 ${'$'}19 forward #
      vertex ${'$'}-1 ${'$'}10 ${'$'}20 #
      vertex ${'$'}-1 ${'$'}11 ${'$'}21 #
      vertex ${'$'}-1 ${'$'}12 ${'$'}22 #
      vertex ${'$'}-1 ${'$'}13 ${'$'}23 #
      intcurve-curve ${'$'}-1 forward { surfintcur nubs 1 open 3 0 1 1 1 2 1 0 0 0 5 -2 0 10 0 0 0 nullbs nullbs I I } I I #
      straight-curve ${'$'}-1 0 0 0 1 0 0 I I #
      point ${'$'}-1 0 0 0 #
      point ${'$'}-1 $end #
      point ${'$'}-1 10 10 0 #
      point ${'$'}-1 0 10 0 #
      End-of-ACIS-data
    """.trimIndent()

  @Test
  fun `ребро-сплайн intcurve - ломаная от вершины до вершины через полюса bs3`() {
    val body = AcisSat.parse(splineSquare())
    assertEquals(0, body.skippedFaces)
    val edge = body.faces.single().loops.single()[0]
    val points = assertNotNull(edge.points)
    val n = points.size / 3
    assertEquals(listOf(0.0, 0.0, 0.0), points.slice(0..2))
    assertEquals(listOf(10.0, 0.0, 0.0), points.slice((n - 1) * 3 until n * 3))
    assertEquals(-2.0, (0 until n).minOf { points[it * 3 + 1] }, 1e-12)
    // точки идут по росту x (по ходу ребра)
    for (i in 1 until n) assertTrue(points[i * 3] > points[(i - 1) * 3])
  }

  @Test
  fun `ребро-сплайн короче шага ломаной - отрезок между вершинами`() {
    // конец на сплайне в 1/25 первого пролёта: между вершинами нет ни одной точки ломаной
    val body = AcisSat.parse(splineSquare("0.2 -0.08 0"))
    assertEquals(0, body.skippedFaces)
    val points = assertNotNull(body.faces.single().loops.single()[0].points)
    assertEquals(listOf(0.0, 0.0, 0.0, 0.2, -0.08, 0.0), points.toList())
  }
}
