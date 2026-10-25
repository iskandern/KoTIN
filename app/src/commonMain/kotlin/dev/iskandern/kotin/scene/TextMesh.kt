package dev.iskandern.kotin.scene

import dev.iskandern.kotin.dxf.DxfColors
import dev.iskandern.kotin.dxf.DxfTextBackground
import dev.iskandern.kotin.dxf.FACE_DEFAULT
import dev.iskandern.kotin.dxf.MTEXT_COLUMN_BREAK
import dev.iskandern.kotin.dxf.MTEXT_NBSP
import dev.iskandern.kotin.dxf.MTextFrame
import dev.iskandern.kotin.dxf.POINT_MODE_UNSET
import dev.iskandern.kotin.dxf.TEXT_ALIGN_UNDER_DESCENT
import dev.iskandern.kotin.dxf.TEXT_BACKGROUND_WINDOW
import dev.iskandern.kotin.dxf.TEXT_COLOR_ENTITY
import dev.iskandern.kotin.dxf.TEXT_OVERLINE
import dev.iskandern.kotin.dxf.TEXT_STRIKE
import dev.iskandern.kotin.dxf.TEXT_UNDERLINE
import dev.iskandern.kotin.dxf.mTextLine
import dev.iskandern.kotin.model.NO_TICK
import dev.iskandern.kotin.model.TICK_DP
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.math.sqrt

/**
 * Подписи и точки в 3D без CPU на кадр. Глифы и значки точек лежат в одном атласе — SDF-текстуре
 * ([GlyphAtlas]), каждая подпись — набор квадов по символу. Положение, размер и поворот квадов считает
 * вершинный шейдер ([TEXT_VERTEX]) по камере, так что кадр не требует ни прохода по подписям, ни проекций.
 */

/** Размер шрифта в атласе, px; мировой размер подписи — высота текста DXF, масштаб — отношение к этому размеру. */
const val ATLAS_FONT_PX = 40

/**
 * Радиус SDF, px атласа: расстояние до контура кодируется в диапазоне ±радиус. Снаружи поле нужно и под ореол
 * ([HALO_DP] при читаемом кегле — около 7 px атласа).
 */
const val ATLAS_SDF_RADIUS = 8

/** Поля вокруг символа в ячейке атласа, px: хватает на SDF. */
const val ATLAS_PAD = ATLAS_SDF_RADIUS + 1

/**
 * Номинальная сторона значка точки, px атласа: к ней относятся размеры значка (`$PDSIZE`, [MARKER_DEFAULT_DP]); фигура
 * вписана в неё с отступом.
 */
const val MARKER_SIZE_PX = 56

/**
 * Радиус SDF значков точек, px атласа — больше, чем у букв: ореол выбранной точки до 4 dp при значке по умолчанию
 * (16 dp на 56 px — 3,5 px на dp) лежит внутри поля. В шейдере значок — квад с `fit` < 0 ([TEXT_VERTEX]).
 */
const val MARKER_SDF_RADIUS = 20

/**
 * Предел px атласа на dp ореола значка: 4 dp ореола — 16 px, с запасом на сглаживание внутри поля [MARKER_SDF_RADIUS].
 * У значка по умолчанию на dp 3,5 px — предел не действует; у мелких значков ореолы ужимаются.
 */
const val MARKER_HALO_PX_PER_DP = 4f

/** Сторона ячейки значка, px атласа: фигура с толщиной штриха (до ±25 px от центра) и поле SDF вокруг. */
const val MARKER_CELL_PX = 92

/**
 * Сторона квада точки сцены, px атласа: номинальная сторона с полем под белый ореол ([POINT_HALO_DP] — не больше
 * [MARKER_HALO_PX_PER_DP] px на dp) и сглаживание. Остальное поле ячейки нужно только ореолу выбранной точки, а у
 * обычных точек оно лишь увеличивало бы перекрытие пикселей ([buildSelectedMarksMesh] берёт ячейку целиком).
 */
const val MARKER_QUAD_PX = MARKER_SIZE_PX + 2 * ATLAS_PAD

/** Значки точек лежат в атласе под символами из области частного использования. */
const val MARKER_DOT = ''
const val MARKER_PLUS = ''
const val MARKER_CROSS = ''
const val MARKER_TICK = ''
const val MARKER_CIRCLE = ''
const val MARKER_SQUARE = ''
/** Сплошная ячейка: линии подчёркивания и плашки под текстом. */
const val MARKER_FILL = ''
/** Диск на всю фигуру значка: кружок номера кандидата выбора ([buildCandidateMarksMesh]). */
const val MARKER_DISC = ''
val MARKER_CHARS = listOf(MARKER_DOT, MARKER_PLUS, MARKER_CROSS, MARKER_TICK, MARKER_CIRCLE, MARKER_SQUARE, MARKER_FILL, MARKER_DISC)

/** Ячейка символа в атласе: UV и размеры в px атласа. [advance] — шаг пера. */
class Glyph(
  val u0: Float, val v0: Float, val u1: Float, val v1: Float,
  val cellWidth: Int,
  val advance: Float,
)

/** Квадратная ячейка значка, обрезанная вокруг центра до стороны [sidePx]: UV — внутренняя часть ячейки. */
internal fun Glyph.markerInner(sidePx: Int): Glyph {
  val cut = (cellWidth - sidePx) / 2f / cellWidth
  return Glyph(u0 + (u1 - u0) * cut, v0 + (v1 - v0) * cut, u1 - (u1 - u0) * cut, v1 - (v1 - v0) * cut, sidePx, advance)
}

/**
 * Символы одной гарнитуры в атласе: [cellHeight] и [baselineFromTop] общие для её ячеек, px атласа; [capHeight] и
 * [descent] — высота заглавных и глубина подстрочных относительно кегля.
 */
class GlyphFace(
  val glyphs: Map<Char, Glyph>,
  val cellHeight: Int,
  val baselineFromTop: Float,
  val capHeight: Float = FACE_CAP_HEIGHT[FACE_DEFAULT],
  val descent: Float = FACE_DESCENT[FACE_DEFAULT],
)

/**
 * [rgba] — атлас RGBA8 ([width] × [height]), SDF в альфа-канале: 0,5 — контур, больше — внутри.
 * [glyphs], [cellHeight] и [baselineFromTop] — шрифт интерфейса (Golos Text) и значки точек, [faces] — остальные гарнитуры.
 */
