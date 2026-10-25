package dev.iskandern.kotin

import dev.iskandern.kotin.scene.ATLAS_FONT_PX
import dev.iskandern.kotin.scene.ATLAS_SDF_RADIUS
import dev.iskandern.kotin.scene.HALO_DP
import dev.iskandern.kotin.scene.MARKER_CELL_PX
import dev.iskandern.kotin.scene.MARKER_DEFAULT_DP
import dev.iskandern.kotin.scene.MARKER_HALO_PX_PER_DP
import dev.iskandern.kotin.scene.MARKER_INSET
import dev.iskandern.kotin.scene.MARKER_SDF_RADIUS
import dev.iskandern.kotin.scene.MARKER_SIZE_PX
import dev.iskandern.kotin.scene.READABLE_TEXT_DP
import dev.iskandern.kotin.scene.writeSdf
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

class GlyphSdfTest {
  // перебор квадрата радиуса — прежний способ, эталон для построчного
  private fun bruteSdf(coverage: BooleanArray, stride: Int, rgba: ByteArray, x0: Int, y0: Int, w: Int, h: Int, radius: Int) {
    for (y in y0 until y0 + h) for (x in x0 until x0 + w) {
      val inside = coverage[y * stride + x]
      var best = (radius * radius + 1).toFloat()
      for (dy in -radius..radius) {
        val yy = y + dy
        if (yy < y0 || yy >= y0 + h) continue
        for (dx in -radius..radius) {
          val xx = x + dx
          if (xx < x0 || xx >= x0 + w) continue
          if (coverage[yy * stride + xx] != inside) best = min(best, (dx * dx + dy * dy).toFloat())
        }
      }
      val distance = min(sqrt(best), radius.toFloat())
      val signed = if (inside) distance - 0.5f else -(distance - 0.5f)
      val value = (0.5f + signed / (2 * radius)).coerceIn(0f, 1f)
      rgba[(y * stride + x) * 4 + 3] = (value * 255f + 0.5f).toInt().toByte()
    }
  }

  @Test
  fun rowWiseSdfMatchesBruteForce() {
    val random = Random(7)
    val stride = 64
    val height = 48
    // пятна вперемешку с пустыми областями: внутри ячейки есть и края, и пиксели дальше радиуса от края
    val coverage = BooleanArray(stride * height) { i -> (i % stride) / 9 % 2 == 0 && random.nextInt(4) > 0 }
    // радиусы букв и значков
    for (radius in listOf(ATLAS_SDF_RADIUS, MARKER_SDF_RADIUS)) {
      val fast = ByteArray(stride * height * 4)
      val brute = ByteArray(stride * height * 4)
      // две ячейки: соседняя не должна влиять на поле
      for ((x0, w) in listOf(0 to 30, 30 to 34)) {
        writeSdf(coverage, stride, fast, x0, 4, w, 40, radius)
        bruteSdf(coverage, stride, brute, x0, 4, w, 40, radius)
      }
      val alpha = { a: ByteArray -> ByteArray(a.size / 4) { a[it * 4 + 3] } }
      assertContentEquals(alpha(brute), alpha(fast))
    }
  }

  @Test
  fun fieldReachesHaloOutsideGlyph() {
    // ореол при читаемом кегле и предел ореола (восьмая часть кегля) — в px атласа; поле снаружи символа не короче
    assertTrue(ATLAS_SDF_RADIUS - 0.5f >= HALO_DP / READABLE_TEXT_DP * ATLAS_FONT_PX)
    assertTrue(ATLAS_SDF_RADIUS - 0.5f >= ATLAS_FONT_PX / 8f)
  }

  @Test
  fun markerFieldReachesSelectionHalo() {
    // ореол выбранной точки с кантом — 4 dp; у значка по умолчанию на dp приходится MARKER_SIZE_PX / MARKER_DEFAULT_DP
    val haloDp = 4f
    val pxPerDp = MARKER_SIZE_PX / MARKER_DEFAULT_DP
    assertTrue(pxPerDp <= MARKER_HALO_PX_PER_DP, "у значка по умолчанию ореол не ужимается")
    // ореол на пределе и сглаживание (до 0,35·px на px экрана при плотности 1) — внутри поля
    assertTrue(haloDp * MARKER_HALO_PX_PER_DP + 2 * 0.35f * MARKER_HALO_PX_PER_DP <= MARKER_SDF_RADIUS - 0.5f)
    // поле целиком в ячейке: от фигуры (радиус с отступом MARKER_INSET и полштриха r/10) до края — не меньше радиуса
    val figure = (MARKER_SIZE_PX / 2f - MARKER_INSET) * 1.1f
    assertTrue(MARKER_CELL_PX / 2f - figure >= MARKER_SDF_RADIUS + 1)
  }
}
