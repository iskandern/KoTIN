package dev.iskandern.kotin.landxml

import org.junit.Test
import dev.iskandern.kotin.io.ByteArrayInput
import dev.iskandern.kotin.model.LineBatch
import dev.iskandern.kotin.model.PointMark
import dev.iskandern.kotin.model.SceneGeometry
import dev.iskandern.kotin.model.SceneMesh
import dev.iskandern.kotin.io.asByteInput
import java.io.File
import kotlin.math.abs
import kotlin.random.Random
import dev.iskandern.kotin.scene.buildRenderScene
import dev.iskandern.kotin.io.DrawingFormat
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Посадка 2D-линий, точек и подписей на поверхность (KoTIN-67). */
class LandXmlDrapeTest {

  // сетка n × n точек с шагом step, вершина (i, j) — x = x0 + i · step, y = y0 + j · step; z — по [z]
  private fun gridMesh(
    n: Int, step: Double, x0: Double = 0.0, y0: Double = 0.0, layer: String = "S",
    z: (Double, Double) -> Double,
  ): SceneMesh {
    val coords = DoubleArray(n * n * 3)
    for (j in 0 until n) for (i in 0 until n) {
      val v = (j * n + i) * 3
      val x = x0 + i * step
      val y = y0 + j * step
      coords[v] = x
      coords[v + 1] = y
      coords[v + 2] = z(x, y)
    }
    val triangles = IntArray((n - 1) * (n - 1) * 6)
    var k = 0
    for (j in 0 until n - 1) for (i in 0 until n - 1) {
      val a = j * n + i
      triangles[k++] = a; triangles[k++] = a + 1; triangles[k++] = a + n + 1
      triangles[k++] = a; triangles[k++] = a + n + 1; triangles[k++] = a + n
    }
    var minZ = Double.POSITIVE_INFINITY
    var maxZ = Double.NEGATIVE_INFINITY
    for (v in 0 until n * n) {
      minZ = minOf(minZ, coords[v * 3 + 2])
      maxZ = maxOf(maxZ, coords[v * 3 + 2])
    }
    val side = (n - 1) * step
    return SceneMesh(layer, coords, triangles, x0, y0, minZ, x0 + side, y0 + side, maxZ)
  }

  // эталон: перебор всех треугольников, барицентрика с тем же допуском; NaN — вне поверхности
  private fun bruteHeight(mesh: SceneMesh, px: Double, py: Double): Double {
    val c = mesh.coords
    for (t in 0 until mesh.triangleCount) {
      val a = mesh.triangles[t * 3] * 3
      val b = mesh.triangles[t * 3 + 1] * 3
      val d = mesh.triangles[t * 3 + 2] * 3
      val s = (c[b] - c[a]) * (c[d + 1] - c[a + 1]) - (c[b + 1] - c[a + 1]) * (c[d] - c[a])
      val wa = ((c[d] - c[b]) * (py - c[b + 1]) - (c[d + 1] - c[b + 1]) * (px - c[b])) / s
      val wb = ((c[a] - c[d]) * (py - c[d + 1]) - (c[a + 1] - c[d + 1]) * (px - c[d])) / s
      val wd = 1 - wa - wb
      if (wa >= -1e-12 && wb >= -1e-12 && wd >= -1e-12) return wa * c[a + 2] + wb * c[b + 2] + wd * c[d + 2]
    }
    return Double.NaN
  }

  private fun batch(geometry: SceneGeometry, flat: Boolean): LineBatch? = geometry.lines.singleOrNull { it.flat == flat }

  private fun segments(batch: LineBatch?) = (0 until (batch?.coords?.size ?: 0) / 6).map { k ->
    DoubleArray(6) { batch!!.coords[k * 6 + it] }
  }

  @Test
  fun `наклонная плоскость — высота линии равна плоскости, плоских отрезков не остаётся`() {
    val plane = { x: Double, y: Double -> 100 + 0.1 * x - 0.05 * y }
    val scene = LandXmlSceneBuilder()
    scene.addMesh(gridMesh(11, 10.0, z = plane), "S")
    scene.lines("Трассы", 1, flat = true).add(3.0, 4.0, 0.0, 97.0, 61.0, 0.0, objectId = 0)
    val geometry = scene.finish()
    assertEquals(null, batch(geometry, flat = true))
    val draped = batch(geometry, flat = false)!!
    for (s in segments(draped)) {
      assertEquals(plane(s[0], s[1]), s[2], 1e-9)
      assertEquals(plane(s[3], s[4]), s[5], 1e-9)
    }
    assertTrue(draped.objectIds!!.all { it == 0 })
    // куски покрывают отрезок без щелей
    val list = segments(draped)
    assertEquals(3.0, list.first()[0], 1e-9)
    assertEquals(97.0, list.last()[3], 1e-9)
    for (k in 1 until list.size) {
      assertEquals(list[k - 1][3], list[k][0], 1e-9)
      assertEquals(list[k - 1][4], list[k][1], 1e-9)
    }
  }

