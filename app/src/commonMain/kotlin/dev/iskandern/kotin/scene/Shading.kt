package dev.iskandern.kotin.scene

import io.github.erkko68.filament.compose.scene.LinearColor
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Освещение поверхности на CPU: ламбертово от одного источника сверху-сбоку плюс рассеянный свет.
 * Результат — цвет вершин для unlit-материала (feature level 0 не умеет lit-материалы).
 * Постобработка выключена, поэтому цвет сразу переводится в sRGB-гамму.
 */
fun bakeSurfaceColors(normals: FloatArray, verticalScale: Float, base: LinearColor): FloatArray {
  val colors = FloatArray(normals.size / 3 * 4)
  for (v in 0 until normals.size / 3) {
    // нормаль при растяжении по Y в k раз: y делится на k (обратная транспонированная матрица)
    val nx = normals[v * 3]
    val ny = normals[v * 3 + 1] / verticalScale
    val nz = normals[v * 3 + 2]
    val length = sqrt(nx * nx + ny * ny + nz * nz)
    val diffuse = if (length < 1e-12f) 1f else max((nx * LIGHT_X + ny * LIGHT_Y + nz * LIGHT_Z) / length, 0f)
    val shade = AMBIENT + (1f - AMBIENT) * diffuse
    colors[v * 4] = toDisplay(base.r * shade)
    colors[v * 4 + 1] = toDisplay(base.g * shade)
    colors[v * 4 + 2] = toDisplay(base.b * shade)
    colors[v * 4 + 3] = 1f
  }
  return colors
}

/** Координаты с вертикальным масштабом. */
fun scaledPositions(positions: FloatArray, verticalScale: Float): FloatArray {
  val result = positions.copyOf()
  for (i in 1 until result.size step 3) result[i] = result[i] * verticalScale
  return result
}

fun displayColor(color: LinearColor) = LinearColor(toDisplay(color.r), toDisplay(color.g), toDisplay(color.b))

/** Обратное к [displayColor]: цвет экрана 0xRRGGBB → цвет для материала, на экране он снова [rgb]. */
fun linearColor(rgb: Int) = LinearColor(fromDisplay(rgb shr 16), fromDisplay(rgb shr 8), fromDisplay(rgb))

private fun fromDisplay(channel: Int): Float = ((channel and 0xFF) / 255f).pow(2.2f)

private fun toDisplay(linear: Float): Float = linear.coerceIn(0f, 1f).pow(1f / 2.2f)

private const val AMBIENT = 0.35f

// направление на источник света (нормализованное (0.4, 1, 0.3))
private val LIGHT_LENGTH = sqrt(0.4f * 0.4f + 1f + 0.3f * 0.3f)
private val LIGHT_X = 0.4f / LIGHT_LENGTH
private val LIGHT_Y = 1f / LIGHT_LENGTH
private val LIGHT_Z = 0.3f / LIGHT_LENGTH
