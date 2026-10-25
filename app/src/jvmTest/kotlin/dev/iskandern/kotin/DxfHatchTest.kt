package dev.iskandern.kotin

import dev.iskandern.kotin.dxf.DxfColors
import dev.iskandern.kotin.model.SceneGeometry
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sqrt
import kotlin.system.measureNanoTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DxfHatchTest {

  private fun entities(vararg body: String) = arrayOf("0", "SECTION", "2", "ENTITIES", *body, "0", "ENDSEC", "0", "EOF")

  /** Заголовок HATCH до контуров. */
  private fun hatch(layer: String = "H", color: String = "1", paths: Array<String>, style: String = "0") = arrayOf(
    "0", "HATCH", "8", layer, "62", color, "10", "0", "20", "0", "30", "0", "210", "0", "220", "0", "230", "1",
    "2", "SOLID", "70", "1", "71", "0", "91", (paths.count { it == "92" }).toString(), *paths, "75", style, "76", "1", "98", "0",
  )

  /** Контур-ломаная: вершины (x, y [, bulge]). */
  private fun polyPath(closed: Boolean, vararg v: Double): Array<String> {
    val out = mutableListOf("92", "3", "72", "0", "73", if (closed) "1" else "0", "93", (v.size / 2).toString())
    for (i in 0 until v.size / 2) out += listOf("10", v[i * 2].toString(), "20", v[i * 2 + 1].toString())
    out += listOf("97", "0")
    return out.toTypedArray()
  }

  /** HATCH с паттерном: [lines] — линии паттерна ([patternLine]); после них затравка (10/20 не должны мешать). */
  private fun patternHatch(name: String, paths: Array<String>, lines: Array<String>, color: String = "1", scale: String = "1") = arrayOf(
    "0", "HATCH", "8", "H", "62", color, "10", "0", "20", "0", "30", "0", "210", "0", "220", "0", "230", "1",
    "2", name, "70", "0", "71", "0", "91", (paths.count { it == "92" }).toString(), *paths,
    "75", "0", "76", "1", "52", "0", "41", scale, "77", "0", "78", (lines.count { it == "53" }).toString(), *lines,
    "98", "1", "10", "0.5", "20", "0.5",
  )

  private fun patternLine(angle: Double, offsetX: Double, offsetY: Double, vararg dashes: Double) =
    arrayOf("53", "$angle", "43", "0", "44", "0", "45", "$offsetX", "46", "$offsetY", "79", "${dashes.size}") +
      dashes.flatMap { listOf("49", "$it") }

  /** Отрезки пакетов линий: x1 y1 x2 y2 (z отброшен). */
  private fun SceneGeometry.segments2d(): List<DoubleArray> = lines.flatMap { batch ->
    (0 until batch.segmentCount).map { i -> doubleArrayOf(batch.coords[i * 6], batch.coords[i * 6 + 1], batch.coords[i * 6 + 3], batch.coords[i * 6 + 4]) }
  }

  private fun List<DoubleArray>.totalLength() = sumOf { hypot(it[2] - it[0], it[3] - it[1]) }

  private fun lineEdge(x1: Double, y1: Double, x2: Double, y2: Double) =
    arrayOf("72", "1", "10", "$x1", "20", "$y1", "11", "$x2", "21", "$y2")

  private fun area(fill: DoubleArray): Double {
    var sum = 0.0
    for (t in 0 until fill.size / 9) {
      val o = t * 9
      sum += abs((fill[o + 3] - fill[o]) * (fill[o + 7] - fill[o + 1]) - (fill[o + 6] - fill[o]) * (fill[o + 4] - fill[o + 1])) / 2
    }
    return sum
  }

  private fun SceneGeometry.fillArea() = fills.sumOf { area(it.coords) }

  @Test
  fun `стиль заливки 75 - остров в отверстии`() {
    // квадрат 10×10, в нём отверстие 6×6, в нём остров 2×2
    val paths = polyPath(true, 0.0, 0.0, 10.0, 0.0, 10.0, 10.0, 0.0, 10.0) +
      polyPath(true, 2.0, 2.0, 8.0, 2.0, 8.0, 8.0, 2.0, 8.0) +
      polyPath(true, 4.0, 4.0, 6.0, 4.0, 6.0, 6.0, 4.0, 6.0)
    fun fill(style: String) = readGeometry(*entities(*hatch(paths = paths, style = style))).fillArea()
    assertEquals(100.0 - 36.0 + 4.0, fill("0"), 1e-6)
    assertEquals(100.0 - 36.0, fill("1"), 1e-6)
    assertEquals(100.0, fill("2"), 1e-6)
  }

  @Test
  fun `ребро-сплайн по опорным точкам — дуга, а не ломаная`() {
    // полукруг радиуса 10: сплайн через 5 опорных точек с касательными, замыкает диаметр
    val spline = mutableListOf("72", "4", "94", "3", "73", "0", "74", "0", "95", "0", "96", "0", "97", "5")
    for (k in 0..4) {
      val a = PI * k / 4
      spline += listOf("11", "${10 * kotlin.math.cos(a)}", "21", "${10 * kotlin.math.sin(a)}")
    }
    spline += listOf("12", "0", "22", "1", "13", "0", "23", "-1")
    val path = arrayOf("92", "1", "93", "2", *spline.toTypedArray(), *lineEdge(-10.0, 0.0, 10.0, 0.0), "97", "0")
    val g = readGeometry(*entities(*hatch(paths = path)))
    // ломаная через опорные точки дала бы 141,4; полукруг — 157,1
    assertEquals(PI * 100 / 2, g.fillArea(), PI * 100 / 2 * 0.01)
  }

  @Test
  fun `квадрат с отверстием`() {
    val g = readGeometry(*entities(*hatch(
      paths = polyPath(true, 0.0, 0.0, 4.0, 0.0, 4.0, 2.0, 0.0, 2.0) + polyPath(true, 1.0, 0.5, 3.0, 0.5, 3.0, 1.5, 1.0, 1.5),
    )))
    val fill = g.fills.single()
    assertEquals(DxfColors.aciToRgb(1), fill.color)
    assertTrue(fill.flat)
    assertEquals(8.0 - 2.0, area(fill.coords), 1e-9)
    // ни один треугольник не попадает в отверстие
    for (t in 0 until fill.triangleCount) {
      val cx = (fill.coords[t * 9] + fill.coords[t * 9 + 3] + fill.coords[t * 9 + 6]) / 3
      val cy = (fill.coords[t * 9 + 1] + fill.coords[t * 9 + 4] + fill.coords[t * 9 + 7]) / 3
      assertTrue(!(cx > 1 && cx < 3 && cy > 0.5 && cy < 1.5), "центр треугольника в отверстии: $cx, $cy")
    }
  }

  @Test
  fun `рамка, отверстие и остров заливаются по чётности вложенности`() {
    val g = readGeometry(*entities(*hatch(
      paths = polyPath(true, 0.0, 0.0, 10.0, 0.0, 10.0, 10.0, 0.0, 10.0) +
        polyPath(true, 2.0, 2.0, 8.0, 2.0, 8.0, 8.0, 2.0, 8.0) +
        polyPath(true, 4.0, 4.0, 6.0, 4.0, 6.0, 6.0, 4.0, 6.0),
    )))
    assertEquals(100.0 - 36.0 + 4.0, g.fillArea(), 1e-9)
  }

  @Test
  fun `полукруг с bulge`() {
    // от (0, 0) до (2, 0) с bulge 1 — полукруг радиуса 1
    val path = arrayOf(
      "92", "3", "72", "1", "73", "1", "93", "2", "10", "0", "20", "0", "42", "1", "10", "2", "20", "0", "97", "0",
    )
    val g = readGeometry(*entities(*hatch(paths = path)))
    assertEquals(PI / 2, g.fillArea(), PI / 2 * 0.01)
  }

  @Test
  fun `петля из четырёх LINE`() {
    val path = arrayOf("92", "1", "93", "4") +
      lineEdge(0.0, 0.0, 3.0, 0.0) + lineEdge(3.0, 0.0, 3.0, 2.0) + lineEdge(3.0, 2.0, 0.0, 2.0) + lineEdge(0.0, 2.0, 0.0, 0.0) +
      arrayOf("97", "0")
    val g = readGeometry(*entities(*hatch(paths = path)))
    assertEquals(6.0, g.fillArea(), 1e-9)
  }

  @Test
  fun `рёбра в произвольном порядке и направлении стыкуются`() {
    val path = arrayOf("92", "1", "93", "4") +
      lineEdge(3.0, 0.0, 0.0, 0.0) + lineEdge(3.0, 0.0, 3.0, 2.0) + lineEdge(0.0, 2.0, 3.0, 2.0) + lineEdge(0.0, 2.0, 0.0, 0.0) +
      arrayOf("97", "0")
    assertEquals(6.0, readGeometry(*entities(*hatch(paths = path))).fillArea(), 1e-9)
  }

  @Test
  fun `круг из одной дуги и эллипс`() {
    val circle = arrayOf("92", "1", "93", "1", "72", "2", "10", "0", "20", "0", "40", "5", "50", "0", "51", "360", "73", "1", "97", "0")
    assertEquals(PI * 25, readGeometry(*entities(*hatch(paths = circle))).fillArea(), PI * 25 * 0.01)
    // эллипс: большая полуось 4 по X, отношение 0.5 → площадь π·4·2
    val ellipse = arrayOf("92", "1", "93", "1", "72", "3", "10", "0", "20", "0", "11", "4", "21", "0", "40", "0.5", "50", "0", "51", "360", "73", "1", "97", "0")
    assertEquals(PI * 8, readGeometry(*entities(*hatch(paths = ellipse))).fillArea(), PI * 8 * 0.01)
  }

  @Test
  fun `дуга по часовой стрелке даёт ту же половину круга`() {
    // нижняя половина круга: дуга против часовой 180→360 и отрезок обратно; то же — по часовой 180→0 (углы как в DXF)
    val ccw = arrayOf("92", "1", "93", "2", "72", "2", "10", "0", "20", "0", "40", "1", "50", "180", "51", "360", "73", "1") +
      lineEdge(1.0, 0.0, -1.0, 0.0) + arrayOf("97", "0")
    assertEquals(PI / 2, readGeometry(*entities(*hatch(paths = ccw))).fillArea(), PI / 2 * 0.01)
  }

  @Test
  fun `незамкнутая граница пропускается без падения`() {
    val open = arrayOf("92", "1", "93", "3") +
      lineEdge(0.0, 0.0, 3.0, 0.0) + lineEdge(3.0, 0.0, 3.0, 2.0) + lineEdge(3.0, 2.0, 0.0, 2.0) + arrayOf("97", "0")
    val g = readGeometry(*entities(*hatch(paths = open + polyPath(false, 0.0, 0.0, 1.0, 0.0, 1.0, 1.0))))
    assertTrue(g.fills.isEmpty())
  }

  @Test
  fun `цвет по слою и хэтч в блоке с поворотом и масштабом`() {
    val island = polyPath(true, 0.0, 0.0, 4.0, 0.0, 4.0, 4.0, 0.0, 4.0) + polyPath(true, 1.0, 1.0, 3.0, 1.0, 3.0, 3.0, 1.0, 3.0)
    val g = readGeometry(
      "0", "SECTION", "2", "TABLES", "0", "TABLE", "2", "LAYER", "0", "LAYER", "2", "ZONE", "70", "0", "62", "5", "0", "ENDTAB", "0", "ENDSEC",
      "0", "SECTION", "2", "BLOCKS", "0", "BLOCK", "2", "B", "10", "0", "20", "0", "30", "0",
      *hatch(layer = "0", color = "256", paths = island),
      "0", "ENDBLK", "0", "ENDSEC",
      "0", "SECTION", "2", "ENTITIES",
      "0", "INSERT", "8", "ZONE", "2", "B", "10", "100", "20", "200", "30", "0", "41", "2", "42", "2", "43", "1", "50", "30",
      "0", "ENDSEC", "0", "EOF",
    )
    val fill = g.fills.single()
    assertEquals("ZONE", fill.layer)
    assertEquals(DxfColors.aciToRgb(5), fill.color)
    // масштаб 2 по обеим осям: площадь × 4, поворот площадь не меняет
    assertEquals((16.0 - 4.0) * 4, area(fill.coords), 1e-9)
    assertTrue((0 until fill.coords.size / 3).all { fill.coords[it * 3] > 90 && fill.coords[it * 3 + 1] > 190 })
  }

  @Test
  fun `штриховка 45° в квадрате — отрезки внутри, длина = площадь ÷ шаг`() {
    val square = polyPath(true, 0.0, 0.0, 10.0, 0.0, 10.0, 10.0, 0.0, 10.0)
    // шаг 0,5 поперёк линий под 45°
    val g = readGeometry(*entities(*patternHatch("ANSI31", square, patternLine(45.0, -0.5 / sqrt(2.0), 0.5 / sqrt(2.0)))))
    assertTrue(g.fills.isEmpty())
    val segments = g.segments2d()
    assertEquals(DxfColors.aciToRgb(1), g.lines.single().color)
    for (s in segments) for (v in s) assertTrue(v > -1e-9 && v < 10 + 1e-9, "отрезок вне квадрата: ${s.toList()}")
    for (s in segments) assertEquals(1.0, (s[3] - s[1]) / (s[2] - s[0]), 1e-9)
    assertEquals(100 / 0.5, segments.totalLength(), 100 / 0.5 * 0.02)
  }

  @Test
  fun `штриховка не заходит в отверстие и остров снова штрихуется`() {
    val paths = polyPath(true, 0.0, 0.0, 10.0, 0.0, 10.0, 10.0, 0.0, 10.0) +
      polyPath(true, 2.0, 2.0, 8.0, 2.0, 8.0, 8.0, 2.0, 8.0) +
      polyPath(true, 4.0, 4.0, 6.0, 4.0, 6.0, 6.0, 4.0, 6.0)
    // горизонтальные линии с шагом 0,25, сдвинутые от вершин контура
    val g = readGeometry(*entities(*patternHatch("USER", paths, patternLine(0.0, 0.0, 0.25))))
    val segments = g.segments2d()
    for (s in segments) {
      val mx = (s[0] + s[2]) / 2
      val my = (s[1] + s[3]) / 2
      // кромка острова (y = 4) — его часть
      val inHole = mx > 2 && mx < 8 && my > 2 && my < 8 && !(mx > 4 && mx < 6 && my >= 4 && my <= 6)
      assertTrue(!inHole, "отрезок в отверстии: ${s.toList()}")
    }
    assertEquals((100.0 - 36.0 + 4.0) / 0.25, segments.totalLength(), 10.0)
  }

  @Test
  fun `штрихи паттерна — доля длины по периоду, фаза от начала линии`() {
    val square = polyPath(true, 0.0, 0.0, 10.0, 0.0, 10.0, 10.0, 0.0, 10.0)
    // горизонтальные линии через 1, штрих 0,75 и пробел 0,25 от x = 0
    val g = readGeometry(*entities(*patternHatch("DASH", square, patternLine(0.0, 0.0, 1.0, 0.75, -0.25))))
    val segments = g.segments2d()
    // линии y = 0…9 (верхняя кромка y = 10 — снаружи по правилу полуинтервала)
    assertEquals(10 * 10 * 0.75, segments.totalLength(), 1e-6)
    for (s in segments) {
      assertEquals(0.0, s[0] - floor(s[0]), 1e-9)
      assertEquals(0.75, s[2] - s[0], 1e-9)
    }
  }

  @Test
  fun `частая штриховка заливается средним цветом, сплошная по имени SOLID — цветом хэтча`() {
    val square = polyPath(true, 0.0, 0.0, 10.0, 0.0, 10.0, 10.0, 0.0, 10.0)
    // шаг 0,001 при сцене 14 — меньше порога плотности
    val dense = readGeometry(*entities(*patternHatch("ANSI31", square, patternLine(45.0, -0.001 / sqrt(2.0), 0.001 / sqrt(2.0)))))
    assertTrue(dense.lines.isEmpty())
    assertEquals(100.0, dense.fillArea(), 1e-9)
    assertEquals(0x800000, dense.fills.single().color)

    val solid = readGeometry(*entities(*patternHatch("SOLID", square, patternLine(45.0, -0.1, 0.1)))).fills.single()
    assertEquals(DxfColors.aciToRgb(1), solid.color)
  }

  @Test
  fun `стандартный паттерн без линий в файле берётся из таблицы, неизвестный — средним цветом`() {
    val square = polyPath(true, 0.0, 0.0, 100.0, 0.0, 100.0, 100.0, 0.0, 100.0)
    // ANSI31 в acadiso — 45°, шаг 3,175; масштаб 2 → 6,35
    val g = readGeometry(*entities(*patternHatch("ANSI31", square, emptyArray(), scale = "2")))
    val segments = g.segments2d()
    assertTrue(segments.isNotEmpty())
    for (s in segments) assertEquals(1.0, (s[3] - s[1]) / (s[2] - s[0]), 1e-9)
    assertEquals(10_000 / 6.35, segments.totalLength(), 10_000 / 6.35 * 0.02)

    val unknown = readGeometry(*entities(*patternHatch("NOSUCH", square, emptyArray())))
    assertTrue(unknown.lines.isEmpty())
    assertEquals(10_000.0, unknown.fillArea(), 1e-6)
  }

  @Test
  fun `линейный градиент — цвета по вершинам от первого ко второму`() {
    val box = polyPath(true, 0.0, 0.0, 10.0, 0.0, 10.0, 4.0, 0.0, 4.0)
    fun gradient(angle: String, vararg colors: String) = readGeometry(*entities(
      "0", "HATCH", "8", "H", "62", "7", "10", "0", "20", "0", "30", "0", "210", "0", "220", "0", "230", "1",
      "2", "SOLID", "70", "1", "71", "0", "91", "1", *box, "75", "0", "76", "1", "98", "0",
      "450", "1", "451", "0", "460", angle, "461", "0", "452", if (colors.size == 1) "1" else "0", "462", "1", "453", "${colors.size}",
      *colors.flatMap { listOf("463", "0", "421", it) }.toTypedArray(), "470", "LINEAR",
    )).fills.single()

    // 0°: красный слева, синий справа
    val horizontal = gradient("0", "${0xFF0000}", "${0x0000FF}")
    val colors = horizontal.colors!!
    assertEquals(40.0, area(horizontal.coords), 1e-9)
    for (v in colors.indices) {
      val x = horizontal.coords[v * 3]
      assertEquals(if (x == 0.0) 0xFF0000 else 0x0000FF, colors[v])
    }
    // 90°: снизу первый цвет, сверху второй
    val vertical = gradient("${PI / 2}", "${0x00A000}", "${0xFFFF00}")
    for (v in vertical.colors!!.indices) {
      assertEquals(if (vertical.coords[v * 3 + 1] == 0.0) 0x00A000 else 0xFFFF00, vertical.colors!![v])
    }
    // одноцветный: второй цвет — серый по оттенку (462 = 1 → белый)
    val single = gradient("0", "${0x0000FF}")
    assertTrue(0xFFFFFF in single.colors!!)
  }

  @Test
  fun `сферический градиент делится сеткой, в центре второй цвет`() {
    val box = polyPath(true, 0.0, 0.0, 10.0, 0.0, 10.0, 10.0, 0.0, 10.0)
    val fill = readGeometry(*entities(
      "0", "HATCH", "8", "H", "10", "0", "20", "0", "30", "0", "210", "0", "220", "0", "230", "1",
      "2", "SOLID", "70", "1", "71", "0", "91", "1", *box, "75", "0", "76", "1", "98", "0",
      "450", "1", "451", "0", "460", "0", "461", "0", "452", "0", "462", "0", "453", "2",
      "463", "0", "63", "1", "463", "1", "63", "5", "470", "SPHERICAL",
    )).fills.single()
    assertTrue(fill.triangleCount > 100)
    assertEquals(100.0, area(fill.coords), 1e-9)
    val colors = fill.colors!!
    val center = colors.indices.minBy { hypot(fill.coords[it * 3] - 5, fill.coords[it * 3 + 1] - 5) }
    assertEquals(0x0000FF, colors[center])
    for (v in colors.indices) {
      val x = fill.coords[v * 3]
      val y = fill.coords[v * 3 + 1]
      if ((x == 0.0 || x == 10.0) && (y == 0.0 || y == 10.0)) assertEquals(0xFF0000, colors[v])
    }
  }

  @Test
  fun `файл задачи 15 — штриховки, градиенты и хэтч в блоке в пределах лимита`() {
    val g = readGeometry(File(testDxfDir, "hatch-patterns.dxf"))
    val segments = g.lines.sumOf { it.segmentCount }
    println("hatch-patterns.dxf: отрезков $segments, заливок ${g.fills.size}")
    assertTrue(!g.truncated)
    assertTrue(segments in 100..100_000, "$segments")
    // два градиента (один пакет слоя «0») и заливки SOLID и частой штриховки
    assertEquals(4, g.fills.filter { it.colors != null }.sumOf { it.triangleCount })
    assertTrue(g.fills.count { it.colors == null } >= 2)
  }

  /** MPOLYGON как пишет ezdxf: 70 — версия, 71 — сплошная заливка, контуры 92/73/72/93, затем 76 и цвет заливки 63. */
  private fun mpolygon(paths: Array<String>, solid: Boolean = true, fillColor: String? = "3", pattern: Array<String> = emptyArray()) = arrayOf(
    "0", "MPOLYGON", "8", "M", "62", "1", "100", "AcDbMPolygon", "70", "1", "10", "0", "20", "0", "30", "0",
    "210", "0", "220", "0", "230", "1", "2", if (solid) "SOLID" else "USER", "71", if (solid) "1" else "0",
    "91", (paths.count { it == "92" }).toString(), *paths, "76", "1", *pattern,
    *(if (fillColor != null) arrayOf("63", fillColor) else emptyArray()),
    "11", "0", "21", "0", "99", "0", "450", "0", "451", "0", "460", "0", "461", "0", "452", "0", "462", "0", "453", "0", "470", "",
  )

  private fun mpolygonPath(vararg v: Double): Array<String> {
    val out = mutableListOf("92", "3", "73", "1", "72", "0", "93", (v.size / 2).toString())
    for (i in 0 until v.size / 2) out += listOf("10", v[i * 2].toString(), "20", v[i * 2 + 1].toString())
    return out.toTypedArray()
  }

  @Test
  fun `MPOLYGON — квадрат с отверстием, заливка с дыркой своим цветом и два контура`() {
    val g = readGeometry(*entities(*mpolygon(
      mpolygonPath(0.0, 0.0, 4.0, 0.0, 4.0, 4.0, 0.0, 4.0) + mpolygonPath(1.0, 1.0, 3.0, 1.0, 3.0, 3.0, 1.0, 3.0),
    )))
    val fill = g.fills.single()
    assertEquals(DxfColors.aciToRgb(3), fill.color)
    assertEquals(16.0 - 4.0, area(fill.coords), 1e-9)
    // контуры — линией цвета сущности: 4 + 4 стороны, периметр 16 + 8
    val outline = g.lines.single()
    assertEquals(DxfColors.aciToRgb(1), outline.color)
    assertEquals(8, outline.segmentCount)
    assertEquals(24.0, g.segments2d().totalLength(), 1e-9)
  }

  @Test
  fun `MPOLYGON без цвета заливки и со штриховкой`() {
    val square = mpolygonPath(0.0, 0.0, 4.0, 0.0, 4.0, 4.0, 0.0, 4.0)
    // без 63 заливка — цветом сущности
    val plain = readGeometry(*entities(*mpolygon(square, fillColor = null)))
    assertEquals(DxfColors.aciToRgb(1), plain.fills.single().color)
    // штриховка горизонталями через 1 цветом заливки, контур — цветом сущности
    val lines = arrayOf("52", "0", "41", "1", "77", "0", "78", "1") + patternLine(0.0, 0.0, 1.0)
    val hatched = readGeometry(*entities(*mpolygon(square, solid = false, fillColor = "5", pattern = lines)))
    assertTrue(hatched.fills.isEmpty())
    assertEquals(setOf(DxfColors.aciToRgb(1), DxfColors.aciToRgb(5)), hatched.lines.map { it.color }.toSet())
    assertTrue(hatched.lines.single { it.color == DxfColors.aciToRgb(5) }.segmentCount >= 3)
  }

  @Test
  fun `файл задачи 39 — MPOLYGON`() {
    val g = readGeometry(File(testDxfDir, "mpolygon.dxf"))
    assertTrue(!g.truncated)
    // зелёная заливка с отверстием 10×10 − 4×4, синий треугольник, красная в блоке (масштаб 2: (8 − 2) × 4)
    assertEquals(100.0 - 16.0, area(g.fills.single { it.color == DxfColors.aciToRgb(3) }.coords), 1e-6)
    assertEquals(40.0, area(g.fills.single { it.color == DxfColors.aciToRgb(5) }.coords), 1e-6)
    assertEquals(24.0, area(g.fills.single { it.color == DxfColors.aciToRgb(1) }.coords), 1e-6)
    // контур по слою Parcels (пурпурный) и штриховка жёлтого
    assertTrue(g.lines.any { it.color == DxfColors.aciToRgb(6) })
    assertTrue(g.lines.filter { it.color == DxfColors.aciToRgb(2) }.sumOf { it.segmentCount } > 10)
  }

  @Test
  fun `тестовый файл с хэтчами читается, 400 малых хэтчей быстро`() {
    val file = File(testDxfDir, "hatch-solid.dxf")
    var g: SceneGeometry? = null
    readGeometry(file) // прогрев
    val ms = measureNanoTime { g = readGeometry(file) } / 1_000_000
    println("hatch-solid.dxf: $ms мс, заливок ${g!!.fills.sumOf { it.triangleCount }} треугольников")
    assertTrue(g!!.fills.isNotEmpty())
    assertTrue(ms < 2000, "$ms мс")
  }
}
