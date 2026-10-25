package dev.iskandern.kotin

import dev.iskandern.kotin.dxf.DxfDocumentReader
import dev.iskandern.kotin.model.FillBatch
import dev.iskandern.kotin.dxf.DxfFlattener
import dev.iskandern.kotin.io.asByteInput
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DxfPolylineWidthTest {

  private fun entities(vararg body: String) = arrayOf("0", "SECTION", "2", "ENTITIES", *body, "0", "ENDSEC", "0", "EOF")

  // площадь всех треугольников заливки (в плоскости XY)
  private fun area(fill: FillBatch): Double {
    val c = fill.coords
    var sum = 0.0
    for (t in 0 until fill.triangleCount) {
      val p = t * 9
      sum += abs((c[p + 3] - c[p]) * (c[p + 7] - c[p + 1]) - (c[p + 4] - c[p + 1]) * (c[p + 6] - c[p])) / 2
    }
    return sum
  }

  private fun points(fill: FillBatch) = (0 until fill.coords.size / 3).map { fill.coords[it * 3] to fill.coords[it * 3 + 1] }

  @Test
  fun `постоянная ширина 43 - два треугольника шириной 2, без линии`() {
    val geometry = readGeometry(*entities(
      "0", "LWPOLYLINE", "8", "W", "90", "2", "70", "0", "43", "2", "10", "0", "20", "0", "10", "10", "20", "0",
    ))
    val fill = geometry.fills.single()
    assertEquals(2, fill.triangleCount)
    assertTrue(fill.flat)
    assertEquals(setOf(0.0 to 1.0, 0.0 to -1.0, 10.0 to 1.0, 10.0 to -1.0), points(fill).toSet())
    assertEquals(20.0, area(fill), 1e-9)
    assertTrue(geometry.lines.isEmpty())
  }

  @Test
  fun `ширина 0 в начале и 4 в конце - один треугольник-стрелка`() {
    val geometry = readGeometry(*entities(
      "0", "LWPOLYLINE", "8", "W", "90", "2", "70", "0", "10", "0", "20", "0", "40", "0", "41", "4", "10", "10", "20", "0",
    ))
    val fill = geometry.fills.single()
    assertEquals(1, fill.triangleCount)
    assertEquals(setOf(0.0 to 0.0, 10.0 to 2.0, 10.0 to -2.0), points(fill).toSet())
  }

  @Test
  fun `стрелка - древко и голова на одной прямой не срезают друг друга`() {
    val geometry = readGeometry(*entities(
      "0", "LWPOLYLINE", "8", "W", "90", "3", "70", "0",
      "10", "0", "20", "0", "40", "0.4", "41", "0.4", "10", "10", "20", "0", "40", "3", "41", "0", "10", "14", "20", "0",
    ))
    val fill = geometry.fills.single()
    // древко 10 × 0,4 и голова 4 × 3 / 2
    assertEquals(3, fill.triangleCount)
    assertEquals(10.0, area(fill), 1e-9)
    assertTrue((10.0 to 1.5) in points(fill).toSet())
  }

  @Test
  fun `замкнутая полилиния с шириной - стыки без щелей`() {
    // квадрат 10×10 шириной 1: рамка от −0,5 до 10,5, площадь 11² − 9² = 40 ровно — ни щелей, ни перекрытий
    val geometry = readGeometry(*entities(
      "0", "LWPOLYLINE", "8", "W", "90", "4", "70", "1", "43", "1",
      "10", "0", "20", "0", "10", "10", "20", "0", "10", "10", "20", "10", "10", "0", "20", "10",
    ))
    val fill = geometry.fills.single()
    assertEquals(8, fill.triangleCount)
    assertEquals(40.0, area(fill), 1e-9)
    val corners = points(fill).toSet()
    for (corner in listOf(-0.5 to -0.5, 10.5 to -0.5, 10.5 to 10.5, -0.5 to 10.5)) assertTrue(corner in corners, "$corner")
  }

  @Test
  fun `острый угол стыкуется скосом`() {
    // разворот почти назад: срез по биссектрисе ушёл бы далеко, вместо него — треугольник в вершине
    val geometry = readGeometry(*entities(
      "0", "LWPOLYLINE", "8", "W", "90", "3", "70", "0", "43", "1",
      "10", "0", "20", "0", "10", "10", "20", "0", "10", "0", "20", "1",
    ))
    val fill = geometry.fills.single()
    val far = points(fill).maxOf { it.first }
    assertTrue(far <= 10.5 + 1e-9, "far=$far")
    assertTrue(fill.triangleCount >= 5)
  }

  @Test
  fun `дуговой сегмент с шириной - полоса вдоль дуги`() {
    // bulge = 1: полуокружность радиуса 1 с центром (1, 0), ширина 0,5 — кольцо от 0,75 до 1,25
    val geometry = readGeometry(*entities(
      "0", "LWPOLYLINE", "8", "W", "90", "2", "70", "0", "43", "0.5", "10", "0", "20", "0", "42", "1", "10", "2", "20", "0",
    ))
    val fill = geometry.fills.single()
    assertTrue(fill.triangleCount > 20)
    for ((x, y) in points(fill)) {
      val r = hypot(x - 1, y)
      assertTrue(abs(r - 0.75) < 1e-9 || abs(r - 1.25) < 1e-9, "r=$r")
    }
    // против часовой от (0, 0) — через низ
    assertTrue(points(fill).all { it.second <= 1e-9 })
    assertEquals(PI / 2, area(fill), 0.01)
  }

  @Test
  fun `дуга с меняющейся шириной`() {
    val geometry = readGeometry(*entities(
      "0", "LWPOLYLINE", "8", "W", "90", "2", "70", "0", "10", "0", "20", "0", "40", "0", "41", "1", "42", "1", "10", "2", "20", "0",
    ))
    val fill = geometry.fills.single()
    // в начале острие, в конце — ширина 1: от 0,5 до 1,5 от центра
    val ends = points(fill).filter { abs(it.second) < 1e-9 }.map { it.first }.toSet()
    assertTrue(0.0 in ends && 2.5 in ends && 1.5 in ends, "$ends")
  }

  @Test
  fun `сегменты без ширины остаются линиями`() {
    val geometry = readGeometry(*entities(
      "0", "LWPOLYLINE", "8", "W", "90", "3", "70", "0",
      "10", "0", "20", "0", "40", "2", "41", "2", "10", "10", "20", "0", "40", "0", "41", "0", "10", "10", "20", "10",
    ))
    assertEquals(2, geometry.fills.single().triangleCount)
    val line = geometry.lines.single()
    assertEquals(1, line.segmentCount)
    assertEquals(listOf(10.0, 0.0, 0.0, 10.0, 10.0, 0.0), line.coords.toList())
  }

  @Test
  fun `POLYLINE - ширина заголовка по умолчанию, своя у вершины`() {
    val geometry = readGeometry(*entities(
      "0", "POLYLINE", "8", "W", "66", "1", "70", "0", "40", "2", "41", "2",
      "0", "VERTEX", "8", "W", "10", "0", "20", "0",
      "0", "VERTEX", "8", "W", "10", "10", "20", "0", "40", "0", "41", "4",
      "0", "VERTEX", "8", "W", "10", "20", "20", "0",
      "0", "SEQEND",
    ))
    val fill = geometry.fills.single()
    // первый сегмент 2×10 (2 треугольника, площадь 20), второй — стрелка 0→4 (1 треугольник, площадь 20)
    assertEquals(3, fill.triangleCount)
    assertEquals(40.0, area(fill), 1e-9)
    assertTrue(geometry.lines.isEmpty())
  }

  @Test
  fun `3D-полилиния ширину не получает`() {
    val geometry = readGeometry(*entities(
      "0", "POLYLINE", "8", "W", "66", "1", "70", "8", "40", "2", "41", "2",
      "0", "VERTEX", "8", "W", "70", "32", "10", "0", "20", "0", "30", "0",
      "0", "VERTEX", "8", "W", "70", "32", "10", "10", "20", "0", "30", "5",
      "0", "SEQEND",
    ))
    assertTrue(geometry.fills.isEmpty())
    assertEquals(1, geometry.lines.single().segmentCount)
  }

  @Test
  fun `ширина в блоке масштабируется вхождением, OCS учитывается`() {
    val geometry = readGeometry(
      "0", "SECTION", "2", "BLOCKS",
      "0", "BLOCK", "2", "B", "10", "0", "20", "0", "30", "0",
      "0", "LWPOLYLINE", "8", "0", "90", "2", "70", "0", "43", "1", "10", "0", "20", "0", "10", "5", "20", "0",
      "0", "ENDBLK", "0", "ENDSEC",
      *entities(
        "0", "INSERT", "8", "L", "2", "B", "10", "100", "20", "0", "30", "0", "41", "2", "42", "2", "43", "2",
        // нормаль (0, 0, −1): x зеркалится
        "0", "LWPOLYLINE", "8", "M", "90", "2", "70", "0", "43", "1", "38", "3",
        "10", "0", "20", "0", "10", "5", "20", "0", "210", "0", "220", "0", "230", "-1",
      ),
    )
    val scaled = geometry.fills.single { it.layer == "L" }
    assertEquals(setOf(100.0 to 1.0, 100.0 to -1.0, 110.0 to 1.0, 110.0 to -1.0), points(scaled).toSet())
    val mirrored = geometry.fills.single { it.layer == "M" }
    assertTrue(points(mirrored).all { it.first <= 0.0 })
    assertEquals(5.0, area(mirrored), 1e-9)
    // отметка 38 в OCS с нормалью вниз — z = −3
    assertTrue((0 until mirrored.coords.size / 3).all { mirrored.coords[it * 3 + 2] == -3.0 })
  }

  @Test
  fun `треугольники полос считаются в лимит`() {
    val text = entities(
      "0", "LWPOLYLINE", "8", "W", "90", "2", "70", "0", "43", "2", "10", "0", "20", "0", "10", "10", "20", "0",
      "0", "LWPOLYLINE", "8", "W", "90", "2", "70", "0", "43", "2", "10", "0", "20", "5", "10", "10", "20", "5",
    ).joinToString("\n", postfix = "\n")
    val geometry = DxfFlattener(DxfDocumentReader(text.byteInputStream().asByteInput()).read(), maxSegments = 3).flatten()
    assertTrue(geometry.truncated)
    assertEquals(2, geometry.fills.sumOf { it.triangleCount })
  }
}
