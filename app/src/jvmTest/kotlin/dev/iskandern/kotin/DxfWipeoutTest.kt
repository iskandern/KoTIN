package dev.iskandern.kotin

import dev.iskandern.kotin.dxf.DxfColors
import dev.iskandern.kotin.model.SceneGeometry
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DxfWipeoutTest {

  private val red = DxfColors.aciToRgb(1)
  private val blue = DxfColors.aciToRgb(5)

  // WIPEOUT-квадрат 10 × 10 с углом в (5, 0), записанный как у ezdxf: U, V — по стороне, картинка 1 × 1
  private val wipeout = arrayOf(
    "0", "WIPEOUT", "8", "0",
    "10", "5", "20", "0", "30", "0", "11", "10", "21", "0", "31", "0", "12", "0", "22", "10", "32", "0",
    "13", "1", "23", "1", "71", "2", "91", "5",
    "14", "-0.5", "24", "0.5", "14", "0.5", "24", "0.5", "14", "0.5", "24", "-0.5", "14", "-0.5", "24", "-0.5",
    "14", "-0.5", "24", "0.5",
  )

  private fun line(color: Int, x1: Double, y1: Double, x2: Double, y2: Double) =
    arrayOf("0", "LINE", "8", "0", "62", "$color", "10", "$x1", "20", "$y1", "11", "$x2", "21", "$y2")

  private fun read(vararg entities: Array<String>, objects: Array<String> = emptyArray()): SceneGeometry = readGeometry(
    "0", "SECTION", "2", "ENTITIES", *entities.flatten().toTypedArray(), "0", "ENDSEC",
    *objects, "0", "EOF",
  )

  // отрезки цвета [color]: x1 y1 x2 y2
  private fun SceneGeometry.segments(color: Int): List<List<Double>> = lines.filter { it.color == color }.flatMap { batch ->
    (0 until batch.segmentCount).map { s -> listOf(0, 1, 3, 4).map { batch.coords[s * 6 + it] } }
  }

  @Test
  fun `WIPEOUT-квадрат - два треугольника цвета фона`() {
    val fill = read(wipeout).fills.single()
    assertEquals(DxfColors.BACKGROUND, fill.color)
    assertEquals(2, fill.triangleCount)
    val xs = fill.coords.filterIndexed { i, _ -> i % 3 == 0 }
    val ys = fill.coords.filterIndexed { i, _ -> i % 3 == 1 }
    assertEquals(5.0, xs.min(), 1e-9)
    assertEquals(15.0, xs.max(), 1e-9)
    assertEquals(0.0, ys.min(), 1e-9)
    assertEquals(10.0, ys.max(), 1e-9)
  }

  @Test
  fun `линия до маски обрезается, линия после - целая`() {
    val geometry = read(line(1, 0.0, 3.0, 20.0, 3.0), wipeout, line(5, 0.0, 7.0, 20.0, 7.0))
    val before = geometry.segments(red)
    assertEquals(2, before.size)
    assertEquals(listOf(0.0, 3.0, 5.0, 3.0), before[0].map { (it * 1e9).let(Math::round) / 1e9 })
    assertEquals(listOf(15.0, 3.0, 20.0, 3.0), before[1].map { (it * 1e9).let(Math::round) / 1e9 })
    assertEquals(listOf(listOf(0.0, 7.0, 20.0, 7.0)), geometry.segments(blue))
  }

  @Test
  fun `линия целиком под маской пропадает, мимо - не трогается`() {
    val geometry = read(line(1, 6.0, 1.0, 14.0, 9.0), line(5, 0.0, 20.0, 20.0, 20.0), wipeout)
    assertTrue(geometry.segments(red).isEmpty())
    assertEquals(listOf(listOf(0.0, 20.0, 20.0, 20.0)), geometry.segments(blue))
  }

  @Test
  fun `заливка маски поверх более ранних заливок`() {
    val solid = arrayOf("0", "SOLID", "8", "0", "62", "1", "10", "2", "20", "2", "11", "8", "21", "2", "12", "2", "22", "8", "13", "8", "23", "8")
    val fills = read(solid, wipeout).fills
    val mask = fills.single { it.color == DxfColors.BACKGROUND }
    val under = fills.single { it.color == red }
    assertTrue(mask.orders!!.min() > under.orders!!.max())
  }

  @Test
  fun `рамка - только по флагу WIPEOUTVARIABLES`() {
    assertTrue(read(wipeout).lines.isEmpty())
    val variables = arrayOf("0", "SECTION", "2", "OBJECTS", "0", "WIPEOUTVARIABLES", "5", "80", "100", "AcDbWipeoutVariables", "70", "1", "0", "ENDSEC")
    assertEquals(4, read(wipeout, objects = variables).lines.single().segmentCount)
    val off = arrayOf("0", "SECTION", "2", "OBJECTS", "0", "WIPEOUTVARIABLES", "5", "80", "100", "AcDbWipeoutVariables", "70", "0", "0", "ENDSEC")
    assertTrue(read(wipeout, objects = off).lines.isEmpty())
  }

  @Test
  fun `тестовый файл - треугольная маска в повёрнутом блоке, как boundary_path_wcs в ezdxf`() {
    val geometry = readGeometry(File("src/jvmTest/resources/dxf/wipeout.dxf"))
    val masks = geometry.fills.single { it.color == DxfColors.BACKGROUND }
    // квадрат (2 треугольника) и треугольник блока
    assertEquals(3, masks.triangleCount)
    // вершина треугольника (5, 9) блока, вставленного в (30, 0) с поворотом 15°
    val a = Math.toRadians(15.0)
    val tx = 30 + 5 * Math.cos(a) - 9 * Math.sin(a)
    val ty = 5 * Math.sin(a) + 9 * Math.cos(a)
    val points = (0 until masks.coords.size / 3).map { masks.coords[it * 3] to masks.coords[it * 3 + 1] }
    assertTrue(points.any { (x, y) -> Math.abs(x - tx) < 1e-9 && Math.abs(y - ty) < 1e-9 })
    // голубые линии блока под треугольником разрезаны: отрезков больше 11
    assertTrue(geometry.segments(DxfColors.aciToRgb(4)).size > 11)
  }
}