class GlyphAtlas(
  val width: Int,
  val height: Int,
  val rgba: ByteArray,
  val glyphs: Map<Char, Glyph>,
  val cellHeight: Int,
  val baselineFromTop: Float,
  val faces: Map<Int, GlyphFace> = emptyMap(),
) {
  private val defaultFace = GlyphFace(glyphs, cellHeight, baselineFromTop)

  /** Символы гарнитуры [face]; её нет в атласе — шрифт интерфейса. */
  fun face(face: Int): GlyphFace = faces[face] ?: defaultFace
}

/**
 * Высота заглавных и глубина подстрочных (буква «p») к кеглю по гарнитурам — по файлам `composeResources/font`
 * (проверяет `DxfFontTest`): Golos Text, Arimo, Tinos, Carlito, PT Sans Narrow, JetBrains Mono ×3.
 */
internal val FACE_CAP_HEIGHT = floatArrayOf(0.7f, 0.688f, 0.6548f, 0.6416f, 0.7f, 0.73f, 0.73f, 0.73f)
internal val FACE_DESCENT = floatArrayOf(0.2f, 0.2075f, 0.2129f, 0.1611f, 0.2f, 0.18f, 0.18f, 0.18f)

/**
 * Квады одного слоя: [positions] — точка вставки (одинакова у всех четырёх вершин символа), [uvs] — угол ячейки в
 * атласе, [colors] — RGBA8, [corners] — (x, y смещения угла от пера в px атласа, высота текста, «запас места» подписи
 * для отсева; −1 — не отсеивать), [along] — направление строки в горизонтальной плоскости сцены.
 */
class TextLayerMesh(
  val layer: String,
  val positions: FloatArray,
  val uvs: FloatArray,
  val colors: ByteArray,
  val corners: FloatArray,
  val along: FloatArray,
  val indices: IntArray,
) {
  /** Число прямоугольников; читается только в тестах. */
  val quadCount: Int get() = indices.size / 6
}

/** Подписи и значки точек сцены по слоям: отдельно, чтобы включать независимо. */
class TextMeshes(val labels: List<TextLayerMesh>, val points: List<TextLayerMesh>)

fun buildTextMeshes(scene: RenderScene, atlas: GlyphAtlas): TextMeshes {
  val all = layoutMTextLabels(scene.labels, atlas)
  val placement = placeLabels(all, atlas)
  val labels = all.indices.groupBy { all[it].layer }
    .map { (layer, indices) -> buildLabelMesh(layer, indices, all, placement, atlas) }
  val points = scene.points.groupBy { it.layer }.map { (layer, items) -> buildPointMesh(layer, items, atlas, scene) }
  return TextMeshes(labels, points)
}

/** [contrast] — цвета подстраиваются под светлый фон ([DxfColors.onBackground]); у подсветки выбранного — нет. */
private class QuadSink(capacity: Int, val layer: String, val contrast: Boolean = true) {
  val positions = FloatArray(capacity * 4 * 3)
  val uvs = FloatArray(capacity * 4 * 2)
  val colors = ByteArray(capacity * 4 * 4)
  val corners = FloatArray(capacity * 4 * 4)
  val along = FloatArray(capacity * 4 * 2)
  var quads = 0

  /** Квад символа: [x0]…[x1] и [y0]…[y1] — смещения от пера в px атласа (y вверх), UV — ячейка [glyph]. */
  fun add(
    x: Float, y: Float, z: Float, rgb: Int, glyph: Glyph,
    x0: Float, y0: Float, x1: Float, y1: Float,
    height: Float, fit: Float, alongX: Float, alongZ: Float,
    shear: Float = 0f,
  ) {
    for (corner in 0 until 4) {
      val v = quads * 4 + corner
      // углы: 0 — верх-лево, 1 — верх-право, 2 — низ-право, 3 — низ-лево
      val right = corner == 1 || corner == 2
      val top = corner < 2
      positions[v * 3] = x
      positions[v * 3 + 1] = y
      positions[v * 3 + 2] = z
      uvs[v * 2] = if (right) glyph.u1 else glyph.u0
      // материал переворачивает v (flipUV в Filament по умолчанию): строка атласа y — это v = 1 − y / высота
      uvs[v * 2 + 1] = 1f - (if (top) glyph.v0 else glyph.v1)
      val c = if (contrast) DxfColors.onBackground(rgb) else rgb
      colors[v * 4] = (c shr 16).toByte()
      colors[v * 4 + 1] = (c shr 8).toByte()
      colors[v * 4 + 2] = c.toByte()
      colors[v * 4 + 3] = -1
      val cornerY = if (top) y1 else y0
      // наклон букв: угол сдвигается вдоль строки пропорционально высоте
      corners[v * 4] = (if (right) x1 else x0) + cornerY * shear
      corners[v * 4 + 1] = cornerY
      corners[v * 4 + 2] = height
      corners[v * 4 + 3] = fit
      along[v * 2] = alongX
      along[v * 2 + 1] = alongZ
    }
    quads++
  }

  fun build(): TextLayerMesh {
    val n = quads
    val indices = IntArray(n * 6)
    for (q in 0 until n) {
      val v = q * 4
      indices[q * 6] = v
      indices[q * 6 + 1] = v + 1
      indices[q * 6 + 2] = v + 2
      indices[q * 6 + 3] = v
      indices[q * 6 + 4] = v + 2
      indices[q * 6 + 5] = v + 3
    }
    return TextLayerMesh(
      layer,
      positions.copyOf(n * 12), uvs.copyOf(n * 8), colors.copyOf(n * 16), corners.copyOf(n * 16), along.copyOf(n * 8),
      indices,
    )
  }
}

