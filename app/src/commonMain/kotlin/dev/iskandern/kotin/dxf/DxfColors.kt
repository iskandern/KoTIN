package dev.iskandern.kotin.dxf

import kotlin.math.pow

/**
 * Цвет AutoCAD Color Index (ACI) → 0xRRGGBB по стандартной палитре:
 * 1–9 — основные, 10–249 — 24 тона по 15° × 10 оттенков (чётные — насыщенные, нечётные — пастельные,
 * яркость ступенями), 250–255 — серые.
 */
object DxfColors {

  const val WHITE = 0xFFFFFF
  const val WHITE_ACI = 7
  /** Фон сцены (`BACKGROUND` в SceneView): им закрашены WIPEOUT и плашка MTEXT цвета окна. */
  const val BACKGROUND = 0xE3E7EB
  /** Тёмный цвет текста интерфейса: им на светлом фоне рисуется белый (ACI 7), как в AutoCAD. */
  const val INK = 0x15202B

  /**
   * Цвет для показа на светлом фоне, как AutoCAD на белом: белый становится тёмным ([INK]), слишком светлые (жёлтый,
   * голубой, зелёный, светло-серые, пастельные) затемняются до читаемого — контраст с фоном не меньше [MIN_CONTRAST],
   * оттенок и насыщенность те же (каналы делятся в линейном свете поровну). Цвет фона остаётся: им WIPEOUT и плашки
   * MTEXT закрашивают то, что под ними. Одно место для линий, заливок, подписей и точек.
   */
  fun onBackground(rgb: Int): Int {
    val c = rgb and 0xFFFFFF
    if (c == WHITE) return INK
    if (c == BACKGROUND) return c
    val luminance = luminance(c)
    if (luminance <= MAX_LUMINANCE) return c
    val k = MAX_LUMINANCE / luminance
    val r = LINEAR[(c shr 16) and 0xFF]
    val g = LINEAR[(c shr 8) and 0xFF]
    val b = LINEAR[c and 0xFF]
    return (srgb(r * k) shl 16) or (srgb(g * k) shl 8) or srgb(b * k)
  }

  /** Наименьший контраст цвета с фоном (по WCAG: 3 — для линий и значков). */
  private const val MIN_CONTRAST = 3f

  // sRGB 0..255 → линейный свет
  private val LINEAR = FloatArray(256) { v ->
    val x = v / 255f
    if (x <= 0.04045f) x / 12.92f else ((x + 0.055f) / 1.055f).pow(2.4f)
  }

  // яркость фона #E3E7EB ≈ 0,795; цвет с яркостью не выше этой даёт с ним контраст не меньше MIN_CONTRAST
  private val MAX_LUMINANCE = (luminance(BACKGROUND) + 0.05f) / MIN_CONTRAST - 0.05f

  private fun luminance(rgb: Int) =
    0.2126f * LINEAR[(rgb shr 16) and 0xFF] + 0.7152f * LINEAR[(rgb shr 8) and 0xFF] + 0.0722f * LINEAR[rgb and 0xFF]

  private fun srgb(linear: Float): Int {
    val x = if (linear <= 0.0031308f) linear * 12.92f else 1.055f * linear.pow(1f / 2.4f) - 0.055f
    return (x * 255f + 0.5f).toInt().coerceIn(0, 255)
  }

  fun aciToRgb(aci: Int): Int {
    val index = if (aci < 0) -aci else aci
    return when (index) {
      1 -> 0xFF0000
      2 -> 0xFFFF00
      3 -> 0x00FF00
      4 -> 0x00FFFF
      5 -> 0x0000FF
      6 -> 0xFF00FF
      7 -> WHITE
      8 -> 0x808080
      9 -> 0xC0C0C0
      in 10..249 -> tableColor(index)
      in 250..255 -> GRAYS[index - 250]
      else -> WHITE
    }
  }

  private fun tableColor(aci: Int): Int {
    val hue = (aci / 10 - 1) * 15.0
    val shade = aci % 10
    val (r, g, b) = hueToRgb(hue)
    val value = VALUES[shade / 2]
    // нечётные — наполовину разбавлены белым
    val pastel = shade % 2 == 1
    fun channel(c: Double): Int {
      val base = if (pastel) c * 0.5 + 0.5 else c
      return (base * value).toInt().coerceIn(0, 255)
    }
    return (channel(r) shl 16) or (channel(g) shl 8) or channel(b)
  }

  // полный тон при насыщенности и яркости 1
  private fun hueToRgb(hue: Double): Triple<Double, Double, Double> {
    val h = hue / 60.0
    val x = 1 - kotlin.math.abs(h % 2 - 1)
    return when (h.toInt()) {
      0 -> Triple(1.0, x, 0.0)
      1 -> Triple(x, 1.0, 0.0)
      2 -> Triple(0.0, 1.0, x)
      3 -> Triple(0.0, x, 1.0)
      4 -> Triple(x, 0.0, 1.0)
      else -> Triple(1.0, 0.0, x)
    }
  }

  private val VALUES = doubleArrayOf(255.0, 165.0, 127.0, 76.0, 38.0)
  private val GRAYS = intArrayOf(0x333333, 0x5B5B5B, 0x848484, 0xADADAD, 0xD6D6D6, 0xFFFFFF)
}
