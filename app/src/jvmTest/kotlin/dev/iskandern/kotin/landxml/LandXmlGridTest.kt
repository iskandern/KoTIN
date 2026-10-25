package dev.iskandern.kotin.landxml

import org.junit.Test
import dev.iskandern.kotin.io.ByteArrayInput
import dev.iskandern.kotin.model.SceneGeometry
import dev.iskandern.kotin.model.SceneMesh
import dev.iskandern.kotin.ru
import dev.iskandern.kotin.scene.buildRenderScene
import dev.iskandern.kotin.io.DrawingFormat
import java.io.File
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sqrt
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Поверхность grid на синтетических файлах `landxml/synthetic` (KoTIN-72, генератор `scripts/gen_test_landxml.py`):
 * рельеф — плоскость z = 100 + 0,1·Δвосток + 0,05·Δсевер, поэтому отметку любой вершины и площади знаем без файла.
 */
class LandXmlGridTest {

  private val dir = File("src/jvmTest/resources/landxml/synthetic")
  private val logs = ArrayList<String>()

  private fun bytes(name: String) = File(dir, name).readBytes()

  private fun read(bytes: ByteArray): SceneGeometry {
    val detected = DrawingFormat.detect(ByteArrayInput(bytes))
    assertEquals(DrawingFormat.LANDXML, detected.format)
    return LandXmlReader(detected.input, listOf(SurfacesSection(log = { logs.add(ru(it)) })), log = {}).read()
  }

  private fun plane(east: Double, north: Double) = 100.0 + 0.1 * (east - 2000.0) + 0.05 * (north - 5000.0)

  // z-составляющая нормали каждого треугольника: > 0 — смотрит вверх
  private fun normalsZ(mesh: SceneMesh) = (0 until mesh.triangleCount).map { k ->
    val c = mesh.coords
    val a = mesh.triangles[k * 3] * 3
    val b = mesh.triangles[k * 3 + 1] * 3
    val d = mesh.triangles[k * 3 + 2] * 3
    (c[b] - c[a]) * (c[d + 1] - c[a + 1]) - (c[b + 1] - c[a + 1]) * (c[d] - c[a])
  }

  // ячейки сетки (столбец, ряд), которые покрывают треугольники: по центру тяжести, шаг 10
  private fun coveredCells(mesh: SceneMesh): Map<Pair<Int, Int>, Int> {
    val result = HashMap<Pair<Int, Int>, Int>()
    val c = mesh.coords
    for (k in 0 until mesh.triangleCount) {
      var e = 0.0
      var n = 0.0
      for (j in 0 until 3) {
        e += c[mesh.triangles[k * 3 + j] * 3] / 3
        n += c[mesh.triangles[k * 3 + j] * 3 + 1] / 3
      }
      val cell = floor((e - 2000.0) / 10).toInt() to floor((n - 5000.0) / 10).toInt()
      result.merge(cell, 1, Int::plus)
    }
    return result
  }

  // вершины лежат на плоскости, площади — по ячейкам, сводка совпадает с эталоном и с контрольными числами файла
  private fun check(name: String, cells: Int): SceneGeometry {
    logs.clear()
    val data = bytes(name)
    val geometry = read(data)
    val mesh = geometry.meshes.single()
    assertEquals(cells * 2, mesh.triangleCount, name)
    for (v in 0 until mesh.vertexCount) {
      val z = mesh.coords[v * 3 + 2]
      assertTrue(abs(z - plane(mesh.coords[v * 3], mesh.coords[v * 3 + 1])) < 1e-9, "$name: вершина $v, z = $z")
    }
    assertTrue(normalsZ(mesh).all { it > 0 }, "$name: нормаль вниз")
    assertTrue(logs.none { "неизвестным id" in it }, logs.toString())
    val summary = summaryOf(geometry)
    assertSummaryMatches(LandXmlReference.summarize(data), summary, setOf(SURFACES), name)
    val surface = summary.surfaces.single()
    assertTrue(abs(surface.area2D - cells * 100.0) < 1e-6, "$name: площадь 2D ${surface.area2D}")
    assertTrue(abs(surface.area3D - cells * 100.0 * sqrt(1.0125)) < 1e-6, "$name: площадь 3D ${surface.area3D}")
    val reference = LandXmlReference.summarize(data).surfaces.single()
    val control = SurfaceSummary(
      surface.name, null, null, surface.visibleFaces, null, surface.usedPoints, surface.box, surface.area2D, surface.area3D,
      reference.fileArea2D, reference.fileArea3D, reference.fileElevMin, reference.fileElevMax,
    )
    assertTrue(reference.fileArea2D != null && reference.fileElevMax != null, "$name: нет контрольных чисел")
    assertEquals(emptyList(), controlMismatches(control), name)
    return geometry
  }