private fun buildLabelMesh(
  layer: String, indices: List<Int>, all: List<RenderLabel>, placement: LabelPlacement, atlas: GlyphAtlas, contrast: Boolean = true,
): TextLayerMesh {
  val capacity = indices.sumOf { i ->
    val label = all[i]
    val glyphs = atlas.face(label.face).glyphs
    label.text.count { (glyphs[it]?.cellWidth ?: 0) > 0 } +
      (label.runs?.sumOf { it.decoration.countOneBits() } ?: 0) + (label.background?.let { if (it.edge > 0) 2 else 1 } ?: 0) +
      (if (label.tickRgb != NO_TICK) 1 else 0)
  }
  val sink = QuadSink(capacity, layer, contrast)
  val fill = atlas.glyphs[MARKER_FILL]
  // плашки — под буквами всех строк слоя, канты — под заливками всех строк: плашки соседних строк сливаются в одну
  for (pass in 0..2) for (i in indices) {
    val label = all[i]
    if (pass < 2 && (label.background == null || fill == null)) continue
    val face = atlas.face(label.face)
    val below = face.cellHeight - face.baselineFromTop
    val shape = labelShape(label, atlas)
    val width = shape.widthFactor
    // подъём над точкой (dp) — в px атласа при кегле подписи
    val lift = labelLift(label, face)
    val shiftY = verticalShift(label.alignV, face) + lift
    // наклон отсчитывается от базовой линии, а не от точки выравнивания
    val slant = -shiftY * label.shear
    val textWidth = labelWidth(label, face)
    // сдвиг от точки (dp, у подписей рядом со значком) — общий для букв и плашки
    val shift = label.shiftDp * atlasPerDp(label, face) / width
    val start = -textWidth * horizontalFactor(label.alignH) + shift
    // высота текста в DXF — высота заглавных букв, а кегль шрифта больше неё
    val fontHeight = shape.height / face.capHeight
    val fit = placement.fit[i]
    // нулевое направление шейдер кладёт горизонтально на экране (dir = (1, 0)) — так рисуются подписи screenHorizontal
    val alongX = if (label.screenHorizontal) 0f else label.alongX
    val alongZ = if (label.screenHorizontal) 0f else label.alongZ

    // прямоугольник от пера: x — по строке до ширины букв, y — от базовой линии
    fun rect(glyph: Glyph, rgb: Int, x0: Float, y0: Float, x1: Float, y1: Float) = sink.add(
      label.x, label.y, label.z, rgb, glyph,
      x0 * width + slant, y0 + shiftY, x1 * width + slant, y1 + shiftY,
      fontHeight, fit, alongX, alongZ, label.shear,
    )

    if (pass < 2) {
      val background = label.background ?: continue
      val fillGlyph = fill ?: continue
      val rgb = when (background.color) {
        TEXT_BACKGROUND_WINDOW -> WINDOW_RGB
        TEXT_COLOR_ENTITY -> label.color
        else -> background.color
      }
      val cap = ATLAS_FONT_PX * face.capHeight
      val margin = (background.margin * cap).toFloat()
      // ширина рамки MTEXT — мировая, в px атласа при кегле строки
      val boxWidth = if (background.width > 0 && fontHeight > 0) (background.width * ATLAS_FONT_PX / fontHeight).toFloat() / width else textWidth
      val left = -boxWidth * horizontalFactor(label.alignH) + shift
      // кант — тот же прямоугольник шире на edge, под плашкой (порядок квадов — порядок рисования)
      if (pass == 0) {
        if (background.edge > 0) {
          val outer = margin + (background.edge * cap).toFloat()
          rect(fillGlyph, background.edgeColor, left - outer / width, -outer, left + boxWidth + outer / width, cap + outer)
        }
      } else {
        rect(fillGlyph, rgb, left - margin / width, -margin, left + boxWidth + margin / width, cap + margin)
      }
      continue
    }
    // засечка: от точки (y = −shiftY в координатах строки) вверх на TICK_DP, посередине точки вставки
    if (label.tickRgb != NO_TICK && fill != null && lift > 0f) {
      val k = lift / label.liftDp
      val half = TICK_WIDTH_DP * k / 2 / width
      rect(fill, label.tickRgb, -half - slant / width, -shiftY, half - slant / width, -shiftY + TICK_DP * k)
    }

    val runs = label.runs
    var run = 0
    var runStart = start
    var pen = start
    for ((index, ch) in label.text.withIndex()) {
      val scale = if (runs != null) runs[run].scale.toFloat() else 1f
      val rgb = if (runs != null && runs[run].color != TEXT_COLOR_ENTITY) runs[run].color else label.color
      val glyph = face.glyphs[ch]
      if (glyph != null && glyph.cellWidth > 0) {
        rect(
          glyph, rgb,
          pen - ATLAS_PAD * scale, -below * scale, pen + (glyph.cellWidth - ATLAS_PAD) * scale, face.baselineFromTop * scale,
        )
      }
      pen += (glyph?.advance ?: 0f) * scale
      // конец фрагмента: его линии и переход к следующему
      if (runs != null && (index + 1 >= runs[run].end || index == label.text.length - 1)) {
        val decoration = runs[run].decoration
        if (decoration != 0 && fill != null) {
          val thickness = DECORATION_THICKNESS * scale
          for (bit in DECORATIONS) {
            if (decoration and bit == 0) continue
            val y = decorationOffset(bit, face) * scale
            rect(fill, rgb, runStart, y - thickness / 2, pen, y + thickness / 2)
          }
        }
        runStart = pen
        if (run < runs.size - 1) run++
      }
    }
  }
  return sink.build()
}

private fun textWidth(text: String, face: GlyphFace): Float {
  var width = 0f
  for (ch in text) width += face.glyphs[if (ch == MTEXT_NBSP) ' ' else ch]?.advance ?: 0f
  return width
}

/** Шаг пера у каждого символа подписи, px атласа: у фрагментов MTEXT — со своей высотой букв. */
private fun advances(label: RenderLabel, face: GlyphFace): FloatArray {
  val text = label.text
  val runs = label.runs
  val result = FloatArray(text.length)
  var run = 0
  for (i in text.indices) {
    if (runs != null) while (run < runs.size - 1 && i >= runs[run].end) run++
    val ch = text[i]
    val advance = face.glyphs[if (ch == MTEXT_NBSP) ' ' else ch]?.advance ?: 0f
    result[i] = if (runs != null) advance * runs[run].scale.toFloat() else advance
  }
  return result
}

/** Ширина строки подписи в px атласа (без ширины букв). */
internal fun labelWidth(label: RenderLabel, face: GlyphFace): Float =
  if (label.runs == null) textWidth(label.text, face) else advances(label, face).sum()

/** Цвет окна — фон сцены (`BACKGROUND` в SceneView) для плашки MTEXT с флагом 2. */
private const val WINDOW_RGB = DxfColors.BACKGROUND

