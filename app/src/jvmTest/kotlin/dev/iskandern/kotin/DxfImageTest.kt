package dev.iskandern.kotin

import dev.iskandern.kotin.model.SceneGeometry
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DxfImageTest {

  // IMAGE 100 × 50 пикселей, пиксель 0,2: рамка 20 × 10 с углом в (10, 5); ссылка на IMAGEDEF с handle 2A
  private fun image(def: String? = "2A", vararg extra: String) = arrayOf(
    "0", "IMAGE", "8", "0", "62", "1",
    "10", "10", "20", "5", "30", "0", "11", "0.2", "21", "0", "31", "0", "12", "0", "22", "0.2", "32", "0",
    "13", "100", "23", "50", *(if (def != null) arrayOf("340", def) else emptyArray()), *extra,
  )

  private fun imageDef(path: String) = arrayOf("0", "IMAGEDEF", "5", "2A", "100", "AcDbRasterImageDef", "1", path)

  private fun read(entity: Array<String>, objects: Array<String> = emptyArray()): SceneGeometry = readGeometry(
    "0", "SECTION", "2", "ENTITIES", *entity, "0", "ENDSEC",
    "0", "SECTION", "2", "OBJECTS", *objects, "0", "ENDSEC", "0", "EOF",
  )

  private fun SceneGeometry.frame(): List<Double> {
    val xs = lines.flatMap { b -> (0 until b.segmentCount * 2).map { b.coords[it * 3] } }
    val ys = lines.flatMap { b -> (0 until b.segmentCount * 2).map { b.coords[it * 3 + 1] } }
    return listOf(xs.min(), ys.min(), xs.max(), ys.max())
  }

  @Test
  fun `IMAGE - рамка нужного размера и подпись с путём`() {
    val g = read(image(), imageDef("C:\\Users\\a\\plan.png"))
    val f = g.frame()
    assertEquals(listOf(10.0, 5.0, 30.0, 15.0), f.map { Math.round(it * 1e9) / 1e9 })
    assertEquals(4, g.lines.sumOf { it.segmentCount })
    val label = g.labels.single()
    assertEquals("C:\\Users\\a\\plan.png", label.text)
    assertEquals(20.0, label.x, 1e-9)
    assertEquals(10.0, label.y, 1e-9)
  }

  @Test
  fun `IMAGE без IMAGEDEF - рамка и подпись изображение`() {
    val g = read(image(def = null))
    assertEquals(4, g.lines.sumOf { it.segmentCount })
    assertEquals("изображение", g.labels.single().text)
    val broken = read(image(def = "FFFF"), imageDef("x.png"))
    assertEquals("изображение", broken.labels.single().text)
  }

  @Test
  fun `длинный путь режется с многоточием посередине, имя файла остаётся`() {
    val path = "C:\\Users\\very-long-user-name\\Documents\\Projects\\Survey\\Orthophoto\\2026\\site-plan.tif"
    val label = read(image(), imageDef(path)).labels.single()
    assertTrue(label.text.length < path.length)
    assertTrue(label.text.startsWith("C:\\"))
    assertTrue(label.text.endsWith("…site-plan.tif"), label.text)
    // подпись по ширине помещается в рамку 20
    val width = label.text.sumOf { (if (it == '.' || it == ',' || it == ' ') 0.35 else 0.8) } * label.height
    assertTrue(width <= 20.0, "ширина $width")
  }

  @Test
  fun `повёрнутая картинка с обрезкой - рамка по вершинам, подпись вдоль U`() {
    // U повёрнут на 90°: пиксель идёт вдоль +Y, V — вдоль −X; обрезка — прямоугольник 20…80 × 10…40 пикселей
    val rotated = arrayOf(
      "0", "IMAGE", "8", "0",
      "10", "0", "20", "0", "30", "0", "11", "0", "21", "0.2", "31", "0", "12", "-0.2", "22", "0", "32", "0",
      "13", "100", "23", "50", "340", "2A", "280", "1", "71", "1", "91", "2",
      "14", "19.5", "24", "9.5", "14", "79.5", "24", "39.5",
    )
    val g = read(rotated, imageDef("a.png"))
    val f = g.frame()
    // u в [20, 80] пикселей → y в [4, 16]; v = 50 − y_пикс в [10, 40] → x в [−8, −2]
    assertEquals(listOf(-8.0, 4.0, -2.0, 16.0), f.map { Math.round(it * 1e9) / 1e9 })
    val label = g.labels.single()
    assertEquals(90.0, label.rotationDeg, 1e-9)
    assertEquals(-5.0, label.x, 1e-9)
    assertEquals(10.0, label.y, 1e-9)
    assertTrue(abs(label.height) > 0)
  }
}