  @Test
  fun `обычная сетка — ячейка даёт два треугольника, диагональ скрыта, F@n с четырьмя значениями не мешает`() {
    val geometry = check("grid-plane.xml", cells = 20)
    val mesh = geometry.meshes.single()
    assertEquals(30, mesh.vertexCount)
    assertEquals((0 until 5).flatMap { r -> (0 until 4).map { c -> (c to r) } }.toSet().size, coveredCells(mesh).size)
    assertTrue(coveredCells(mesh).values.all { it == 2 })
    // каркас: 5 · 5 горизонтальных и 6 · 4 вертикальных рёбер, диагоналей нет
    assertEquals(49, buildRenderScene(geometry).surfaces.single().edges.size / 2)
    assertEquals("grid", geometry.objects.single().attributes.first { ru(it.name) == "Тип" }.value)
  }

  @Test
  fun `порядок вершин ячейки — против часовой с любого угла, по часовой, Z и восьмёрка дают одну плоскость`() {
    val geometry = check("grid-vertex-orders.xml", cells = 9)
    val mesh = geometry.meshes.single()
    assertEquals(16, mesh.vertexCount)
    val covered = coveredCells(mesh)
    assertEquals(9, covered.size)
    assertTrue(covered.values.all { it == 2 })
    // три ячейки переставлены (Z, восьмёрка, Z со сдвигом), две по часовой — это четыре треугольника
    assertTrue(logs.any { "крест-накрест 3" in it }, logs.toString())
    assertTrue(logs.any { "4 граней записаны по часовой" in it }, logs.toString())
    // диагональ скрыта и после перестановки: каркас — только стороны ячеек, 3 · 4 + 4 · 3
    assertEquals(24, buildRenderScene(geometry).surfaces.single().edges.size / 2)
  }

  @Test
  fun `пропуски — нет ячейки, нули в F, заглушки отметок, невидимая грань — без треугольников и шипов`() {
    val geometry = check("grid-nodata.xml", cells = 17)
    val mesh = geometry.meshes.single()
    val skipped = setOf(0 to 1, 1 to 1, 2 to 2, 3 to 3, 4 to 3, 3 to 4, 4 to 4, 4 to 0)
    val expected = (0 until 5).flatMap { r -> (0 until 5).map { c -> c to r } }.filter { it !in skipped }.toSet()
    val covered = coveredCells(mesh)
    assertEquals(expected, covered.keys)
    assertTrue(covered.values.all { it == 2 })
    assertTrue(mesh.maxZ < 107.0 && mesh.minZ >= 100.0, "заглушка попала в сцену: ${mesh.minZ}…${mesh.maxZ}")
    // нули в F — не ошибка; заглушек пять ячеек
    assertTrue(logs.any { "(нули в F) 1, с заглушкой вместо отметки 5" in it }, logs.toString())
    assertTrue(logs.none { "неизвестным id" in it }, logs.toString())
    val attributes = geometry.objects.single().attributes.associate { ru(it.name) to ru(it.value) }
    // ячейки без F в файле нет — граней 24
    assertEquals("24", attributes["Граней"])
    assertEquals("1", attributes["Невидимых граней"])
    assertEquals("17", attributes["Видимых граней"])
  }
}