  @Test
  fun `линия через несколько треугольников делится на пересечениях с рёбрами`() {
    val random = Random(7)
    val heights = DoubleArray(10_000) { random.nextDouble(0.0, 20.0) }
    // рельеф «зубцами»: высота вершины случайна, внутри треугольника — плоскость
    val mesh = gridMesh(6, 10.0) { x, y -> heights[(x / 10).toInt() * 7 + (y / 10).toInt() * 13] }
    val scene = LandXmlSceneBuilder()
    scene.addMesh(mesh, "S")
    scene.lines("L", 1, flat = true).add(1.0, 2.0, 0.0, 47.0, 31.0, 0.0)
    val list = segments(batch(scene.finish(), flat = false))
    // ребро пересекается: вертикали x = 10..40 (4), горизонтали y = 10..30 (3), диагонали — по клеткам
    assertTrue(list.size >= 8, "кусков ${list.size}")
    for (s in list) {
      // середина куска лежит на рельефе: кусок не режет треугольник хордой
      val mx = (s[0] + s[3]) / 2
      val my = (s[1] + s[4]) / 2
      assertEquals(bruteHeight(mesh, mx, my), (s[2] + s[5]) / 2, 1e-9)
      assertEquals(bruteHeight(mesh, s[0], s[1]), s[2], 1e-9)
      assertEquals(bruteHeight(mesh, s[3], s[4]), s[5], 1e-9)
    }
  }

  @Test
  fun `линия вне поверхности остаётся плоской, частично вне — плоская только снаружи`() {
    val scene = LandXmlSceneBuilder()
    scene.addMesh(gridMesh(5, 10.0) { _, _ -> 50.0 }, "S")
    scene.lines("Вне", 1, flat = true).add(100.0, 100.0, 0.0, 200.0, 150.0, 0.0)
    scene.lines("Через", 2, flat = true).add(-10.0, 20.0, 0.0, 60.0, 20.0, 0.0)
    val geometry = scene.finish()
    val outside = geometry.lines.single { it.layer == "Вне" }
    assertTrue(outside.flat)
    // отметка — нижний уровень сеток, тот же, куда плоское кладёт сцена
    assertEquals(listOf(100.0, 100.0, 50.0, 200.0, 150.0, 50.0), outside.coords.toList())

    val through = geometry.lines.filter { it.layer == "Через" }
    val flat = segments(through.single { it.flat })
    val draped = segments(through.single { !it.flat })
    assertEquals(2, flat.size)
    assertEquals(-10.0, flat[0][0], 1e-9)
    assertEquals(0.0, flat[0][3], 1e-9)
    assertEquals(40.0, flat[1][0], 1e-9)
    assertEquals(60.0, flat[1][3], 1e-9)
    assertTrue(flat.all { it[2] == 50.0 && it[5] == 50.0 })
    assertTrue(draped.all { it[2] == 50.0 && it[5] == 50.0 })
    assertEquals(0.0, draped.first()[0], 1e-9)
    assertEquals(40.0, draped.last()[3], 1e-9)
  }

  @Test
  fun `линии с отметками, 3D-точки и файл без поверхности не меняются`() {
    val scene = LandXmlSceneBuilder()
    scene.addMesh(gridMesh(5, 10.0) { x, _ -> 50.0 + x }, "S")
    scene.lines("Профиль", 1, flat = false).add(1.0, 1.0, 7.0, 30.0, 30.0, 9.0)
    scene.addPoint(PointMark("P", 1, 5.0, 5.0, 12.0))
    scene.addPoint(PointMark("P", 1, 6.0, 6.0, 0.0), flat = true)
    scene.addPoint(PointMark("P", 1, 99.0, 99.0, 0.0), flat = true)
    scene.addLabel("P", 1, 6.0, 6.0, 0.0, "2D", flat = true)
    scene.addLabel("P", 1, 5.0, 5.0, 12.0, "3D")
    val geometry = scene.finish()
    assertEquals(listOf(1.0, 1.0, 7.0, 30.0, 30.0, 9.0), geometry.lines.single().coords.toList())
    // вне поверхности — нижний уровень сетки (50), как положила бы сцена
    assertEquals(listOf(12.0, 56.0, 50.0), geometry.points.map { it.z })
    assertEquals(listOf(56.0, 12.0), geometry.labels.map { it.z })
    assertEquals(1, scene.drapedPoints)

    val bare = LandXmlSceneBuilder()
    bare.lines("L", 1, flat = true).add(0.0, 0.0, 0.0, 10.0, 0.0, 0.0)
    bare.addPoint(PointMark("P", 1, 1.0, 1.0, 0.0), flat = true)
    val plain = bare.finish()
    assertTrue(plain.lines.single().flat)
    assertEquals(0.0, plain.points.single().z)
    assertEquals(0, bare.drapedSegments)
  }

