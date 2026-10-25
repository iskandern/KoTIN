package dev.iskandern.kotin.scene

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import dev.iskandern.kotin.dxf.FACE_DEFAULT

/**
 * Атлас SDF ([buildGlyphAtlas]), растр — Compose: шрифты гарнитур [fonts], символы рисует [measurer].
 * Выполняется на потоке композиции — нужен [measurer].
 */
fun buildGlyphAtlas(
  chars: Map<Int, Set<Char>>, fonts: Map<Int, FontFamily>, fontChars: Map<Int, Set<Char>>,
  measurer: TextMeasurer, density: Density,
): GlyphAtlas = buildGlyphAtlas(chars, fonts.keys, fontChars, ComposeGlyphRasterizer(fonts, fontChars, measurer, density))

private class ComposeGlyphRasterizer(
  private val fonts: Map<Int, FontFamily>, fontChars: Map<Int, Set<Char>>,
  private val measurer: TextMeasurer, private val density: Density,
) : GlyphRasterizer {
  private val systemStyle = TextStyle(color = Color.White, fontSize = with(density) { ATLAS_FONT_PX.toSp() })
  private val defaultFamily = fonts[FACE_DEFAULT]
  private val defaultStyle = if (defaultFamily == null) systemStyle else systemStyle.copy(fontFamily = defaultFamily)
  private val defaultCovered = fontChars[FACE_DEFAULT]
  private val symbolsCovered = fontChars[FACE_SYMBOLS].orEmpty()
  private val symbolsStyle = fonts[FACE_SYMBOLS]?.let { systemStyle.copy(fontFamily = it) } ?: systemStyle
  private val layouts = HashMap<Pair<Int, Char>, TextLayoutResult>()

  private fun faceStyle(face: Int) = fonts[face]?.let { systemStyle.copy(fontFamily = it) } ?: defaultStyle

  // символа нет в гарнитуре: шрифт интерфейса, если символ в нём есть, затем знаки KoTIN Symbols (⌀, ∅, греческие,
  // знаки допусков), затем шрифт платформы — на web его нет, и символ был бы квадратом
  private fun fallbackStyle(ch: Char) = when {
    defaultFamily == null || defaultCovered == null || ch in defaultCovered -> defaultStyle
    ch in symbolsCovered -> symbolsStyle
    else -> systemStyle
  }

  override fun measure(face: Int, char: Char, fallback: Boolean): GlyphLine {
    val style = if (fallback) fallbackStyle(char) else faceStyle(face)
    val layout = measurer.measure(char.toString(), style = style, softWrap = false, overflow = TextOverflow.Clip)
    layouts[face to char] = layout
    return GlyphLine(layout.size.width.toFloat(), layout.firstBaseline, layout.size.height.toFloat())
  }

  override fun spaceAdvance(face: Int): Float =
    measurer.measure(" ", style = faceStyle(face), softWrap = false).size.width.toFloat()

  private lateinit var bitmap: ImageBitmap
  private val draws = ArrayList<DrawScope.() -> Unit>()

  override fun begin(width: Int, height: Int) {
    bitmap = ImageBitmap(width, height)
  }

  override fun text(face: Int, char: Char, fallback: Boolean, left: Float, top: Float) {
    val layout = layouts.getValue(face to char)
    draws += { drawText(layout, topLeft = Offset(left, top)) }
  }

  override fun marker(marker: Char, left: Float, top: Float) {
    val color = Color.White
    draws += {
      drawMarkerShape(
        marker, left, top,
        circle = { cx, cy, radius, stroke -> drawCircle(color, radius, Offset(cx, cy), style = if (stroke > 0f) Stroke(stroke) else Fill) },
        line = { x0, y0, x1, y1, width -> drawLine(color, Offset(x0, y0), Offset(x1, y1), strokeWidth = width) },
        rect = { x, y, w, h, stroke -> drawRect(color, Offset(x, y), Size(w, h), style = if (stroke > 0f) Stroke(stroke) else Fill) },
      )
    }
  }

  override fun coverage(): BooleanArray {
    val width = bitmap.width
    val height = bitmap.height
    CanvasDrawScope().draw(density, LayoutDirection.Ltr, Canvas(bitmap), Size(width.toFloat(), height.toFloat())) {
      for (draw in draws) draw()
    }
    val pixels = bitmap.toPixelMap()
    val coverage = BooleanArray(width * height)
    for (py in 0 until height) for (px in 0 until width) coverage[py * width + px] = pixels[px, py].alpha >= 0.5f
    return coverage
  }
}
