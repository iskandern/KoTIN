package dev.iskandern.kotin

import dev.iskandern.kotin.dxf.DxfColors
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Подстройка цветов DXF под светлый фон: светлые темнеют до контраста 3 : 1, тёмные и фон не меняются. */
class DxfColorsContrastTest {

  private fun linear(c: Int): Double = (c / 255.0).let { if (it <= 0.04045) it / 12.92 else ((it + 0.055) / 1.055).pow(2.4) }

  private fun luminance(rgb: Int) =
    0.2126 * linear((rgb shr 16) and 0xFF) + 0.7152 * linear((rgb shr 8) and 0xFF) + 0.0722 * linear(rgb and 0xFF)

  private fun contrast(a: Int, b: Int): Double {
    val la = luminance(a)
    val lb = luminance(b)
    return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
  }

  @Test
  fun lightColorsGetReadable() {
    // жёлтый, зелёный, голубой, светло-серые 9 и 254, пастельный ACI 11, светлый truecolor
    val light = listOf(2, 3, 4, 9, 254, 11).map(DxfColors::aciToRgb) + 0xFFE0A0
    for (rgb in light) {
      val shown = DxfColors.onBackground(rgb)
      assertTrue(contrast(shown, DxfColors.BACKGROUND) >= 2.95, "%06X → %06X".format(rgb, shown))
    }
  }

  @Test
  fun hueIsKept() {
    // жёлтый остаётся жёлтым (оливковым): красный и зелёный поровну, синего нет
    val yellow = DxfColors.onBackground(0xFFFF00)
    assertEquals((yellow shr 16) and 0xFF, (yellow shr 8) and 0xFF)
    assertEquals(0, yellow and 0xFF)
    val cyan = DxfColors.onBackground(0x00FFFF)
    assertEquals(0, (cyan shr 16) and 0xFF)
    assertEquals((cyan shr 8) and 0xFF, cyan and 0xFF)
  }

  @Test
  fun darkColorsBackgroundAndWhite() {
    for (rgb in listOf(0xFF0000, 0x0000FF, 0x808080, 0x15202B)) assertEquals(rgb, DxfColors.onBackground(rgb))
    // WIPEOUT и плашки цвета окна закрашивают фоном — он не темнеет
    assertEquals(DxfColors.BACKGROUND, DxfColors.onBackground(DxfColors.BACKGROUND))
    assertEquals(DxfColors.INK, DxfColors.onBackground(DxfColors.WHITE))
  }
}