// линии фрагментов: подчёркивание, надчёркивание (над заглавными), зачёркивание
private const val DECORATION_THICKNESS = ATLAS_FONT_PX * 0.06f
private val DECORATIONS = listOf(TEXT_UNDERLINE, TEXT_OVERLINE, TEXT_STRIKE)

/** Высота линии фрагмента [bit] над базовой линией, px атласа. */
private fun decorationOffset(bit: Int, face: GlyphFace) = when (bit) {
  TEXT_UNDERLINE -> -ATLAS_FONT_PX * 0.12f
  TEXT_OVERLINE -> ATLAS_FONT_PX * face.capHeight * 1.2f
  else -> ATLAS_FONT_PX * face.capHeight * 0.5f
}

/**
 * MTEXT с рамкой ([RenderLabel.frame]) → по подписи на строку. Абзацы переносятся по словам по ширине символов
 * атласа (тот же шрифт, что рисует подписи), не шире рамки; слово длиннее рамки остаётся целым. В колонках строки
 * идут сверху вниз, пока не кончится высота колонки, затем — в следующую; разрыв колонки (\N) начинает новую.
 * Привязка (код 71) — от рамки: у колонок от общей ширины, по вертикали — от высоты текста (или рамки колонок).
 * Остальные подписи — как есть.
 */
internal fun layoutMTextLabels(labels: List<RenderLabel>, atlas: GlyphAtlas): List<RenderLabel> {
  if (labels.none { it.frame != null }) return labels
  val result = ArrayList<RenderLabel>(labels.size)
  for (label in labels) {
    val frame = label.frame
    if (frame == null) result.add(label) else layoutMText(label, frame, atlas, result)
  }
  return result
}

private fun layoutMText(label: RenderLabel, frame: MTextFrame, atlas: GlyphAtlas, out: MutableList<RenderLabel>) {
  val face = atlas.face(label.face)
  // мировая длина пикселя атласа вдоль строки
  val scale = label.height / face.capHeight / ATLAS_FONT_PX * label.widthFactor
  val maxWidth = if (scale > 0f && frame.width > 0) (frame.width / scale).toFloat() else Float.POSITIVE_INFINITY
  val columns = frame.columnWidth > 0
  val text = label.text
  val advances = advances(label, face)
  // строки — отрезки текста [starts, ends); строки, с которых начинается колонка по разрыву \N
  val starts = ArrayList<Int>()
  val ends = ArrayList<Int>()
  val breaks = HashSet<Int>()
  var paragraph = 0
  for (i in 0..text.length) {
    val c = if (i < text.length) text[i] else '\n'
    if (c != '\n' && c != MTEXT_COLUMN_BREAK) continue
    wrapWords(text, paragraph, i, advances, maxWidth, starts, ends)
    if (c == MTEXT_COLUMN_BREAK) breaks.add(starts.size)
    paragraph = i + 1
  }
  // высота строки — наибольшая у её фрагментов (\H)
  val lines = starts.indices.map { mTextLine(label.runs, starts[it], ends[it]) }

  val height = label.height
  val pitch = frame.pitch.toFloat()
  // высота колонки: свои высоты по порядку (последняя берёт остаток) или общая высота рамки
  fun columnLimit(column: Int): Float = when {
    frame.columnHeights.isNotEmpty() ->
      if (column >= frame.columnHeights.size - 1) Float.POSITIVE_INFINITY else frame.columnHeights[column].toFloat()
    frame.height > 0 -> frame.height.toFloat()
    else -> Float.POSITIVE_INFINITY
  }
  val columnOf = IntArray(lines.size)
  // от верха колонки до базовой линии строки: первая — на свою высоту, следующие — на шаг по своей высоте
  val depthOf = FloatArray(lines.size)
  var column = 0
  var row = 0
  var depth = 0f
  var usedHeight = 0f
  for (i in lines.indices) {
    val scale = lines[i].scale.toFloat()
    if (columns && i > 0 && (i in breaks || (row > 0 && depth + pitch * scale > columnLimit(column) * (1 + 1e-4f)))) {
      column++
      row = 0
    }
    depth = if (row == 0) height * scale else depth + pitch * scale
    columnOf[i] = column
    depthOf[i] = depth
    usedHeight = maxOf(usedHeight, depth)
    row++
  }

  val anchor = frame.attachment.coerceIn(1, 9) - 1
  val alignH = anchor % 3
  val f = horizontalFactor(alignH)
  val blockHeight = if (columns && frame.height > 0) maxOf(frame.height.toFloat(), usedHeight) else usedHeight
  val top = when (anchor / 3) {
    0 -> 0f
    1 -> blockHeight / 2
    else -> blockHeight
  }
  val columnWidth = frame.columnWidth.toFloat()
  val step = columnWidth + frame.gutter.toFloat()
  val totalWidth = (column + 1) * step - frame.gutter.toFloat()
  for (i in lines.indices) {
    val line = text.substring(starts[i], ends[i]).replace(MTEXT_NBSP, ' ')
    if (line.isBlank()) continue
    // точка привязки строки вдоль и поперёк: у колонок — та же привязка внутри своей колонки
    val along = if (columns) -totalWidth * f + columnOf[i] * step + columnWidth * f else 0f
    val up = top - depthOf[i]
    // «вверх» строки на плане — поворот направления на 90° против часовой
    out.add(
      RenderLabel(
        label.layer, label.color,
        label.x + label.alongX * along + label.alongZ * up, label.y, label.z + label.alongZ * along - label.alongX * up,
        height * lines[i].scale.toFloat(), label.alongX, label.alongZ, line, alignH = alignH,
        widthFactor = label.widthFactor, shear = label.shear,
        runs = lines[i].runs, background = label.background, face = label.face,
      ),
    )
  }
}

/**
 * Переносит абзац [start, end) текста по пробелам не шире [maxWidth] px атласа ([advances] — шаг пера символов).
 * Строки — отрезки текста [starts], [ends] без пробелов в конце.
 */
