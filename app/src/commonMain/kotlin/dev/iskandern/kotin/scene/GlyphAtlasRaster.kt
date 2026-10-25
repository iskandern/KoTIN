package dev.iskandern.kotin.scene

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
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
import dev.iskandern.kotin.dxf.MTEXT_NBSP
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Гарнитура знаков ([fonts] и [fontChars] в [buildGlyphAtlas]): не гарнитура подписей, а последнее звено запасных
 * шрифтов — `kotin_symbols.ttf`, урезанный DejaVu Sans.
 */
const val FACE_SYMBOLS = -1

/**
 * Пустой зазор между ячейками атласа, px. Линейная фильтрация на краю ячейки берёт полтексела соседней: вплотную к
 * запятой или дефису стояла ячейка ниже, и ореол рисовал под ними светлую черту.
 */
private const val CELL_GAP = 2

/**
 * Растрирует символы и значки точек в атлас SDF. [chars] — символы по гарнитурам, шрифты гарнитур — [fonts]
 * ([FACE_DEFAULT] — шрифт интерфейса Golos Text; гарнитуры без шрифта рисует он, нет и его — шрифт платформы). Символы,
 * которых нет в шрифте гарнитуры ([fontChars]; нет набора — считаются все), рисует шрифт интерфейса на её базовой линии,
 * нет и в нём — шрифт платформы. Текст рисует Compose, затем из покрытия считается поле расстояний: по нему шейдер
 * даёт чёткий контур при любом масштабе.
 * Выполняется на потоке композиции — нужен [measurer].
 */
