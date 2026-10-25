package dev.iskandern.kotin

import dev.iskandern.kotin.model.SceneGeometry
import dev.iskandern.kotin.model.EXTRUSION_ENTITY
import dev.iskandern.kotin.model.EXTRUSION_NONE
import dev.iskandern.kotin.model.EXTRUSION_POINT
import dev.iskandern.kotin.scene.buildRenderScene
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DxfThicknessTest {

  private fun entities(vararg body: String) = arrayOf("0", "SECTION", "2", "ENTITIES", *body, "0", "ENDSEC", "0", "EOF")

  private fun SceneGeometry.extrusionLines(kind: Int = EXTRUSION_ENTITY) = lines.filter { it.extrusion == kind }
  private fun SceneGeometry.extrusionFills() = fills.filter { it.extrusion == EXTRUSION_ENTITY }

  @Test
  fun `LINE длиной 10 с толщиной 3 - копия, два ребра и два треугольника грани`() {
    val geometry = readGeometry(*entities(
      "0", "LINE", "8", "T", "39", "3", "10", "0", "20", "0", "30", "0", "11", "10", "21", "0", "31", "0",
    ))
    // основание — как без толщины
    val base = geometry.lines.single { it.extrusion == EXTRUSION_NONE }
    assertEquals(1, base.segmentCount)
    assertTrue(base.flat)

    val extra = geometry.extrusionLines().single()
    assertTrue(extra.flat, "основание на z = 0 — пакет толщины поднимается вместе с чертежом")
    assertEquals(3, extra.segmentCount)
    val c = extra.coords
    // копия на высоте 3
    assertEquals(listOf(0.0, 0.0, 3.0, 10.0, 0.0, 3.0), c.copyOfRange(0, 6).toList())
    // вертикальные рёбра в обеих вершинах
    assertEquals(listOf(0.0, 0.0, 0.0, 0.0, 0.0, 3.0), c.copyOfRange(6, 12).toList())
    assertEquals(listOf(10.0, 0.0, 0.0, 10.0, 0.0, 3.0), c.copyOfRange(12, 18).toList())

    val faces = geometry.extrusionFills().single()
    assertEquals(2, faces.triangleCount)
    val corners = (0 until 6).map { Triple(faces.coords[it * 3], faces.coords[it * 3 + 1], faces.coords[it * 3 + 2]) }.toSet()
    assertEquals(setOf(Triple(0.0, 0.0, 0.0), Triple(10.0, 0.0, 0.0), Triple(10.0, 0.0, 3.0), Triple(0.0, 0.0, 3.0)), corners)
  }

  @Test
  fun `CIRCLE с толщиной - цилиндр`() {
    val geometry = readGeometry(*entities("0", "CIRCLE", "8", "T", "39", "5", "10", "0", "20", "0", "30", "0", "40", "2"))
    val base = geometry.lines.single { it.extrusion == EXTRUSION_NONE }
    val extra = geometry.extrusionLines().single()
    val faces = geometry.extrusionFills().single()
    val n = base.segmentCount
    // верхняя окружность без вертикальных рёбер (изломов нет); боковая поверхность — по два треугольника на сегмент
    assertEquals(n, extra.segmentCount)
    assertEquals(2 * n, faces.triangleCount)
    for (i in 0 until faces.coords.size / 3) {
      val x = faces.coords[i * 3]
      val y = faces.coords[i * 3 + 1]
      val z = faces.coords[i * 3 + 2]
      assertEquals(2.0, hypot(x, y), 1e-9)
      assertTrue(z == 0.0 || z == 5.0)
    }
    // верхняя окружность целиком на высоте 5
    for (i in 0 until n * 2) assertEquals(5.0, extra.coords[i * 3 + 2], 1e-12)
  }

  @Test
  fun `POINT с толщиной 2 - отрезок длиной 2 в отдельном пакете`() {
    val geometry = readGeometry(*entities("0", "POINT", "8", "P", "39", "2", "10", "5", "20", "6", "30", "0"))
    assertEquals(1, geometry.points.size)
    val stick = geometry.extrusionLines(EXTRUSION_POINT).single()
    assertEquals(listOf(5.0, 6.0, 0.0, 5.0, 6.0, 2.0), stick.coords.toList())
    assertTrue(geometry.extrusionLines().isEmpty())
    assertTrue(geometry.extrusionFills().isEmpty())
  }

  @Test
  fun `толщина вдоль нормали 210-230`() {
    // нормаль −X: точка выдавливается в сторону −X
    val geometry = readGeometry(*entities(
      "0", "POINT", "8", "P", "39", "2", "10", "5", "20", "6", "30", "7", "210", "-1", "220", "0", "230", "0",
    ))
    assertEquals(listOf(5.0, 6.0, 7.0, 3.0, 6.0, 7.0), geometry.extrusionLines(EXTRUSION_POINT).single().coords.toList())
  }

  @Test
  fun `SOLID с толщиной - призма - верхняя грань и боковые`() {
    val geometry = readGeometry(*entities(
      "0", "SOLID", "8", "S", "39", "4",
      "10", "0", "20", "0", "30", "0", "11", "2", "21", "0", "31", "0",
      "12", "0", "22", "2", "32", "0", "13", "2", "23", "2", "33", "0",
    ))
    val base = geometry.fills.single { it.extrusion == EXTRUSION_NONE }
    // верхняя грань — цвета сущности, как основание, целиком на высоте 4; боковые — притемнённые, четыре грани по 2
    val top = geometry.extrusionFills().single { it.color == base.color }
    assertEquals(2, top.triangleCount)
    for (i in 0 until 6) assertEquals(4.0, top.coords[i * 3 + 2])
    val sides = geometry.extrusionFills().single { it.color != base.color }
    assertEquals(8, sides.triangleCount)
    // замкнутый контур: копия 4 отрезка + 4 ребра (без повтора в первой вершине)
    assertEquals(8, geometry.extrusionLines().single().segmentCount)
  }

  @Test
  fun `масштаб вставки по Z умножает толщину`() {
    val geometry = readGeometry(
      "0", "SECTION", "2", "BLOCKS",
      "0", "BLOCK", "2", "B", "10", "0", "20", "0", "30", "0",
      "0", "POINT", "8", "0", "39", "2", "10", "0", "20", "0", "30", "0",
      "0", "ENDBLK",
      "0", "ENDSEC",
      *entities("0", "INSERT", "2", "B", "8", "0", "10", "1", "20", "1", "30", "0", "43", "3"),
    )
    assertEquals(listOf(1.0, 1.0, 0.0, 1.0, 1.0, 6.0), geometry.extrusionLines(EXTRUSION_POINT).single().coords.toList())
  }

  @Test
  fun `TEXT с толщиной не выдавливается`() {
    val geometry = readGeometry(*entities("0", "TEXT", "8", "T", "39", "2", "10", "0", "20", "0", "30", "0", "40", "1", "1", "A"))
    assertEquals(1, geometry.labels.size)
    assertTrue(geometry.lines.isEmpty())
    assertTrue(geometry.fills.isEmpty())
  }

  @Test
  fun `чертёж в плоскость - толщины нет, иначе стоит на уровне чертежа`() {
    val geometry = readGeometry(*entities(
      "0", "3DFACE", "8", "S", "10", "0", "20", "0", "30", "100", "11", "10", "21", "0", "31", "100",
      "12", "0", "22", "10", "32", "100", "13", "0", "23", "10", "33", "100",
      "0", "LINE", "8", "T", "39", "3", "10", "0", "20", "0", "30", "0", "11", "10", "21", "0", "31", "0",
      "0", "POINT", "8", "P", "39", "2", "10", "5", "20", "5", "30", "0",
    ))
    val scene = buildRenderScene(geometry, flattenDrawing = false)
    assertEquals(3, scene.lines.size)
    assertEquals(1, scene.lines.count { it.pointThickness })
    // основание поднято на уровень поверхности (100), толщина — над ним: 100..103 в мире → разброс 3 по оси Y сцены
    val extrusion = scene.lines.single { it.lineCount == 3 }
    val ys = (0 until extrusion.positions.size / 3).map { extrusion.positions[it * 3 + 1] }
    assertEquals(3f, ys.max() - ys.min(), 1e-4f)
    val base = scene.lines.single { it.lineCount == 1 && !it.pointThickness }
    assertEquals(ys.min(), base.positions[1], 1e-4f)

    val flat = buildRenderScene(geometry, flattenDrawing = true)
    assertEquals(1, flat.lines.size)
    assertTrue(flat.fills.isEmpty())
    assertTrue(flat.lines.none { it.pointThickness })
  }
}