private fun wrapWords(
  text: String, start: Int, end: Int, advances: FloatArray, maxWidth: Float, starts: MutableList<Int>, ends: MutableList<Int>,
) {
  fun trimmed(from: Int, to: Int): Int {
    var e = to
    while (e > from && text[e - 1].isWhitespace()) e--
    return e
  }
  val stop = trimmed(start, end)
  var lineStart = start
  var lineEnd = start
  var width = 0f
  var empty = true
  var wrapped = false
  // слова — между пробелами; пустое слово — лишний пробел
  var wordStart = start
  while (wordStart <= stop) {
    var wordEnd = wordStart
    var w = 0f
    while (wordEnd < stop && text[wordEnd] != ' ') w += advances[wordEnd++]
    if (!empty && wordEnd > wordStart && width + advances[wordStart - 1] + w > maxWidth) {
      starts.add(lineStart)
      ends.add(trimmed(lineStart, lineEnd))
      empty = true
      wrapped = true
    }
    if (empty) {
      // пробелы в начале перенесённой строки не нужны, в начале абзаца — отступ
      if (wordEnd > wordStart || !wrapped) {
        lineStart = wordStart
        lineEnd = wordEnd
        width = w
        empty = false
      }
    } else {
      width += advances[wordStart - 1] + w
      lineEnd = wordEnd
    }
    wordStart = wordEnd + 1
  }
  starts.add(lineStart)
  ends.add(trimmed(lineStart, lineEnd))
}

/** Высота букв (как в DXF) и ширина букв подписи после подгонки строки по длине. */
internal class LabelShape(val height: Float, val widthFactor: Float)

/**
 * «По ширине» (3) — строка растягивается между точками пропорционально, меняется высота; «вписанный» (5) —
 * высота из файла, меняется ширина букв. Остальные подписи — как в файле.
 */
internal fun labelShape(label: RenderLabel, atlas: GlyphAtlas): LabelShape {
  val face = atlas.face(label.face)
  val atlasWidth = labelWidth(label, face) * label.widthFactor
  if (label.fitLength <= 0f || atlasWidth <= 0f || label.height <= 0f) return LabelShape(label.height, label.widthFactor)
  // мировая ширина строки: px атласа × кегль / кегль атласа
  val k = label.fitLength / (atlasWidth * label.height / face.capHeight / ATLAS_FONT_PX)
  return if (label.alignH == 3) LabelShape(label.height * k, label.widthFactor) else LabelShape(label.height, label.widthFactor * k)
}

/** Доля ширины строки, на которую пером отступают влево от точки выравнивания: 0 слева, 1 по центру (и по середине), 2 справа. */
private fun horizontalFactor(alignH: Int) = when (alignH) {
  1, 4 -> 0.5f
  2 -> 1f
  else -> 0f
}

/**
 * Подъём строки над точкой, px атласа: [RenderLabel.liftDp] при кегле подписи в dp (кегль в px атласа — [ATLAS_FONT_PX]).
 * У мировых подписей (высота > 0) — 0.
 */
internal fun labelLift(label: RenderLabel, face: GlyphFace): Float = label.liftDp * atlasPerDp(label, face)

/** px атласа на dp у подписи с кеглем в dp (высота < 0); у мировых — 0: подъём и сдвиг к ним не применяются. */
private fun atlasPerDp(label: RenderLabel, face: GlyphFace): Float =
  if (label.height >= 0f) 0f else ATLAS_FONT_PX * face.capHeight / -label.height

// толщина засечки пикета, dp (длина — TICK_DP); строка — над ней, на подъёме подписи
private const val TICK_WIDTH_DP = 1.5f

/** Смещение базовой линии от точки выравнивания по вертикали в px атласа (вверх положительно). */
private fun verticalShift(alignV: Int, face: GlyphFace) = when (alignV) {
  1 -> ATLAS_FONT_PX * face.descent
  2 -> -ATLAS_FONT_PX * face.capHeight / 2
  3 -> -ATLAS_FONT_PX * face.capHeight
  TEXT_ALIGN_UNDER_DESCENT -> -ATLAS_FONT_PX * (face.capHeight + face.descent)
  else -> 0f
}

/** [fit] — запас места подписи в метрах на пиксель атласа (см. [placeLabels]); умножить на кегль атласа. */
class LabelPlacement(val fit: FloatArray)

/** Подписи одной точки как единый блок: положение, направление строки и размеры по осям строки (px атласа от точки). */
private class Spot(
  val x: Float, val y: Float, val z: Float,
  val ux: Float, val uz: Float,
  val height: Float,
  val alongLow: Float, val alongHigh: Float,
  val upLow: Float, val upHigh: Float,
  val members: List<Int>,
)

/**
 * Приоритетный отсев подписей, как на картах. Подписи в одной точке (например «0» над точкой и число под ней) — один
 * блок, они показываются и скрываются вместе. Блоки упорядочены по важности (крупнее текст — важнее, при равенстве —
 * по стабильному хешу места, чтобы выбор был равномерным). Для каждого считается, до какого масштаба текста он не
 * налезает ни на один более важный: прямоугольники блоков (с выравниванием, высотой и направлением строки) растут
 * от точек вставки пропорционально масштабу, и берётся наибольший масштаб без пересечения. Это «запас места»:
 * шейдер показывает подпись, пока её кегль на экране не больше запаса, иначе скрывает (не уменьшает).
 * Считается один раз при загрузке.
 */
