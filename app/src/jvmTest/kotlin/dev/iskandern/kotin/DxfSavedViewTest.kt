package dev.iskandern.kotin

import dev.iskandern.kotin.dxf.DxfDocumentReader
import dev.iskandern.kotin.io.asByteInput
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class DxfSavedViewTest {

  private fun read(vararg vports: Array<String>) = DxfDocumentReader(
    arrayOf(
      "0", "SECTION", "2", "TABLES", "0", "TABLE", "2", "VPORT", "70", "1",
      *vports.flatMap { it.toList() }.toTypedArray(),
      "0", "ENDTAB", "0", "ENDSEC", "0", "SECTION", "2", "ENTITIES", "0", "ENDSEC", "0", "EOF",
    ).joinToString("\n", postfix = "\n").byteInputStream().asByteInput(),
  ).read()

  private fun vport(name: String, vararg extra: String) = arrayOf("0", "VPORT", "2", name, "70", "0", *extra)

  @Test
  fun `центр и высота Active попадают в документ`() {
    val view = assertNotNull(
      read(vport("*Active", "12", "150.5", "22", "-40", "40", "80", "41", "1.5", "16", "0", "26", "0", "36", "1")).savedView,
    )
    assertEquals(150.5, view.centerX)
    assertEquals(-40.0, view.centerY)
    assertEquals(80.0, view.height)
  }

  @Test
  fun `другие виды и пустая высота игнорируются`() {
    assertNull(read(vport("*Other", "12", "1", "22", "1", "40", "10")).savedView)
    assertNull(read(vport("*Active", "12", "1", "22", "1", "40", "0")).savedView)
    assertNull(read().savedView)
  }

  @Test
  fun `для наклонного вида центр пересчитывается через цель`() {
    // направление взгляда (0, −1, 0): горизонтальный вид, ось Y вида вертикальна и на X, Y мира не влияет
    val view = assertNotNull(
      read(vport("*Active", "12", "5", "22", "7", "40", "20", "16", "0", "26", "-1", "36", "0", "17", "100", "27", "200", "37", "0")).savedView,
    )
    // X вида = (0,0,1) × (0,−1,0) = (1, 0, 0); Y вида = Z × X = (0, 0, 1)
    assertEquals(105.0, view.centerX, 1e-9)
    assertEquals(200.0, view.centerY, 1e-9)
  }

  @Test
  fun `вид сверху с ненулевой целью - центр смещён на цель`() {
    val view = assertNotNull(
      read(vport("*Active", "12", "-123.19", "22", "28.34", "40", "75.4", "16", "0", "26", "0", "36", "1", "17", "210", "27", "148.5", "37", "0")).savedView,
    )
    assertEquals(86.81, view.centerX, 1e-9)
    assertEquals(176.84, view.centerY, 1e-9)
  }

  @Test
  fun `вид снизу с ненулевой целью - X зеркален относительно цели`() {
    val view = assertNotNull(
      read(vport("*Active", "12", "-10", "22", "5", "40", "20", "16", "0", "26", "0", "36", "-1", "17", "100", "27", "200", "37", "0")).savedView,
    )
    assertEquals(110.0, view.centerX, 1e-9)
    assertEquals(205.0, view.centerY, 1e-9)
  }
}
