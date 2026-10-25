package dev.iskandern.kotin

import dev.iskandern.kotin.scene.buildRenderScene
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DxfXLineTest {

  // квадрат 10×10 у начала координат задаёт габариты сцены
  private val square = arrayOf(
    "0", "LINE", "8", "A", "10", "0", "20", "0", "30", "0", "11", "10", "21", "0", "31", "0",
    "0", "LINE", "8", "A", "10", "10", "20", "0", "30", "0", "11", "10", "21", "10", "31", "0",
    "0", "LINE", "8", "A", "10", "10", "20", "10", "30", "0", "11", "0", "21", "10", "31", "0",
    "0", "LINE", "8", "A", "10", "0", "20", "10", "30", "0", "11", "0", "21", "0", "31", "0",
  )

  private fun xline(type: String) = arrayOf(
    "0", type, "8", "X", "62", "1", "10", "0", "20", "0", "30", "0", "11", "1", "21", "1", "31", "0",
  )

  private fun doc(vararg entities: Array<String>) = arrayOf(
    "0", "SECTION", "2", "ENTITIES", *entities.flatMap { it.toList() }.toTypedArray(), "0", "ENDSEC", "0", "EOF",
  )

  @Test
  fun `XLINE - сплошная прямая через всю сцену в обе стороны`() {
    val g = readGeometry(*doc(square, xline("XLINE")))
    val batch = g.lines.single { it.unbounded }
    assertEquals("X", batch.layer)
    val c = batch.coords
    // куски идут подряд: конец одного — начало следующего, все на прямой y = x
    for (s in 0 until batch.segmentCount) {
      assertEquals(c[s * 6], c[s * 6 + 1], 1e-9)
      assertEquals(c[s * 6 + 3], c[s * 6 + 4], 1e-9)
      if (s > 0) assertEquals(c[s * 6 - 3], c[s * 6], 1e-9)
    }
    // диагональ квадрата ≈ 14: концы далеко за его углами (0, 0) и (10, 10)
    assertTrue(c.first() < -10 && c[c.size - 3] > 20, "${c.first()} … ${c[c.size - 3]}")
  }

  @Test
  fun `RAY - только в одну сторону от точки`() {
    val g = readGeometry(*doc(square, xline("RAY")))
    val c = g.lines.single { it.unbounded }.coords
    assertEquals(0.0, c[0]); assertEquals(0.0, c[1])
    assertTrue((0 until c.size / 3).all { c[it * 3] >= 0.0 })
    assertTrue(c[c.size - 3] > 20 && c[c.size - 2] > 20)
  }

  @Test
  fun `XLINE и RAY не меняют габариты сцены и вид камеры`() {
    val without = buildRenderScene(readGeometry(*doc(square)))
    val with = buildRenderScene(readGeometry(*doc(square, xline("XLINE"), xline("RAY"))))
    assertEquals(without.originX, with.originX)
    assertEquals(without.originY, with.originY)
    assertEquals(without.halfX, with.halfX)
    assertEquals(without.halfZ, with.halfZ)
    assertEquals(without.lines.size + 1, with.lines.size)
  }

  @Test
  fun `обычные линии не помечены, нулевое направление пропускается`() {
    val zero = arrayOf("0", "XLINE", "8", "X", "10", "0", "20", "0", "30", "0", "11", "0", "21", "0", "31", "0")
    val g = readGeometry(*doc(square, zero))
    assertFalse(g.lines.any { it.unbounded })
    assertEquals(4, g.lines.sumOf { it.segmentCount })
  }

  @Test
  fun `файл только с XLINE не пустой`() {
    val scene = buildRenderScene(readGeometry(*doc(xline("XLINE"))))
    assertEquals(1, scene.lines.size)
  }
}