internal fun placeLabels(labels: List<RenderLabel>, atlas: GlyphAtlas): LabelPlacement {
  val n = labels.size
  val fit = FloatArray(n)
  if (n == 0) return LabelPlacement(fit)

  val shapes = labels.map { labelShape(it, atlas) }
  fun cm(v: Float) = (v * 100.0).roundToLong()
  val byPoint = LinkedHashMap<Triple<Long, Long, Long>, ArrayList<Int>>()
  for (i in 0 until n) {
    val l = labels[i]
    byPoint.getOrPut(Triple(cm(l.x), cm(l.y), cm(l.z))) { ArrayList(2) }.add(i)
  }
  val spots = byPoint.values.map { members ->
    members.sortByDescending { kotlin.math.abs(shapes[it].height) }
    val lead = labels[members[0]]
    var alongLow = 0f
    var alongHigh = 0f
    var upLow = 0f
    var upHigh = 0f
    for (i in members) {
      val l = labels[i]
      val face = atlas.face(l.face)
      val width = labelWidth(l, face) * shapes[i].widthFactor * BOX_MARGIN_WIDTH
      val f = horizontalFactor(l.alignH)
      val shift = l.shiftDp * atlasPerDp(l, face)
      alongLow = minOf(alongLow, -f * width + shift)
      alongHigh = maxOf(alongHigh, (1 - f) * width + shift)
      val up = verticalShift(l.alignV, face) + labelLift(l, face)
      upLow = minOf(upLow, up)
      upHigh = maxOf(upHigh, up + ATLAS_FONT_PX * face.capHeight * BOX_MARGIN_HEIGHT)
    }
    // горизонтальная на экране строка на плане считается идущей на восток — так она лежит при виде сверху
    val ux = if (lead.screenHorizontal) 1f else lead.alongX
    val uz = if (lead.screenHorizontal) 0f else lead.alongZ
    // важность — по кеглю: у мировых подписей в метрах, у экранных (высота < 0) в dp
    Spot(lead.x, lead.y, lead.z, ux, uz, kotlin.math.abs(shapes[members[0]].height), alongLow, alongHigh, upLow, upHigh, members)
  }
  val m = spots.size

  var maxExtent = 0f
  for (spot in spots) maxExtent = maxOf(maxExtent, spot.alongHigh - spot.alongLow, spot.upHigh - spot.upLow)
  // любые два блока не пересекаются при масштабе не больше расстояния / (суммарный размер по диагонали)
  val reach = 2f * kotlin.math.sqrt(2f) * maxExtent + 1e-3f

  val order = (0 until m).sortedWith(
    compareByDescending<Int> { spots[it].height }.thenBy { hash01(spots[it].x, spots[it].z) },
  )
  var minX = Float.POSITIVE_INFINITY
  var maxX = Float.NEGATIVE_INFINITY
  var minZ = Float.POSITIVE_INFINITY
  var maxZ = Float.NEGATIVE_INFINITY
  for (spot in spots) {
    minX = minOf(minX, spot.x); maxX = maxOf(maxX, spot.x)
    minZ = minOf(minZ, spot.z); maxZ = maxOf(maxZ, spot.z)
  }
  val cell = maxOf(kotlin.math.sqrt(maxOf((maxX - minX) * (maxZ - minZ), 1f) / m) * 2f, 1e-3f)
  val maxRing = (maxOf(maxX - minX, maxZ - minZ) / cell).toInt() + 2
  fun cellOf(v: Float, min: Float) = ((v - min) / cell).toInt()
  fun cellKey(cx: Int, cz: Int) = (cx.toLong() shl 32) or (cz.toLong() and 0xFFFFFFFFL)
  val grid = HashMap<Long, ArrayList<Int>>()

  for (i in order) {
    val a = spots[i]
    val cx = cellOf(a.x, minX)
    val cz = cellOf(a.z, minZ)
    var best = Float.POSITIVE_INFINITY
    var ring = 0
    while (ring <= maxRing) {
      // блоки дальше (ring−1) ячеек не могут дать запас меньше (ring−1)·cell / reach
      if (best.isFinite() && best <= (ring - 1) * cell / reach) break
      for (dx in -ring..ring) for (dz in -ring..ring) {
        if (maxOf(kotlin.math.abs(dx), kotlin.math.abs(dz)) != ring) continue
        val others = grid[cellKey(cx + dx, cz + dz)] ?: continue
        for (j in others) best = minOf(best, separationScale(a, spots[j]))
      }
      ring++
    }
    grid.getOrPut(cellKey(cx, cz)) { ArrayList(2) }.add(i)
    val value = if (best.isFinite()) best * ATLAS_FONT_PX else FIT_UNLIMITED
    for (member in a.members) fit[member] = value
  }
  return LabelPlacement(fit)
}

/**
 * Наибольший масштаб текста (метров на пиксель атласа), при котором блок [a] не пересекает блок [b]: размеры
 * растут от точек вставки вместе с масштабом. Оси — направление строки [a] и перпендикуляр к нему на плане.
 */
private fun separationScale(a: Spot, b: Spot): Float {
  val dx = b.x - a.x
  val dz = b.z - a.z
  val alongOffset = dx * a.ux + dz * a.uz
  // «вверх» строки на плане — поворот направления на 90° против часовой (z сцены смотрит на юг)
  val upOffset = dx * a.uz - dz * a.ux
  val along = overlapThreshold(alongOffset, a.alongLow, a.alongHigh, b.alongLow, b.alongHigh)
  val up = overlapThreshold(upOffset, a.upLow, a.upHigh, b.upLow, b.upHigh)
  // блоки не пересекаются, пока разделены хотя бы по одной оси
  return maxOf(along, up)
}

/**
 * Масштаб, начиная с которого интервалы [aLow, aHigh] (у начала координат) и [bLow, bHigh] (у точки [offset])
 * пересекаются, если растут от своих точек пропорционально масштабу; бесконечность — не пересекутся никогда.
 */
private fun overlapThreshold(offset: Float, aLow: Float, aHigh: Float, bLow: Float, bHigh: Float): Float {
  val forward = aHigh - bLow
  val backward = bHigh - aLow
  return when {
    offset > 0f -> if (forward > 0f) offset / forward else Float.POSITIVE_INFINITY
    offset < 0f -> if (backward > 0f) -offset / backward else Float.POSITIVE_INFINITY
    else -> if (forward > 0f && backward > 0f) 0f else Float.POSITIVE_INFINITY
  }
}

// запас вокруг строки: на ширину и на высоту строки
private const val BOX_MARGIN_WIDTH = 1.1f
private const val BOX_MARGIN_HEIGHT = 1.4f

private const val FIT_UNLIMITED = 1e9f

private fun buildPointMesh(layer: String, points: List<RenderPoint>, atlas: GlyphAtlas, scene: RenderScene): TextLayerMesh {
  // без поля под ореол выбранной точки: квад меньше ячейки, фигура на экране та же (высота — от MARKER_SIZE_PX)
  val parts = markerParts(scene.pointMode).mapNotNull { atlas.glyphs[it]?.markerInner(MARKER_QUAD_PX) }
  val sink = QuadSink(points.size * parts.size, layer)
  val height = markerHeight(scene.pointMode, scene.pointSize)
  for (point in points) {
    for (glyph in parts) sink.addMarker(point.x, point.y, point.z, point.color, glyph, height, point.alongX, point.alongZ)
  }
  return sink.build()
}

/**
 * Высота квада значка для шейдера: номинальная сторона значка в мировых метрах ([size] > 0 при заданном [mode]) или
 * в dp (отрицательная, [MARKER_DEFAULT_DP]); размер видимой фигуры ≈ 0,8 номинальной стороны.
 */
