package dev.iskandern.kotin

import dev.iskandern.kotin.dxf.DxfColors
import dev.iskandern.kotin.model.LineBatch
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DxfMLineTest {

  private fun file(entities: List<String>, objects: List<String> = emptyList()) = arrayOf(
    "0", "SECTION", "2", "ENTITIES", *entities.toTypedArray(), "0", "ENDSEC",
    "0", "SECTION", "2", "OBJECTS", *objects.toTypedArray(), "0", "ENDSEC", "0", "EOF",
  )

  private fun style(handle: String, name: String, flags: Int, fillColor: Int, vararg elements: Pair<Double, Int>) = listOf(
    "0", "MLINESTYLE", "5", handle, "2", name, "70", "$flags", "62", "$fillColor", "71", "${elements.size}",
  ) + elements.flatMap { (offset, color) -> listOf("49", "$offset", "62", "$color", "6", "BYLAYER") }

  /** MLINE по вершинам (x, y); [params] — первый параметр каждого элемента в каждой вершине, null — без параметров. */
  private fun mline(
    styleName: String, handle: String?, vertices: List<Pair<Double, Double>>, elements: Int,
    params: ((Int, Int) -> Double)? = null, scale: Double = 1.0, flags: Int = 1,
  ): List<String> {
    val out = mutableListOf("0", "MLINE", "8", "M", "2", styleName)
    if (handle != null) out += listOf("340", handle)
    out += listOf("40", "$scale", "70", "1", "71", "$flags", "72", "${vertices.size}", "73", "$elements")
    for ((i, p) in vertices.withIndex()) {
      // ломаная вдоль X с изломом вверх: направление и скос считаются по соседям
      val next = vertices.getOrElse(i + 1) { p.first + (p.first - vertices[i - 1].first) to p.second + (p.second - vertices[i - 1].second) }
      val dx = next.first - p.first
      val dy = next.second - p.second
      val len = Math.hypot(dx, dy)
      val prev = vertices.getOrNull(i - 1)
      // скос — единичная биссектриса нормалей влево соседних сегментов, на концах — нормаль
      var mx = -dy / len
      var my = dx / len
      if (prev != null && i < vertices.size - 1) {
        val pl = Math.hypot(p.first - prev.first, p.second - prev.second)
        mx += -(p.second - prev.second) / pl
        my += (p.first - prev.first) / pl
        val ml = Math.hypot(mx, my)
        mx /= ml
        my /= ml
      }
      out += listOf("11", "${p.first}", "21", "${p.second}", "31", "0", "12", "${dx / len}", "22", "${dy / len}", "32", "0",
        "13", "$mx", "23", "$my", "33", "0")
      for (e in 0 until elements) {
        if (params == null) out += listOf("74", "0") else out += listOf("74", "2", "41", "${params(i, e)}", "41", "0")
        out += listOf("75", "0")
      }
    }
    return out
  }

  private val standard = style("A1", "Standard", 0, 256, 0.5 to 256, -0.5 to 256)

  private fun ys(batch: LineBatch) = (0 until batch.coords.size / 3).map { batch.coords[it * 3 + 1] }.toSet()

  @Test
  fun `STANDARD из 3 вершин - две ломаные по 2 отрезка на расстоянии 1`() {
    val offsets = doubleArrayOf(0.5, -0.5)
    val geometry = readGeometry(*file(
      mline("Standard", "A1", listOf(0.0 to 0.0, 10.0 to 0.0, 20.0 to 0.0), 2, params = { _, e -> offsets[e] }),
      standard,
    ))
    val batch = geometry.lines.single()
    assertEquals(4, batch.segmentCount)
    assertEquals(setOf(0.5, -0.5), ys(batch))
    assertTrue(geometry.fills.isEmpty())
  }

  @Test
  fun `без параметров вершин - смещения стиля на масштаб, без OBJECTS - стандартный стиль`() {
    val geometry = readGeometry(*file(mline("Standard", null, listOf(0.0 to 0.0, 10.0 to 0.0, 20.0 to 0.0), 2, scale = 4.0)))
    val batch = geometry.lines.single()
    assertEquals(4, batch.segmentCount)
    assertEquals(setOf(2.0, -2.0), ys(batch))
  }

  @Test
  fun `излом без параметров - элементы по скосу на том же расстоянии от оси`() {
    // уголок (0,0)–(10,0)–(10,10): внутренний элемент проходит через (9.5, 0.5), внешний — через (10.5, -0.5)
    val geometry = readGeometry(*file(mline("Standard", "A1", listOf(0.0 to 0.0, 10.0 to 0.0, 10.0 to 10.0), 2), standard))
    val c = geometry.lines.single().coords
    val points = (0 until c.size / 3).map { Math.round(c[it * 3] * 1e6) / 1e6 to Math.round(c[it * 3 + 1] * 1e6) / 1e6 }.toSet()
    assertTrue((9.5 to 0.5) in points, "$points")
    assertTrue((10.5 to -0.5) in points, "$points")
  }

  @Test
  fun `стиль по handle, цвета элементов, заливка и торец линией`() {
    // handle указывает на TRIPLE, имя в сущности — чужое: берётся по handle
    val triple = style("B2", "TRIPLE", 1 or 16, 2, 1.0 to 1, 0.0 to 3, -1.0 to 5)
    val geometry = readGeometry(*file(mline("Other", "B2", listOf(0.0 to 0.0, 10.0 to 0.0), 3), standard + triple))
    val colors = geometry.lines.map { it.color }.toSet()
    // три элемента своими цветами, торец — цветом мультилинии (белый: слой M без таблицы)
    assertEquals(setOf(DxfColors.aciToRgb(1), DxfColors.aciToRgb(3), DxfColors.aciToRgb(5), DxfColors.WHITE), colors)
    val red = geometry.lines.single { it.color == DxfColors.aciToRgb(1) }
    assertEquals(setOf(1.0), ys(red))
    val fill = geometry.fills.single()
    assertEquals(DxfColors.aciToRgb(2), fill.color)
    assertEquals(2, fill.triangleCount)
    // торец только в начале: отрезок от y = 1 до y = −1 при x = 0
    val cap = geometry.lines.single { it.color == DxfColors.WHITE }
    assertEquals(1, cap.segmentCount)
    assertEquals(0.0, cap.coords[0], 1e-9)
    assertEquals(0.0, cap.coords[3], 1e-9)
  }

  @Test
  fun `замкнутая - без торцов, элементы замкнуты`() {
    val square = style("C3", "SQ", 16 or 256 or 64 or 1024, 256, 0.5 to 256, -0.5 to 256)
    val geometry = readGeometry(*file(
      mline("SQ", "C3", listOf(0.0 to 0.0, 10.0 to 0.0, 10.0 to 10.0, 0.0 to 10.0), 2, flags = 1 or 2),
      square,
    ))
    // 2 элемента по 4 отрезка, торцов нет
    assertEquals(8, geometry.lines.sumOf { it.segmentCount })
  }

  @Test
  fun `тестовый файл задачи читается, торцы и заливка есть`() {
    val geometry = readGeometry(File(testDxfDir, "mline.dxf"))
    assertFalse(geometry.truncated)
    assertTrue(geometry.lines.sumOf { it.segmentCount } > 20)
    // жёлтая заливка TRIPLE: в модели и в блоке
    assertTrue(geometry.fills.any { it.color == DxfColors.aciToRgb(2) })
    assertTrue(geometry.layers.any { it.name == "Walls" })
  }
}
