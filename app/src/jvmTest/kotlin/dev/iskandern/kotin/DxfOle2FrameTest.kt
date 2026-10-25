package dev.iskandern.kotin

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** OLE2FRAME — рамка по углам 10/11 (KoTIN-98). */
class DxfOle2FrameTest {

  private fun ole(vararg corners: String) = arrayOf(
    "0", "OLE2FRAME", "8", "OLE", "62", "1", "100", "AcDbOle2Frame", "70", "2", "3", "Paintbrush Picture",
    *corners, "71", "2", "72", "0", "90", "4", "310", "00010203", "310", "04050607", "1", "OLE",
  )

  private fun read(vararg entities: Array<String>) = readGeometry(
    "0", "SECTION", "2", "ENTITIES", *entities.flatten().toTypedArray(),
    "0", "LINE", "8", "0", "10", "0", "20", "0", "11", "1", "21", "0",
    "0", "ENDSEC", "0", "EOF",
  )

  private fun points(coords: DoubleArray) = coords.toList().chunked(3).map { Triple(it[0], it[1], it[2]) }.toSet()

  @Test
  fun `рамка - четыре отрезка по углам на Z из 10, цвет и слой сущности`() {
    val geometry = read(ole("10", "10", "20", "40", "30", "5", "11", "60", "21", "10", "31", "7"))
    val batch = geometry.lines.single { it.layer == "OLE" }
    assertEquals(4, batch.segmentCount)
    assertEquals(0xFF0000, batch.color)
    assertEquals(
      setOf(Triple(10.0, 40.0, 5.0), Triple(60.0, 40.0, 5.0), Triple(60.0, 10.0, 5.0), Triple(10.0, 10.0, 5.0)),
      points(batch.coords),
    )
    assertEquals(listOf("OLE2FRAME", "LINE"), geometry.objects.map { it.kind })
  }

  @Test
  fun `без кода 11 рамки нет`() {
    val geometry = read(ole("10", "10", "20", "40", "30", "0"))
    assertTrue(geometry.lines.none { it.layer == "OLE" })
    assertEquals(listOf("LINE"), geometry.objects.map { it.kind })
  }

  @Test
  fun `тестовый файл - рамка в модели и рамка блока в мировых координатах`() {
    val geometry = readGeometry(File("src/jvmTest/resources/dxf/ole2frame.dxf"))
    val frames = geometry.lines.filter { it.layer == "OLE" }
    assertEquals(8, frames.sumOf { it.segmentCount })
    val all = frames.flatMap { points(it.coords) }.toSet()
    assertTrue(Triple(10.0, 40.0, 5.0) in all && Triple(60.0, 10.0, 5.0) in all)
    // блок (0, 20)–(30, 0) под вставкой в (100, 0) с масштабом 2
    assertTrue(Triple(100.0, 40.0, 0.0) in all && Triple(160.0, 0.0, 0.0) in all)
    // третья рамка без кода 11 — ничего около x = 200
    assertTrue(all.none { it.first >= 200.0 })
    // рамка блока — часть объекта INSERT
    assertEquals(1, geometry.objects.count { it.kind == "OLE2FRAME" })
  }
}