private fun markerHeight(mode: Int, size: Float): Float = when {
  mode == POINT_MODE_UNSET || size <= 0f -> -MARKER_DEFAULT_DP * ATLAS_FONT_PX / MARKER_SIZE_PX
  else -> size * MARKER_CELL_SCALE * ATLAS_FONT_PX / MARKER_SIZE_PX
}

// квад значка — ячейка глифа (вся [MARKER_CELL_PX] с полем SDF или обрезанная [markerInner]); fit −1 — значок, не прореживается
private fun QuadSink.addMarker(x: Float, y: Float, z: Float, rgb: Int, glyph: Glyph, height: Float, alongX: Float, alongZ: Float) {
  val half = glyph.cellWidth / 2f
  add(x, y, z, rgb, glyph, -half, -half, half, half, height, -1f, alongX, alongZ)
}

/**
 * Подсветка подписей выбранного объекта: тот же текст цветом [rgb] на плашке [plateRgb] с кантом цвета [rgb] — как у
 * значка точки и обводки линии. Подписи без точки применения (значение на участке) выделяются только так, без кружка.
 */
fun buildSelectedLabelsMesh(labels: List<RenderLabel>, rgb: Int, plateRgb: Int, atlas: GlyphAtlas): TextLayerMesh? {
  if (labels.isEmpty()) return null
  val lines = layoutMTextLabels(labels, atlas)
  val widths = stackedWidths(lines, atlas)
  val all = lines.mapIndexed { index, it ->
    // поле плашки — одинаковое со всех сторон и не меньше хвостов («,», «р») с ореолом: подпись под плашкой та же,
    // и её белый ореол иначе выглядывал из-под плашки чертой
    val face = atlas.face(it.face)
    val margin = maxOf(SELECTED_PLATE_MARGIN, (face.descent + HALO_MAX_OF_FONT) / face.capHeight.toDouble())
    RenderLabel(
      it.layer, rgb, it.x, it.y, it.z, it.height, it.alongX, it.alongZ, it.text, it.alignH, it.alignV,
      it.widthFactor, it.shear, it.fitLength, null, null,
      DxfTextBackground(plateRgb, margin, widths[index].toDouble(), rgb, SELECTED_PLATE_EDGE),
      it.face, it.objectId, it.screenHorizontal, it.liftDp, it.shiftDp, it.tickRgb,
    )
  }
  return buildLabelMesh("", all.indices.toList(), all, placeLabels(all, atlas), atlas, contrast = false)
}

/**
 * Ширина плашки, мировая, по подписям [labels]: у строк одного абзаца (идут подряд друг под другом, одно направление
 * и выравнивание) — по самой длинной, чтобы плашки строк слились в прямоугольник; 0 — по своему тексту.
 */
private fun stackedWidths(labels: List<RenderLabel>, atlas: GlyphAtlas): FloatArray {
  val widths = FloatArray(labels.size)
  var start = 0
  for (i in 1..labels.size) {
    if (i < labels.size && isNextLine(labels[i - 1], labels[i])) continue
    if (i - start > 1) {
      var max = 0f
      for (k in start until i) {
        val label = labels[k]
        val face = atlas.face(label.face)
        val shape = labelShape(label, atlas)
        // подсветка рисуется без фрагментов (runs) — ширина по тексту целиком
        max = maxOf(max, textWidth(label.text, face) * shape.widthFactor * shape.height / face.capHeight / ATLAS_FONT_PX)
      }
      for (k in start until i) widths[k] = max
    }
    start = i
  }
  return widths
}

// [next] — следующая строка того же абзаца, что [prev]: мировые, одно направление и выравнивание, точка — поперёк строки
// не дальше трёх высот
private fun isNextLine(prev: RenderLabel, next: RenderLabel): Boolean {
  if (prev.height <= 0f || next.height <= 0f || prev.screenHorizontal || next.screenHorizontal) return false
  if (prev.alignH != next.alignH || prev.alongX != next.alongX || prev.alongZ != next.alongZ || prev.face != next.face) return false
  val dx = next.x - prev.x
  val dy = next.y - prev.y
  val dz = next.z - prev.z
  val h = maxOf(prev.height, next.height)
  return abs(dx * prev.alongX + dz * prev.alongZ) < h * 0.01f && sqrt(dx * dx + dy * dy + dz * dz) < h * 3
}

// поле плашки подсвеченной подписи, доли высоты заглавных; ореол — не шире восьмой части кегля (TEXT_FRAGMENT)
private const val SELECTED_PLATE_MARGIN = 0.3
// кант плашки, доли высоты заглавных: ≈1 dp при читаемом кегле (READABLE_TEXT_DP), растёт вместе с подписью
private const val SELECTED_PLATE_EDGE = 0.12
private const val HALO_MAX_OF_FONT = 1.0 / 8

/**
 * Значки выбранного объекта цветом [rgb]: у точек [points] — тот же набор по `$PDMODE` [mode] и того же размера, что в
 * сцене; у подписей без геометрии ([labelAnchors], по три числа) — точка значка по умолчанию. Силуэт выделяют ореолы
 * прохода [TextQuads]. Режим без фигуры (`$PDMODE` 1) — тоже точкой. null — значков нет.
 */
fun buildSelectedMarksMesh(
  points: List<RenderPoint>, labelAnchors: FloatArray, mode: Int, size: Float, rgb: Int, atlas: GlyphAtlas,
): TextLayerMesh? {
  val dot = atlas.glyphs[MARKER_DOT]
  val parts = markerParts(mode).mapNotNull { atlas.glyphs[it] }.ifEmpty { listOfNotNull(dot) }
  val labels = if (dot == null) 0 else labelAnchors.size / 3
  val count = points.size * parts.size + labels
  if (count == 0) return null
  val sink = QuadSink(count, "", contrast = false)
  val height = markerHeight(mode, size)
  for (point in points) {
    for (glyph in parts) sink.addMarker(point.x, point.y, point.z, rgb, glyph, height, point.alongX, point.alongZ)
  }
  if (dot != null) {
    val dotHeight = markerHeight(POINT_MODE_UNSET, 0f)
    for (i in 0 until labels) sink.addMarker(labelAnchors[i * 3], labelAnchors[i * 3 + 1], labelAnchors[i * 3 + 2], rgb, dot, dotHeight, 1f, 0f)
  }
  return sink.build()
}

