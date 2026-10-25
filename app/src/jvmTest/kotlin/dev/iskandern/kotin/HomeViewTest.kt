package dev.iskandern.kotin

import dev.iskandern.kotin.scene.RenderScene
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.tan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HomeViewTest {

  private fun scene(halfX: Float, halfY: Float, halfZ: Float) = RenderScene(
    surfaces = emptyList(), lines = emptyList(), fills = emptyList(), labels = emptyList(), points = emptyList(),
    layerInfos = emptyList(), truncated = false, drawingFlattened = false,
    originX = 0.0, originY = 0.0, originZ = 0.0,
    halfX = halfX, halfY = halfY, halfZ = halfZ,
  )

  @Test
  fun `плоский чертёж открывается строго сверху и целиком в кадре`() {
    // лист А4 портретом: 210 × 297 мм, высоты нет
    val sheet = scene(105f, 0f, 148.5f)
    assertTrue(isFlatScene(sheet))
    val tanHalf = tan(22.5 * PI / 180).toFloat()
    for (aspect in listOf(0.5f, 1f, 16f / 9f)) {
      val eye = homeEye(sheet, aspect)
      assertEquals(0f, eye.x)
      assertEquals(0f, eye.z)
      // по вертикали и по горизонтали кадра помещается весь лист
      assertTrue(eye.y * tanHalf >= 148.5f, "aspect $aspect: ${eye.y}")
      assertTrue(eye.y * tanHalf * aspect >= 105f, "aspect $aspect: ${eye.y}")
      // и не с большим запасом: по одной из сторон лист почти во весь кадр
      assertTrue(eye.y * tanHalf < 148.5f * 1.2f || eye.y * tanHalf * aspect < 105f * 1.2f, "aspect $aspect: ${eye.y}")
    }
  }

  @Test
  fun `сцена с рельефом — прежний вид сверху-сбоку`() {
    val terrain = scene(500f, 20f, 500f)
    assertFalse(isFlatScene(terrain))
    val eye = homeEye(terrain, 1f)
    assertTrue(eye.z > 0f && eye.y > 0f && eye.y < eye.z)
  }

  @Test
  fun `индикатор осей поворачивается с камерой`() {
    // ось [axis] — три числа подряд
    fun near(a: FloatArray, axis: Int, vararg b: Float) = b.indices.all { abs(a[axis * 3 + it] - b[it]) < 1e-5f }
    // сверху севером вверх: X вправо, Y вверх, Z на зрителя
    val top = axesOnScreen(0f, (PI / 2).toFloat())
    assertTrue(near(top, 0, 1f, 0f, 0f))
    assertTrue(near(top, 1, 0f, 1f, 0f))
    assertTrue(near(top, 2, 0f, 0f, 1f))
    // сбоку с юга: X вправо, Y от зрителя, Z вверх
    val side = axesOnScreen(0f, 0f)
    assertTrue(near(side, 0, 1f, 0f, 0f))
    assertTrue(near(side, 1, 0f, 0f, -1f))
    assertTrue(near(side, 2, 0f, 1f, 0f))
    // сверху, азимут 90°: оси повёрнуты на четверть оборота
    val turned = axesOnScreen((PI / 2).toFloat(), (PI / 2).toFloat())
    assertTrue(near(turned, 0, 0f, -1f, 0f))
    assertTrue(near(turned, 1, 1f, 0f, 0f))
  }
}
