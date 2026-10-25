package dev.iskandern.kotin

import dev.iskandern.kotin.dxf.DxfDocumentReader
import dev.iskandern.kotin.dxf.DxfEntity
import dev.iskandern.kotin.io.asByteInput
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** SURFACE (PLANESURFACE, EXTRUDEDSURFACE…) через путь тел 3DSOLID (файлы — `scripts/gen_test_dxf.py`, task97). */
class DxfSurfaceTest {

  private val file = File(testDxfDir, "surface.dxf")

  @Test
  fun `R2010 - три листа по 2 треугольника, SURFACE без SAT только считается`() {
    val g = readGeometry(file)
    val mesh = g.meshes.single { it.layer == "Surfaces" }
    assertEquals(6, mesh.triangleCount)
    assertEquals(0.0, mesh.minX, 1e-9)
    assertEquals(48.0, mesh.maxX, 1e-9)
    // вертикальный прямоугольник EXTRUDEDSURFACE: высота 6
    assertEquals(6.0, mesh.maxZ, 1e-9)
    assertEquals(1, g.unreadBodies)
    assertEquals(0, g.hiddenBodyFaces)
    assertEquals(1, g.lines.size)
    // в статистике типов — SURFACE, не 3DSOLID
    // объект сцены — у трёх показанных; тело без SAT, как у 3DSOLID, только в счётчике
    assertEquals(3, g.objects.count { it.kind == "SURFACE" })
    assertTrue(g.objects.none { it.kind == "3DSOLID" })
  }

  @Test
  fun `подклассы SURFACE не попадают в SAT и общие коды`() {
    val doc = file.inputStream().use { DxfDocumentReader(it.asByteInput()).read() }
    val bodies = doc.entities.filterIsInstance<DxfEntity.Body>()
    assertEquals(4, bodies.size)
    assertEquals(listOf(1, 1, 1), bodies.take(3).map { assertNotNull(it.acis).faces.size })
    assertNull(bodies[3].acis)
    // код 48 матрицы EXTRUDEDSURFACE — не масштаб типа линии
    assertTrue(bodies.all { it.common.lineTypeScale == 1.0 })
    // строки 1/3 после AcDbModelerGeometry — не продолжение SAT
    val text = file.readText().replace("AcDbExtrudedSurface\n", "AcDbExtrudedSurface\n  1\nnot a SAT\n  3\nmore\n")
    val patched = readGeometry(text.byteInputStream())
    assertEquals(6, patched.meshes.single { it.layer == "Surfaces" }.triangleCount)
  }

  @Test
  fun `R2018 - SAB из ACDSDATA по handle SURFACE`() {
    val g = readGeometry(File(testDxfDir, "surface-sab.dxf"))
    assertEquals(0, g.unreadBodies)
    val mesh = g.meshes.single()
    assertEquals(2, mesh.triangleCount)
    assertEquals(10.0, mesh.maxX - mesh.minX, 1e-9)
    assertEquals("SURFACE", g.objects.single().kind)
  }
}
