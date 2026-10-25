package dev.iskandern.kotin.scene

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

private const val ATLAS_MAX_WIDTH = 1024

// отступ фигуры значка от края номинальной стороны MARKER_SIZE_PX, px атласа
internal const val MARKER_INSET = 6

/** Радиус диска [MARKER_DISC], px атласа: на всю фигуру — край кружка гладкий и при крупном кружке на экране. */
internal const val MARKER_DISC_RADIUS_PX = MARKER_SIZE_PX / 2f - MARKER_INSET

/** Символ одной строкой текста: ширина, базовая линия от верха и высота строки, px атласа. */
class GlyphLine(val width: Float, val firstBaseline: Float, val height: Float)

/**
 * Растр атласа на платформе: меряет и рисует символы белым. [face] — гарнитура, [fallback] — символа в ней нет, и его
 * рисует запасной шрифт (см. [buildGlyphAtlas]). Рисование — после всех замеров, в порядке [draw].
 */
interface GlyphRasterizer {
  fun measure(face: Int, char: Char, fallback: Boolean): GlyphLine
  fun spaceAdvance(face: Int): Float
  /** Холст атласа: потом символы ([text]) и значки ([marker]), затем покрытие (альфа ≥ 0,5) построчно. */
  fun begin(width: Int, height: Int)
  fun text(face: Int, char: Char, fallback: Boolean, left: Float, top: Float)
  fun marker(marker: Char, left: Float, top: Float)
  fun coverage(): BooleanArray
}

/**
 * Растрирует символы и значки точек в атлас SDF. [chars] — символы по гарнитурам; гарнитуры, шрифт которых загружен, —
 * [loaded] ([FACE_DEFAULT] — шрифт интерфейса Golos Text; гарнитуры без шрифта рисует он, нет и его — шрифт
 * платформы). Символы, которых нет в шрифте гарнитуры ([fontChars]; нет набора — считаются все), рисует шрифт
 * интерфейса на её базовой линии, нет и в нём — шрифт платформы ([GlyphRasterizer] решает по `fallback`). Текст рисует
 * платформа, затем из покрытия считается поле расстояний: по нему шейдер даёт чёткий контур при любом масштабе.
 */
fun buildGlyphAtlas(
  chars: Map<Int, Set<Char>>, loaded: Set<Int>, fontChars: Map<Int, Set<Char>>, raster: GlyphRasterizer,
): GlyphAtlas {
  class Entry(val face: Int, val char: Char, val width: Int, val layoutWidth: Float, val isMarker: Boolean, val fallback: Boolean) {
    var x = 0
    var y = 0
    var top = 0f
  }

  class FaceCells(val cellHeight: Int, val baselineFromTop: Float, val spaceAdvance: Float)

  val lines = HashMap<Pair<Int, Char>, GlyphLine>()
  val entries = ArrayList<Entry>()
  val cells = HashMap<Int, FaceCells>()
  // шрифт интерфейса есть всегда: в нём значки точек
  val faces = (chars.keys + FACE_DEFAULT).sorted()
  for (face in faces) {
    val covered = fontChars[face]
    var above = 0f
    var below = 0f
    val faceChars = chars[face].orEmpty()
    for (ch in faceChars.sorted()) {
      // неразрывный пробел MTEXT меряется как обычный
      if (ch <= ' ' || ch == MTEXT_NBSP || ch in MARKER_CHARS) continue
      val own = face !in loaded || covered == null || ch in covered
      val line = raster.measure(face, ch, fallback = !own)
      lines[face to ch] = line
      above = max(above, line.firstBaseline)
      below = max(below, line.height - line.firstBaseline)
      entries.add(Entry(face, ch, ceil(line.width).toInt() + 2 * ATLAS_PAD, line.width, false, !own))
    }
    // пробел: только шаг пера, ячейки нет
    cells[face] = FaceCells(ceil(above + below).toInt() + 2 * ATLAS_PAD, ATLAS_PAD + above, raster.spaceAdvance(face))
  }
  for (marker in MARKER_CHARS) entries.add(Entry(FACE_DEFAULT, marker, MARKER_CELL_PX, 0f, true, false))
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

  raster.begin(atlasWidth, atlasHeight)
  for (e in entries) {
    if (e.isMarker) raster.marker(e.char, e.x.toFloat(), e.y.toFloat())
    else {
      // базовые линии символов гарнитуры совпадают, даже если символ нарисован шрифтом интерфейса
      val line = lines.getValue(e.face to e.char)
      val top = e.y + cells.getValue(e.face).baselineFromTop - line.firstBaseline
      raster.text(e.face, e.char, e.fallback, (e.x + ATLAS_PAD).toFloat(), top)
    }
  }
  val coverage = raster.coverage()

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
    val metrics = if (face in loaded) face else FACE_DEFAULT
    GlyphFace(faceGlyphs.getValue(face), c.cellHeight, c.baselineFromTop, FACE_CAP_HEIGHT[metrics], FACE_DESCENT[metrics])
  }
  return GlyphAtlas(
    atlasWidth, atlasHeight, rgba, faceGlyphs.getValue(FACE_DEFAULT), default.cellHeight, default.baselineFromTop, replacements,
  )
}

/**
 * Фигура значка точки [marker] белым в ячейке MARKER_CELL_PX с левым верхним углом ([left], [top]): что рисовать —
 * платформе ([circle], [line], [rect]; [stroke] 0 — заливка). Вокруг фигуры — поле SDF MARKER_SDF_RADIUS.
 */
internal inline fun drawMarkerShape(
  marker: Char, left: Float, top: Float,
  circle: (cx: Float, cy: Float, radius: Float, stroke: Float) -> Unit,
  line: (x0: Float, y0: Float, x1: Float, y1: Float, width: Float) -> Unit,
  rect: (x: Float, y: Float, w: Float, h: Float, stroke: Float) -> Unit,
) {
  val cx = left + MARKER_CELL_PX / 2f
  val cy = top + MARKER_CELL_PX / 2f
  val r = MARKER_SIZE_PX / 2f - MARKER_INSET
  val width = r / 5
  when (marker) {
    MARKER_DOT -> circle(cx, cy, r / 3, 0f)
    MARKER_DISC -> circle(cx, cy, MARKER_DISC_RADIUS_PX, 0f)
    MARKER_PLUS -> {
      line(cx - r, cy, cx + r, cy, width)
      line(cx, cy - r, cx, cy + r, width)
    }
    MARKER_CROSS -> {
      line(cx - r, cy - r, cx + r, cy + r, width)
      line(cx - r, cy + r, cx + r, cy - r, width)
    }
    MARKER_TICK -> line(cx, cy, cx, cy - r, width)
    MARKER_CIRCLE -> circle(cx, cy, r, width)
    MARKER_SQUARE -> rect(cx - r, cy - r, 2 * r, 2 * r, width)
    // вся ячейка залита: края SDF нет, середина растягивается на любой прямоугольник
    MARKER_FILL -> rect(left, top, MARKER_CELL_PX.toFloat(), MARKER_CELL_PX.toFloat(), 0f)
  }
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