/**
 * Номера кандидатов выбора у мест попадания [hits] (по три числа, координаты сцены с вертикальным масштабом): кружок
 * [CANDIDATE_MARK_DP] цвета [fillRgb] с номером [inkRgb], выбранный [selected] — [selectedFillRgb] с [selectedInkRgb].
 * Кружок правее и выше точки ([candidateMarkShifts]); размеры — dp на экране, номер горизонтален на экране. Кружки
 * идут по номерам, выбранный — последним, поверх соседей. Меньше двух кандидатов — null.
 */
fun buildCandidateMarksMesh(
  hits: FloatArray, selected: Int, fillRgb: Int, inkRgb: Int, selectedFillRgb: Int, selectedInkRgb: Int, atlas: GlyphAtlas,
): TextLayerMesh? {
  val disc = atlas.glyphs[MARKER_DISC] ?: return null
  val count = hits.size / 3
  if (count < 2) return null
  val face = atlas.face(FACE_DEFAULT)
  val shifts = candidateMarkShifts(hits)
  val numbers = (1..count).map { it.toString() }
  val sink = QuadSink(count + numbers.sumOf { it.length }, "", contrast = false)
  // у кружка px атласа на dp — по диаметру фигуры, у номера — по кеглю
  val discPerDp = 2f * MARKER_DISC_RADIUS_PX / CANDIDATE_MARK_DP
  val discHeight = -ATLAS_FONT_PX / discPerDp
  val digitPerDp = ATLAS_FONT_PX / CANDIDATE_DIGIT_DP
  val half = disc.cellWidth / 2f
  val below = face.cellHeight - face.baselineFromTop
  val order = (0 until count).filter { it != selected } + listOfNotNull(selected.takeIf { it in 0 until count })
  for (i in order) {
    val x = hits[i * 3]
    val y = hits[i * 3 + 1]
    val z = hits[i * 3 + 2]
    val on = i == selected
    val cx = shifts[i]
    val cy = CANDIDATE_SHIFT_DP
    // нулевое направление строки — горизонтально на экране; кружок — значок (fit −1), номер не прореживается
    sink.add(
      x, y, z, if (on) selectedFillRgb else fillRgb, disc,
      cx * discPerDp - half, cy * discPerDp - half, cx * discPerDp + half, cy * discPerDp + half, discHeight, -1f, 0f, 0f,
    )
    val text = numbers[i]
    var pen = cx * digitPerDp - textWidth(text, face) / 2
    val base = cy * digitPerDp - ATLAS_FONT_PX * face.capHeight / 2
    for (ch in text) {
      val glyph = face.glyphs[ch] ?: continue
      if (glyph.cellWidth > 0) {
        sink.add(
          x, y, z, if (on) selectedInkRgb else inkRgb, glyph,
          pen - ATLAS_PAD, base - below, pen + glyph.cellWidth - ATLAS_PAD, base + face.baselineFromTop,
          -CANDIDATE_DIGIT_DP, FIT_UNLIMITED, 0f, 0f,
        )
      }
      pen += glyph.advance
    }
  }
  return sink.build()
}

/**
 * Сдвиг центра кружка номера от места попадания вправо, dp (вверх — всегда [CANDIDATE_SHIFT_DP]). Кружки стоят на
 * своих точках сцены и при вращении движутся вместе с ними; только кандидаты в одной точке (дубли точек) раскладываются
 * в ряд по номерам — раскладка по экранным пересечениям перекладывала бы кружки при каждом повороте.
 */
fun candidateMarkShifts(hits: FloatArray): FloatArray {
  val count = hits.size / 3
  return FloatArray(count) { i ->
    var slot = 0
    for (j in 0 until i) {
      if (abs(hits[j * 3] - hits[i * 3]) < CANDIDATE_SAME_SPOT && abs(hits[j * 3 + 1] - hits[i * 3 + 1]) < CANDIDATE_SAME_SPOT &&
        abs(hits[j * 3 + 2] - hits[i * 3 + 2]) < CANDIDATE_SAME_SPOT
      ) slot++
    }
    CANDIDATE_SHIFT_DP + slot * (CANDIDATE_MARK_DP + CANDIDATE_GAP_DP)
  }
}

/** Диаметр кружка номера кандидата, кегль номера, сдвиг от места попадания и зазор между кружками в ряду, dp. */
const val CANDIDATE_MARK_DP = 22f
const val CANDIDATE_DIGIT_DP = 12f
const val CANDIDATE_SHIFT_DP = 10f
private const val CANDIDATE_GAP_DP = 3f
/** Цифры номеров кандидатов: всегда в атласе, и у сцены без подписей. */
const val CANDIDATE_DIGITS = "0123456789"

// кандидаты ближе этого (единицы сцены) стоят в одной точке
private const val CANDIDATE_SAME_SPOT = 0.01f

/** Значки по `$PDMODE`: основа (0…4), +32 — круг, +64 — квадрат; «не задан» — крест «+». */
internal fun markerParts(mode: Int): List<Char> {
  if (mode == POINT_MODE_UNSET) return listOf(MARKER_PLUS)
  val parts = ArrayList<Char>(3)
  when (mode and 31) {
    0 -> parts.add(MARKER_DOT)
    2 -> parts.add(MARKER_PLUS)
    3 -> parts.add(MARKER_CROSS)
    4 -> parts.add(MARKER_TICK)
  }
  if (mode and 32 != 0) parts.add(MARKER_CIRCLE)
  if (mode and 64 != 0) parts.add(MARKER_SQUARE)
  return parts
}

// размер значка по умолчанию (`$PDSIZE` ≤ 0), dp номинальной стороны
internal const val MARKER_DEFAULT_DP = 16f

// номинальная сторона больше самой фигуры (отступ MARKER_INSET)
private const val MARKER_CELL_SCALE = 1.25f

/** Детерминированное число в [0, 1) от пары координат. */
private fun hash01(x: Float, z: Float): Float {
  var h = x.toRawBits() * -1640531535 + z.toRawBits() * 40503
  h = h xor (h ushr 15)
  h *= 0x2c1b3c6d
  h = h xor (h ushr 12)
  h *= 0x297a2d39
  h = h xor (h ushr 15)
  return (h ushr 8) / 16777216f
}
