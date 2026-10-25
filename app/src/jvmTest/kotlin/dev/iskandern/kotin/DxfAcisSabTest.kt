package dev.iskandern.kotin

import dev.iskandern.kotin.dxf.DxfDocumentReader
import dev.iskandern.kotin.dxf.DxfEntity
import dev.iskandern.kotin.io.asByteInput
import dev.iskandern.kotin.model.SceneGeometry
import dev.iskandern.kotin.model.SceneMesh
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** 3DSOLID, REGION, BODY R2013+: SAB из секции ACDSDATA (файлы — `scripts/gen_test_dxf.py`, task48 и task96). */
class DxfAcisSabTest {

  private val sat: SceneGeometry by lazy { readGeometry(File(testDxfDir, "acis-sat.dxf")) }

  // треугольники с маской каркаса, с округлением — для сравнения без учёта порядка
  private fun SceneMesh.triangleSet(dx: Double = 0.0, dy: Double = 0.0, dz: Double = 0.0): List<String> =
    (0 until triangleCount).map { t ->
      val corners = (0..2).map { k ->
        val v = triangles[t * 3 + k] * 3
        "%.9f %.9f %.9f".format(coords[v] + dx, coords[v + 1] + dy, coords[v + 2] + dz)
      }
      corners.joinToString(" | ") + " / " + (edgeMask?.get(t) ?: 0)
    }.sorted()

  private fun assertSameAsSat(name: String) {
    val g = readGeometry(File(testDxfDir, "$name"))
    assertEquals(0, g.unreadBodies)
    assertEquals(0, g.hiddenBodyFaces)
    val solids = g.meshes.single { it.layer == "Solids" }
    assertEquals(sat.meshes.single { it.layer == "Solids" }.triangleSet(), solids.triangleSet())
    assertEquals(1, g.lines.size)
  }

  @Test
  fun `R2018 - кубы из SAB те же, что из SAT`() = assertSameAsSat("acis-sab-ascii.dxf")

  @Test
  fun `бинарный DXF R2013 - кубы из SAB те же, что из SAT`() = assertSameAsSat("acis-sab-bin.dxf")

  @Test
  fun `R2018 - куб 10 у начала координат`() {
    val g = readGeometry(File(testDxfDir, "acis-sab.dxf"))
    assertEquals(0, g.unreadBodies)
    val cube = g.meshes.single()
    assertEquals(12, cube.triangleCount)
    assertEquals(-5.0, cube.minX, 1e-9)
    assertEquals(5.0, cube.maxZ, 1e-9)
    // тот же куб, что в SAT-файле, сдвинутый на 5: в SAT он стоит в 0…10
    val satCube = sat.meshes.single { it.layer == "Solids" }
    val shifted = cube.triangleSet(5.0, 5.0, 5.0)
    assertTrue(satCube.triangleSet().containsAll(shifted))
  }

  @Test
  fun `обрезанный или чужой SAB - тело без данных, без падения`() {
    val file = File(testDxfDir, "acis-sab-ascii.dxf")
    val doc = file.inputStream().use { DxfDocumentReader(it.asByteInput()).read() }
    val body = doc.entities.filterIsInstance<DxfEntity.Body>().first()
    val good = assertNotNull(body.sab)
    for (bad in listOf(good.copyOf(good.size / 2), good.copyOf(40), "not a SAB".encodeToByteArray())) {
      val b = DxfEntity.Body(null)
      b.sab = bad
      assertEquals(null, b.acis)
    }
    assertEquals(6, assertNotNull(body.acis).faces.size)
  }

}