  @Test
  fun `из нескольких поверхностей — наибольшая, где лежит точка, по surfaceRef — указанная`() {
    val scene = LandXmlSceneBuilder()
    // малая внутри большой и вторая малая — вне большой
    scene.addMesh(gridMesh(3, 5.0, x0 = 10.0, y0 = 10.0, layer = "Малая") { _, _ -> 30.0 }, "Малая")
    scene.addMesh(gridMesh(5, 10.0, layer = "Большая") { _, _ -> 20.0 }, "Большая")
    scene.addMesh(gridMesh(3, 5.0, x0 = 100.0, layer = "Отдельная") { _, _ -> 40.0 }, "Отдельная")
    scene.addPoint(PointMark("P", 1, 15.0, 15.0, 0.0), flat = true)
    scene.addPoint(PointMark("P", 1, 105.0, 5.0, 0.0), flat = true)
    scene.lines("Ссылка", 1, flat = true, surfaceRef = "Малая").add(12.0, 12.0, 0.0, 18.0, 12.0, 0.0)
    val geometry = scene.finish()
    assertEquals(listOf(20.0, 40.0), geometry.points.map { it.z })
    val ref = segments(geometry.lines.single { it.layer == "Ссылка" && !it.flat })
    assertTrue(ref.all { it[2] == 30.0 && it[5] == 30.0 })
  }

  @Test
  fun `эталон — высота в случайных точках перебором совпадает с индексом`() {
    val random = Random(11)
    val heights = DoubleArray(400) { random.nextDouble(100.0, 120.0) }
    val n = 20
    // неравномерная сетка: вершины сдвинуты, чтобы треугольники были разными
    val base = gridMesh(n, 3.0) { _, _ -> 0.0 }
    val coords = base.coords.copyOf()
    for (v in 0 until n * n) {
      coords[v * 3] += random.nextDouble(-0.9, 0.9)
      coords[v * 3 + 1] += random.nextDouble(-0.9, 0.9)
      coords[v * 3 + 2] = heights[v]
    }
    val mesh = SceneMesh("S", coords, base.triangles,
      coords.filterIndexed { i, _ -> i % 3 == 0 }.min(), coords.filterIndexed { i, _ -> i % 3 == 1 }.min(), 100.0,
      coords.filterIndexed { i, _ -> i % 3 == 0 }.max(), coords.filterIndexed { i, _ -> i % 3 == 1 }.max(), 120.0)
    val drape = LandXmlDrape(listOf(DrapeSurface("S", mesh)))
    var inside = 0
    var outside = 0
    repeat(20_000) {
      val x = random.nextDouble(-5.0, 62.0)
      val y = random.nextDouble(-5.0, 62.0)
      val expected = bruteHeight(mesh, x, y)
      val actual = drape.height(x, y)
      if (expected.isNaN()) {
        assertTrue(actual.isNaN(), "($x, $y): вне поверхности, а индекс дал $actual")
        outside++
      } else {
        assertEquals(expected, actual, 1e-9, "($x, $y)")
        inside++
      }
    }
    assertTrue(inside > 1000 && outside > 1000, "внутри $inside, вне $outside")
  }

  @Test
  fun `скорость — индекс на 1 млн треугольников против чтения того же TIN, посадка 100 тыс вершин`() {
    val tin = SyntheticLandXml(points = 560_000, faces = 1_060_000)
    val bytes = tin.bytes()
    lateinit var mesh: SceneMesh
    val read = Bench.run("LandXmlReader: TIN 1 млн", bytes.size.toLong(), warmup = 1, runs = 3) {
      mesh = LandXmlReader(DrawingFormat.detect(bytes.inputStream().asByteInput()).input, listOf(SurfacesSection(log = {})),
        log = {}).read().meshes.single()
    }
    val index = Bench.run("индекс посадки: 1 млн", bytes.size.toLong(), warmup = 1, runs = 3) {
      DrapeSurface("S", mesh).ensureIndex()
    }
    println("треугольников ${mesh.triangleCount}: чтение %.0f мс, индекс %.0f мс".format(read.medianMs, index.medianMs))

    // ломаная 100 тыс. вершин: зигзаг по всей поверхности
    val surface = DrapeSurface("S", mesh)
    surface.ensureIndex()
    val drape = LandXmlDrape(listOf(surface))
    val random = Random(3)
    val w = mesh.maxX - mesh.minX
    val h = mesh.maxY - mesh.minY
    var px = mesh.minX + w / 2
    var py = mesh.minY + h / 2
    var pieces = 0L
    val start = System.nanoTime()
    repeat(100_000) {
      val nx = (px + random.nextDouble(-15.0, 15.0)).coerceIn(mesh.minX, mesh.maxX)
      val ny = (py + random.nextDouble(-15.0, 15.0)).coerceIn(mesh.minY, mesh.maxY)
      drape.out.clear()
      drape.segment(px, py, 0.0, nx, ny, 0.0)
      pieces += drape.out.size / 7
      px = nx
      py = ny
    }
    val ms = (System.nanoTime() - start) / 1e6
    println("посадка ломаной 100 тыс. вершин: %.0f мс, кусков $pieces".format(ms))
    assertTrue(index.medianMs <= read.medianMs * 2, "индекс ${index.medianMs} мс, чтение ${read.medianMs} мс")
    assertTrue(abs(pieces) > 100_000)
  }
}