fun buildGlyphAtlas(
  chars: Map<Int, Set<Char>>, fonts: Map<Int, FontFamily>, fontChars: Map<Int, Set<Char>>,
  measurer: TextMeasurer, density: Density,
): GlyphAtlas {
  val fontSize = with(density) { ATLAS_FONT_PX.toSp() }
  val systemStyle = TextStyle(color = Color.White, fontSize = fontSize)
  val defaultFamily = fonts[FACE_DEFAULT]
  val defaultStyle = if (defaultFamily == null) systemStyle else systemStyle.copy(fontFamily = defaultFamily)
  val defaultCovered = fontChars[FACE_DEFAULT]
  val symbolsFamily = fonts[FACE_SYMBOLS]
  val symbolsCovered = fontChars[FACE_SYMBOLS].orEmpty()
  val symbolsStyle = if (symbolsFamily == null) systemStyle else systemStyle.copy(fontFamily = symbolsFamily)
  // символа нет в гарнитуре: шрифт интерфейса, если символ в нём есть, затем знаки KoTIN Symbols (⌀, ∅, греческие,
  // знаки допусков), затем шрифт платформы — на web его нет, и символ был бы квадратом
  fun fallbackStyle(ch: Char) = when {
    defaultFamily == null || defaultCovered == null || ch in defaultCovered -> defaultStyle
    ch in symbolsCovered -> symbolsStyle
    else -> systemStyle
  }

  class Entry(val face: Int, val char: Char, val width: Int, val layoutWidth: Float, val isMarker: Boolean) {
    var x = 0
    var y = 0
  }

  class FaceCells(val cellHeight: Int, val baselineFromTop: Float, val spaceAdvance: Float)

  val layouts = HashMap<Pair<Int, Char>, TextLayoutResult>()
  val entries = ArrayList<Entry>()
  val cells = HashMap<Int, FaceCells>()
  // шрифт интерфейса есть всегда: в нём значки точек
  val faces = (chars.keys + FACE_DEFAULT).sorted()
  for (face in faces) {
    val family = fonts[face]
    val style = if (family == null) defaultStyle else systemStyle.copy(fontFamily = family)
    val covered = fontChars[face]
    var above = 0f
    var below = 0f
    val faceChars = chars[face].orEmpty()
    for (ch in faceChars.sorted()) {
      // неразрывный пробел MTEXT меряется как обычный
      if (ch <= ' ' || ch == MTEXT_NBSP || ch in MARKER_CHARS) continue
      val own = family == null || covered == null || ch in covered
      val layout = measurer.measure(ch.toString(), style = if (own) style else fallbackStyle(ch), softWrap = false, overflow = TextOverflow.Clip)
      layouts[face to ch] = layout
      above = max(above, layout.firstBaseline)
      below = max(below, layout.size.height - layout.firstBaseline)
      entries.add(Entry(face, ch, ceil(layout.size.width.toFloat()).toInt() + 2 * ATLAS_PAD, layout.size.width.toFloat(), false))
    }
    // пробел: только шаг пера, ячейки нет
    val spaceAdvance = measurer.measure(" ", style = style, softWrap = false).size.width.toFloat()
    cells[face] = FaceCells(ceil(above + below).toInt() + 2 * ATLAS_PAD, ATLAS_PAD + above, spaceAdvance)
  }
  for (marker in MARKER_CHARS) entries.add(Entry(FACE_DEFAULT, marker, MARKER_CELL_PX, 0f, true))
  fun heightOf(e: Entry) = if (e.isMarker) MARKER_CELL_PX else cells.getValue(e.face).cellHeight

  // полки: ячейки слева направо, новая строка, когда не помещается
  var x = 0
  var y = 0
  var rowHeight = 0
  var atlasWidth = 0
  for (e in entries) {
    val h = heightOf(e)
    if (x + e.width > ATLAS_MAX_WIDTH) {
      x = 0
      y += rowHeight + CELL_GAP
      rowHeight = 0
    }
    e.x = x
    e.y = y
    x += e.width + CELL_GAP
    rowHeight = max(rowHeight, h)
    atlasWidth = max(atlasWidth, x)
  }
  val atlasHeight = y + rowHeight

  val bitmap = ImageBitmap(atlasWidth, atlasHeight)
  val canvas = Canvas(bitmap)
  CanvasDrawScope().draw(density, LayoutDirection.Ltr, canvas, Size(atlasWidth.toFloat(), atlasHeight.toFloat())) {
    for (e in entries) {
      if (e.isMarker) drawMarker(e.char, Offset(e.x.toFloat(), e.y.toFloat()))
      else {
        // базовые линии символов гарнитуры совпадают, даже если символ нарисован шрифтом интерфейса
        val layout = layouts.getValue(e.face to e.char)
        val top = e.y + cells.getValue(e.face).baselineFromTop - layout.firstBaseline
        drawText(layout, topLeft = Offset((e.x + ATLAS_PAD).toFloat(), top))
      }
    }
  }

  val pixels = bitmap.toPixelMap()
  val coverage = BooleanArray(atlasWidth * atlasHeight)
  for (py in 0 until atlasHeight) for (px in 0 until atlasWidth) coverage[py * atlasWidth + px] = pixels[px, py].alpha >= 0.5f

  val rgba = ByteArray(atlasWidth * atlasHeight * 4)
  val faceGlyphs = faces.associateWith { HashMap<Char, Glyph>() }
  for (e in entries) {
    val w = e.width
    val h = heightOf(e)
    val glyphs = faceGlyphs.getValue(e.face)
    writeSdf(coverage, atlasWidth, rgba, e.x, e.y, w, h, if (e.isMarker) MARKER_SDF_RADIUS else ATLAS_SDF_RADIUS)
    if (e.char == MARKER_FILL) {
      // только середина ячейки: соседние ячейки не просачиваются при фильтрации
      val cx = e.x + w / 2f
      val cy = e.y + h / 2f
      glyphs[e.char] = Glyph((cx - 2) / atlasWidth, (cy - 2) / atlasHeight, (cx + 2) / atlasWidth, (cy + 2) / atlasHeight, w, 0f)
      continue
    }
    glyphs[e.char] = cellGlyph(e.x, e.y, w, h, atlasWidth, atlasHeight, e.layoutWidth)
  }
  for (face in faces) faceGlyphs.getValue(face)[' '] = Glyph(0f, 0f, 0f, 0f, 0, cells.getValue(face).spaceAdvance)
  val default = cells.getValue(FACE_DEFAULT)
  val replacements = faces.filter { it != FACE_DEFAULT }.associateWith { face ->
    val c = cells.getValue(face)
    // гарнитура не загрузилась — рисует шрифт интерфейса, метрики тоже его (атлас ждёт Golos, см. SceneView)
    val metrics = if (fonts[face] != null) face else FACE_DEFAULT
    GlyphFace(faceGlyphs.getValue(face), c.cellHeight, c.baselineFromTop, FACE_CAP_HEIGHT[metrics], FACE_DESCENT[metrics])
  }
  return GlyphAtlas(
    atlasWidth, atlasHeight, rgba, faceGlyphs.getValue(FACE_DEFAULT), default.cellHeight, default.baselineFromTop, replacements,
  )
}

/**
 * Ячейка атласа в UV: строка данных y — это v = y / высота (первая строка — v = 0). Переворот под `flipUV` материала
 * (v ↦ 1 − v в вершинном шейдере) делает TextMesh при сборке квада, поэтому здесь V не переворачивается: второй
 * переворот отражает ячейки по вертикали, и символы берутся из чужих строк атласа.
 */
internal fun cellGlyph(x: Int, y: Int, w: Int, h: Int, atlasWidth: Int, atlasHeight: Int, advance: Float) = Glyph(
  u0 = x.toFloat() / atlasWidth, v0 = y.toFloat() / atlasHeight,
  u1 = (x + w).toFloat() / atlasWidth, v1 = (y + h).toFloat() / atlasHeight,
  cellWidth = w,
  advance = advance,
)

private const val ATLAS_MAX_WIDTH = 1024

