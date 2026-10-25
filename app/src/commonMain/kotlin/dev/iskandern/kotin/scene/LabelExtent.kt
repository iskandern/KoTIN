package dev.iskandern.kotin.scene

import dev.iskandern.kotin.dxf.FACE_DEFAULT
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Кадр исходного вида с учётом длины строк: подпись с высотой в единицах чертежа тянется от точки вставки (влево при
 * выравнивании справа и у отражённого текста, далеко вправо у длинного), и кадр только по точкам её срезал бы.
 * [base] — кадр без учёта подписей (null — вся сцена, ±[halfX] × ±[halfY] × ±[halfZ] вокруг нуля); возвращает его же,
 * если все строки в нём помещаются, иначе кадр, расширенный до прямоугольников строк. Берутся подписи, чья точка
 * вставки внутри кадра: подпись далёкого объекта кадр не растягивает. Подписи с высотой в dp (высота < 0) в мировые
 * габариты не входят — их размер на экране постоянный и от окна не зависит. Ширина строки — оценка по среднему шагу
 * пера [AVERAGE_ADVANCE] (атлас ещё не собран): с запасом, но без растрирования шрифта.
 */
internal fun homeWithLabels(
  base: HomeFrame?, labels: List<RenderLabel>, outline: DrawingOutline?, halfX: Float, halfY: Float, halfZ: Float,
): HomeFrame? {
  val x = base?.x ?: 0f
  val y = base?.y ?: 0f
  val z = base?.z ?: 0f
  val hx = base?.halfX ?: halfX
  val hy = base?.halfY ?: halfY
  val hz = base?.halfZ ?: halfZ
  val x0 = x - hx; val x1 = x + hx
  val z0 = z - hz; val z1 = z + hz
  val reach = max(hx, hz)
  var minX = x0; var maxX = x1
  var minZ = z0; var maxZ = z1
  for (label in labels) {
    if (label.height <= 0f || label.text.isEmpty()) continue
    // кадр — по линиям, а подписи часто стоят рядом с ними, а не на них: близкая к кадру точка тоже считается
    val margin = reach * NEAR_SHARE + label.height * 2
    if (label.x < x0 - margin || label.x > x1 + margin || label.z < z0 - margin || label.z > z1 + margin) continue
    val face = FACE_CAP_HEIGHT.getOrElse(label.face) { FACE_CAP_HEIGHT[FACE_DEFAULT] }
    val width = estimatedWidth(label, face)
    val height = label.height
    val descent = height * DESCENT_SHARE
    // вдоль строки: [-w·k, w·(1−k)]; поперёк: от базовой линии вверх на высоту букв, вниз на подстрочные
    val lines = lineCount(label)
    val lowAlong = -width * horizontalShare(label.alignH)
    val highAlong = lowAlong + width
    val (lowUp, highUp) = when (label.alignV) {
      1 -> 0f to height + descent
      2 -> -(height / 2 + descent) to height / 2 + descent
      3 -> -height - descent to 0f
      0 -> -descent to height
      else -> -2 * height - descent to 0f
    }
    // следующие строки MTEXT с рамкой идут вниз
    val down = (lines - 1) * height * LINE_STEP
    val upLow = lowUp - down
    val ux = label.alongZ; val uz = -label.alongX
    for (corner in 0 until 4) {
      val a = if (corner and 1 == 0) lowAlong else highAlong
      val u = if (corner and 2 == 0) upLow else highUp
      val px = label.x + label.alongX * a + ux * u
      val pz = label.z + label.alongZ * a + uz * u
      if (!px.isFinite() || !pz.isFinite()) continue
      minX = min(minX, px); maxX = max(maxX, px)
      minZ = min(minZ, pz); maxZ = max(maxZ, pz)
    }
  }
  if (minX >= x0 && maxX <= x1 && minZ >= z0 && maxZ <= z1) return base
  return HomeFrame(
    (minX + maxX) / 2, y, (minZ + maxZ) / 2, (maxX - minX) / 2, hy, (maxZ - minZ) / 2,
    base?.outline ?: outline, base?.outliers.orEmpty(),
  )
}

/** Ширина самой длинной строки подписи в мировых единицах: «по ширине»/«вписанный» — своя длина, MTEXT с рамкой — не шире рамки. */
private fun estimatedWidth(label: RenderLabel, capHeight: Float): Float {
  if (label.fitLength > 0f) return label.fitLength
  var longest = 0
  var run = 0
  for (ch in label.text) {
    if (ch == '\n') run = 0 else if (++run > longest) longest = run
  }
  val width = longest * AVERAGE_ADVANCE * label.height / capHeight * label.widthFactor
  val frame = label.frame?.width ?: 0.0
  return if (frame > 0.0) min(width, frame.toFloat()) else width
}

/** Строк у MTEXT с рамкой: абзацы, перенесённые по ширине рамки; у остальных — по переводам строк. */
private fun lineCount(label: RenderLabel): Int {
  val frame = label.frame
  val wrap = if (frame != null && frame.width > 0.0) {
    frame.width.toFloat() / (AVERAGE_ADVANCE * label.height / FACE_CAP_HEIGHT[FACE_DEFAULT] * label.widthFactor)
  } else {
    Float.POSITIVE_INFINITY
  }
  // абзацы считаются проходом по тексту, без split: подписей может быть десятки тысяч
  var count = 1
  var run = 0
  fun closeParagraph() {
    if (run > wrap) count += ceil(run / wrap).toInt() - 1
  }
  for (ch in label.text) {
    if (ch == '\n') {
      closeParagraph()
      count++
      run = 0
    } else {
      run++
    }
  }
  closeParagraph()
  return min(count, MAX_FRAME_LINES)
}

private fun horizontalShare(alignH: Int) = when (alignH) {
  1, 4 -> 0.5f
  2 -> 1f
  else -> 0f
}

// средний шаг пера к кеглю: Golos Text ≈ 0,55, моноширинный JetBrains Mono — 0,6; кириллица шире латиницы
private const val AVERAGE_ADVANCE = 0.6f
// подстрочные и шаг между строками MTEXT — к высоте заглавных
private const val DESCENT_SHARE = 0.3f
private const val LINE_STEP = 1.7f
// точка вставки дальше от кадра, чем эта доля его размера плюс две высоты букв, кадр не растягивает
private const val NEAR_SHARE = 1f
private const val MAX_FRAME_LINES = 200
