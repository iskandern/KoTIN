package dev.iskandern.kotin

import dev.iskandern.kotin.scene.RenderScene
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DepthRangeTest {

  // площадка 1000 × 1000 м с перепадом 40 м, сцена отцентрована в нуле
  private val scene = RenderScene(
    surfaces = emptyList(), lines = emptyList(), fills = emptyList(), labels = emptyList(), points = emptyList(),
    layerInfos = emptyList(), truncated = false, drawingFlattened = false,
    originX = 0.0, originY = 0.0, originZ = 0.0,
    halfX = 500f, halfY = 20f, halfZ = 500f,
  )

  @Test
  fun `ближняя плоскость перед габаритами сцены`() {
    // сверху с 900 м над верхом рельефа: near меньше расстояния до верха, но не 5 % от него, как раньше
    val near = nearPlane(0f, 920f, 0f, scene, 1f)
    assertTrue(near < 900f)
    assertTrue(near > 0.3f * 900f, "near = $near")
    // вертикальный масштаб поднимает верх рельефа — near уменьшается
    assertTrue(nearPlane(0f, 920f, 0f, scene, 10f) < near)
    // камера внутри габаритов — минимальный near
    assertEquals(0.05f, nearPlane(0f, 0f, 0f, scene, 1f))
  }

  @Test
  fun `сдвиг глубины на единицу — шаг 16-битного буфера`() {
    // глубина в Filament обратная: d = near · (far − z) / (z · (far − near)); сдвиг вершины к камере на долю
    // depthBias · z расстояния должен менять d не меньше чем на 1 / 2¹⁶ на каждую единицу
    val near = 360.0
    val far = 3000.0
    fun d(z: Double) = near * (far - z) / (z * (far - near))
    val bias = depthBiasPerUnit(near.toFloat()).toDouble()
    for (z in listOf(400.0, 900.0, 2000.0)) {
      val shifted = z * (1 - bias * z * 4)
      val steps = (d(shifted) - d(z)) * 65536
      assertTrue(steps >= 4 * 0.99, "z = $z: $steps шагов")
      // и при этом сдвиг мал: доли процента расстояния
      assertTrue(z - shifted < z * 0.01, "z = $z: сдвиг ${z - shifted}")
    }
  }
}