// отступ фигуры значка от края номинальной стороны MARKER_SIZE_PX, px атласа
internal const val MARKER_INSET = 6

/** Радиус диска [MARKER_DISC], px атласа: на всю фигуру — край кружка гладкий и при крупном кружке на экране. */
internal const val MARKER_DISC_RADIUS_PX = MARKER_SIZE_PX / 2f - MARKER_INSET

// значки точек, белым в середине ячейки MARKER_CELL_PX; вокруг фигуры — поле SDF MARKER_SDF_RADIUS
private fun DrawScope.drawMarker(marker: Char, origin: Offset) {
  val c = Offset(origin.x + MARKER_CELL_PX / 2f, origin.y + MARKER_CELL_PX / 2f)
  val r = MARKER_SIZE_PX / 2f - MARKER_INSET
  val width = r / 5
  val color = Color.White
  when (marker) {
    MARKER_DOT -> drawCircle(color, radius = r / 3, center = c)
    MARKER_DISC -> drawCircle(color, radius = MARKER_DISC_RADIUS_PX, center = c)
    MARKER_PLUS -> {
      drawLine(color, Offset(c.x - r, c.y), Offset(c.x + r, c.y), strokeWidth = width)
      drawLine(color, Offset(c.x, c.y - r), Offset(c.x, c.y + r), strokeWidth = width)
    }
    MARKER_CROSS -> {
      drawLine(color, Offset(c.x - r, c.y - r), Offset(c.x + r, c.y + r), strokeWidth = width)
      drawLine(color, Offset(c.x - r, c.y + r), Offset(c.x + r, c.y - r), strokeWidth = width)
    }
    MARKER_TICK -> drawLine(color, c, Offset(c.x, c.y - r), strokeWidth = width)
    MARKER_CIRCLE -> drawCircle(color, radius = r, center = c, style = Stroke(width))
    MARKER_SQUARE -> drawRect(color, topLeft = Offset(c.x - r, c.y - r), size = Size(2 * r, 2 * r), style = Stroke(width))
    // вся ячейка залита: края SDF нет, середина растягивается на любой прямоугольник
    MARKER_FILL -> drawRect(color, topLeft = origin, size = Size(MARKER_CELL_PX.toFloat(), MARKER_CELL_PX.toFloat()))
  }
}

/**
 * SDF ячейки: расстояние до ближайшего пикселя другого состояния, в радиусе [radius] (у букв [ATLAS_SDF_RADIUS], у
 * значков [MARKER_SDF_RADIUS]); 0,5 — контур.
 * Сначала по строкам — расстояние вдоль строки до ближайшего пикселя внутри и снаружи, затем по столбцу радиуса:
 * O(радиус) на пиксель вместо O(радиус²).
 */
internal fun writeSdf(coverage: BooleanArray, stride: Int, rgba: ByteArray, x0: Int, y0: Int, w: Int, h: Int, radius: Int = ATLAS_SDF_RADIUS) {
  val far = radius + 1
  // по строкам ячейки: до ближайшего пикселя внутри (toIn) и снаружи (toOut), не дальше far
  val toIn = IntArray(w * h)
  val toOut = IntArray(w * h)
  for (y in 0 until h) {
    val row = (y0 + y) * stride + x0
    var lastIn = -far
    var lastOut = -far
    for (x in 0 until w) {
      if (coverage[row + x]) lastIn = x else lastOut = x
      toIn[y * w + x] = minOf(x - lastIn, far)
      toOut[y * w + x] = minOf(x - lastOut, far)
    }
    lastIn = w - 1 + far
    lastOut = w - 1 + far
    for (x in w - 1 downTo 0) {
      if (coverage[row + x]) lastIn = x else lastOut = x
      toIn[y * w + x] = minOf(toIn[y * w + x], lastIn - x)
      toOut[y * w + x] = minOf(toOut[y * w + x], lastOut - x)
    }
  }
  for (y in 0 until h) for (x in 0 until w) {
    val inside = coverage[(y0 + y) * stride + x0 + x]
    val other = if (inside) toOut else toIn
    var best = (radius * radius + 1).toFloat()
    for (dy in -radius..radius) {
      val yy = y + dy
      if (yy < 0 || yy >= h) continue
      val dx = other[yy * w + x]
      if (dx <= radius) best = min(best, (dx * dx + dy * dy).toFloat())
    }
    val distance = min(sqrt(best), radius.toFloat())
    // пиксель — точка в центре: полпикселя до границы
    val signed = if (inside) distance - 0.5f else -(distance - 0.5f)
    val value = (0.5f + signed / (2 * radius)).coerceIn(0f, 1f)
    val o = ((y0 + y) * stride + x0 + x) * 4
    rgba[o] = -1
    rgba[o + 1] = -1
    rgba[o + 2] = -1
    rgba[o + 3] = (value * 255f + 0.5f).toInt().toByte()
  }
}
