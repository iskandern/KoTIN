package dev.iskandern.kotin

import dev.iskandern.kotin.dxf.DxfColors
import dev.iskandern.kotin.model.SceneGeometry
import java.io.File
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DxfMultiLeaderTest {

  // цвета MULTILEADER: старший байт C3 — ACI в младшем
  private val red = (0xC3000001).toInt()
  private val green = (0xC3000003).toInt()
  private val blue = (0xC3000005).toInt()

  /** Блок RING (кружок R1, запись B1), MULTILEADER [leader] и стиль [style] в OBJECTS. */
  private fun file(leader: List<String>, style: List<String>) = arrayOf(
    "0", "SECTION", "2", "TABLES",
    "0", "TABLE", "2", "BLOCK_RECORD", "5", "1",
    "0", "BLOCK_RECORD", "5", "B1", "2", "RING",
    "0", "ENDTAB", "0", "ENDSEC",
    "0", "SECTION", "2", "BLOCKS",
    "0", "BLOCK", "2", "RING", "10", "0", "20", "0", "30", "0",
    "0", "CIRCLE", "8", "0", "62", "0", "10", "0", "20", "0", "30", "0", "40", "1",
    "0", "ENDBLK", "0", "ENDSEC",
    "0", "SECTION", "2", "ENTITIES", *leader.toTypedArray(), "0", "ENDSEC",
    "0", "SECTION", "2", "OBJECTS", *style.toTypedArray(), "0", "ENDSEC", "0", "EOF",
  )

  private fun style(vararg pairs: String) = listOf("0", "MLEADERSTYLE", "5", "S1", "170", "1", *pairs)

  /** Линия из (0, 0) к точке выноски (5, 5) и полка 1 вправо; блок в (7, 5) с масштабом 2. */
  private fun leader(contextBlock: String?, vararg entityPairs: String) = listOf(
    "0", "MULTILEADER", "8", "ML", "100", "AcDbMLeader", "270", "2",
    "300", "CONTEXT_DATA{", "40", "1", "41", "1", "290", "0", "296", "1",
    *(if (contextBlock != null) arrayOf("341", contextBlock) else emptyArray()),
    "14", "0", "24", "0", "34", "1", "15", "7", "25", "5", "35", "0",
    "16", "2", "26", "2", "36", "2", "46", "0",
    "302", "LEADER{", "290", "1", "291", "1", "10", "5", "20", "5", "30", "0", "11", "1", "21", "0", "31", "0", "40", "1",
    "304", "LEADER_LINE{", "10", "0", "20", "0", "30", "0", "305", "}",
    "303", "}", "301", "}",
    "340", "S1", *entityPairs,
  )

  private fun points(geometry: SceneGeometry, color: Int) = geometry.lines.filter { it.color == color }.flatMap { batch ->
    (0 until batch.coords.size / 3).map { batch.coords[it * 3] to batch.coords[it * 3 + 1] }
  }

  private fun assertRing(geometry: SceneGeometry, color: Int, cx: Double, cy: Double, r: Double) {
    val ring = points(geometry, color)
    assertTrue(ring.size > 8, "нет кружка цвета $color")
    ring.forEach { (x, y) -> assertEquals(r, hypot(x - cx, y - cy), 1e-6) }
  }

  @Test
  fun `блок-содержимое в точке содержимого, линия выноски с полкой`() {
    val geometry = readGeometry(*file(leader("B1", "90", "0"), style("91", "$red", "94", "$blue")))
    assertRing(geometry, DxfColors.aciToRgb(5), 7.0, 5.0, 2.0)
    // линия выноски: (0,0)–(5,5)–(6,5), цвет из стиля
    val line = points(geometry, DxfColors.aciToRgb(1))
    assertEquals(setOf(0.0 to 0.0, 5.0 to 5.0, 6.0 to 5.0), line.toSet())
  }

  @Test
  fun `взведённые флаги - цвета из сущности`() {
    val flags = 0x2 or 0x80000
    val geometry = readGeometry(*file(
      leader("B1", "90", "$flags", "91", "$green", "93", "$red"),
      style("91", "$red", "94", "$blue"),
    ))
    assertRing(geometry, DxfColors.aciToRgb(1), 7.0, 5.0, 2.0)
    assertEquals(3, points(geometry, DxfColors.aciToRgb(3)).toSet().size)
  }

  @Test
  fun `блок не указан в контексте - из стиля, если сущность не переопределяет`() {
    val geometry = readGeometry(*file(leader(null, "90", "0", "344", "XX"), style("343", "B1", "94", "$blue")))
    assertRing(geometry, DxfColors.aciToRgb(5), 7.0, 5.0, 2.0)
  }

  @Test
  fun `тип содержимого из стиля - MTEXT, блок не рисуется`() {
    val geometry = readGeometry(*file(leader("B1", "90", "0"), listOf("0", "MLEADERSTYLE", "5", "S1", "170", "2", "94", "$blue")))
    assertTrue(points(geometry, DxfColors.aciToRgb(5)).isEmpty())
  }

  @Test
  fun `тестовый файл задачи - кружки блоков на месте, линия по стилю RED`() {
    val geometry = readGeometry(File(testDxfDir, "multileader-block.dxf"))
    assertFalse(geometry.truncated)
    // блок — кружок R1 и отрезок внутри; во второй выноске вдвое больше
    fun assertMark(color: Int, cx: Double, r: Double) {
      val ring = points(geometry, color).filter { (x, y) -> hypot(x - cx, y - 6) > r * 0.75 }
      assertTrue(ring.size > 8, "нет кружка цвета $color")
      ring.forEach { (x, y) -> assertEquals(r, hypot(x - cx, y - 6), 1e-6) }
    }
    assertMark(DxfColors.aciToRgb(5), 10.0, 1.0)
    assertMark(DxfColors.aciToRgb(3), 30.0, 2.0)
    // третья: зелёный цвет сущности не переопределяет стиль — линия от (0, −10) красная
    assertTrue((0.0 to -10.0) in points(geometry, DxfColors.aciToRgb(1)))
  }
}
