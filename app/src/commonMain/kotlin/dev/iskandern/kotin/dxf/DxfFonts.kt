package dev.iskandern.kotin.dxf

/**
 * Гарнитуры подписей сцены (`RenderLabel.face`), файлы — в `composeResources/font`, список — `LABEL_FONTS` в SceneView.
 * [FACE_DEFAULT] — Golos Text, шрифт интерфейса; замены шрифтов DXF — Arimo…PT Sans Narrow; JetBrains Mono для чисел:
 * [FACE_MONO] — подписи точек, [FACE_MONO_BOLD] — пикеты, [FACE_MONO_ITALIC] (600 курсив) — имя трассы.
 * Символа нет в гарнитуре — его рисует Golos, нет и в Golos — шрифт платформы.
 */
const val FACE_DEFAULT = 0
const val FACE_ARIMO = 1
const val FACE_TINOS = 2
const val FACE_CARLITO = 3
const val FACE_NARROW = 4
const val FACE_MONO = 5
const val FACE_MONO_BOLD = 6
const val FACE_MONO_ITALIC = 7
// число гарнитур; нигде не используется: набор гарнитур задан списком LABEL_FONTS, размер по счётчику не нужен
const val FACE_COUNT = 8

/**
 * Замена шрифта стиля текста: гарнитура [face] и поправка ширины букв [widthScale] — множитель к ширине из файла
 * (код 41), чтобы строка вышла той же длины, что в AutoCAD.
 */
class DxfFont(val face: Int, val widthScale: Double) {
  companion object {
    /** Неизвестный шрифт: шрифт интерфейса, ширина как есть. */
    val DEFAULT = DxfFont(FACE_DEFAULT, 1.0)
  }
}

/**
 * Замена для шрифта стиля по имени гарнитуры TTF (XDATA `ACAD`, 1000) или файла (код 3). Arial, Times New Roman и
 * Calibri — гарнитуры с теми же метриками (Arimo, Tinos, Carlito), Arial Narrow — Arimo, сжатый в 0,82. SHX, `isocpeur`
 * и ГОСТ-шрифты — PT Sans Narrow, растянутый до средней ширины знака своего шрифта ([NARROW_FONT_WIDTHS]).
 */
fun dxfFont(file: String, family: String): DxfFont {
  if (family.isNotBlank()) fontByName(family)?.let { return it }
  fontByName(file)?.let { return it }
  // неизвестный SHX — всё равно узкий штриховой шрифт, а не широкая гарнитура платформы
  if (file.trim().endsWith(".shx", ignoreCase = true)) return DxfFont(FACE_NARROW, UNKNOWN_SHX_WIDTH / NARROW_FACE_WIDTH)
  return DxfFont.DEFAULT
}

private fun fontByName(name: String): DxfFont? {
  // путь и расширение не важны, пробелы, дефисы и подчёркивания тоже: «Arial Narrow», «arialn.ttf», «GOST 2304A»
  val key = name.substringAfterLast('/').substringAfterLast('\\').lowercase()
    .removeSuffix(".ttf").removeSuffix(".shx").removeSuffix(".otf").filter { it.isLetterOrDigit() }
  if (key.isEmpty()) return null
  BY_NAME[key]?.let { return it }
  NARROW_FONT_WIDTHS[key]?.let { return DxfFont(FACE_NARROW, it / NARROW_FACE_WIDTH) }
  return null
}

private val ARIAL = DxfFont(FACE_ARIMO, 1.0)
private val ARIAL_NARROW = DxfFont(FACE_ARIMO, ARIAL_NARROW_WIDTH)
private val TIMES = DxfFont(FACE_TINOS, 1.0)
private val CALIBRI = DxfFont(FACE_CARLITO, 1.0)

/** Ширина Arial Narrow относительно Arial. */
const val ARIAL_NARROW_WIDTH = 0.82

// файлы обычного, жирного и курсива одной гарнитуры — одна замена (жирный и курсив не рисуем)
private val BY_NAME = mapOf(
  "arial" to ARIAL, "arialbd" to ARIAL, "ariali" to ARIAL, "arialbi" to ARIAL,
  "arialn" to ARIAL_NARROW, "arialnarrow" to ARIAL_NARROW, "arialnb" to ARIAL_NARROW, "arialni" to ARIAL_NARROW,
  "arialnbi" to ARIAL_NARROW,
  "times" to TIMES, "timesnewroman" to TIMES, "timesbd" to TIMES, "timesi" to TIMES, "timesbi" to TIMES,
  "calibri" to CALIBRI, "calibrib" to CALIBRI, "calibrii" to CALIBRI, "calibriz" to CALIBRI, "calibril" to CALIBRI,
)

/** Средняя ширина знака PT Sans Narrow относительно высоты заглавных (цифры, латиница, кириллица). */
internal const val NARROW_FACE_WIDTH = 0.65

/** Ширина знака неизвестного SHX: между ГОСТ тип Б и Hershey. */
private const val UNKNOWN_SHX_WIDTH = 0.9

/**
 * Средняя ширина знака (с межбуквенным просветом) относительно высоты заглавных у шрифтов, которые рисуются
 * PT Sans Narrow. Оценка по сетке знаков: SHX Hershey (`simplex`, `romans`…) — около 20 единиц на 21 высоты,
 * `txt` — 6 на 6 с просветом, ГОСТ 2.304 (буква 6d и просвет 2d) тип А — h = 14d, тип Б — h = 10d.
 */
private val NARROW_FONT_WIDTHS = mapOf(
  "txt" to 0.92,
  "simplex" to 0.95, "romans" to 0.95, "romand" to 0.98, "romanc" to 0.95,
  "isocpeur" to 0.72, "isocp" to 0.72, "isocteur" to 0.72,
  "gost2304a" to 0.6, "gosttypea" to 0.6, "gost2304b" to 0.8, "gosttypeb" to 0.8,
  "gost" to 0.8, "csgost2304" to 0.8, "gost2304" to 0.8, "gostcommon" to 0.8, "gostb" to 0.8, "gosta" to 0.6,
  "eskd" to 0.8, "eskd1" to 0.8, "russiagosttopo" to 0.8,
)
