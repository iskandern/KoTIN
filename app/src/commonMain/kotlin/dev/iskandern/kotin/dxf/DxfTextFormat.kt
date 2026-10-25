package dev.iskandern.kotin.dxf

/**
 * Спецкоды DXF в тексте (TEXT и MTEXT): %%c - диаметр, %%d - градус, %%p - плюс-минус, %%% - процент.
 * %%u/%%o (подчёркивание/надчёркивание) просто убираются.
 */
internal fun replaceSpecialCodes(text: String): String {
  if (!text.contains("%%")) return text

  val result = StringBuilder(text.length)
  var i = 0
  while (i < text.length) {
    if (!text.startsWith("%%", i) || i + 2 >= text.length) {
      result.append(text[i])
      i++
      continue
    }
    when (text[i + 2].lowercaseChar()) {
      'c' -> result.append('⌀')
      'd' -> result.append('°')
      'p' -> result.append('±')
      '%' -> result.append('%')
      'u', 'o' -> Unit
      else -> result.append(text, i, i + 3)
    }
    i += 3
  }
  return result.toString()
}

/** Неразрывный пробел (\~) и разрыв колонки (\N) MTEXT после [stripMTextFormatting]. */
const val MTEXT_NBSP = ' '
const val MTEXT_COLUMN_BREAK = '\u000C'

/**
 * Убирает форматирование MTEXT, оставляя текст. \P - перенос строки, \~ - неразрывный пробел ([MTEXT_NBSP]),
 * \N - разрыв колонки ([MTEXT_COLUMN_BREAK]), \S верх^низ; - дробь, \f…; \H…; \C…; и т.п. - параметры шрифта,
 * {} - группы, \\ \{ \} - экранирование.
 */
internal fun stripMTextFormatting(text: String): String = MTextParser(text, 1.0, special = false).parse().text

/** Текст MTEXT без форматирования ([text], спецкоды %% заменены) и его фрагменты; [runs] null — весь текст одного вида. */
internal class MTextContent(val text: String, val runs: List<DxfTextRun>?)

/**
 * Текст MTEXT, как в [stripMTextFormatting], и фрагменты с цветом \C<aci>; и \c<rgb>;, высотой \H<x>; (абсолютная)
 * и \H<x>x; (от текущей), линиями \L \O \K (подчёркивание, надчёркивание, зачёркивание; строчные выключают).
 * Группа {} возвращает прежнее оформление. Шрифт \f, ширина \W, наклон \Q и прочие коды с параметром пропускаются.
 * [height] — высота текста сущности: от неё высота фрагментов.
 */
internal fun parseMText(text: String, height: Double): MTextContent = MTextParser(text, height, special = true).parse()

private class MTextParser(private val text: String, private val height: Double, private val special: Boolean) {
  private val out = StringBuilder(text.length)
  private val buffer = StringBuilder()
  private val runs = ArrayList<DxfTextRun>()
  private var color = TEXT_COLOR_ENTITY
  private var scale = 1.0
  private var decoration = 0
  // оформление до открытых групп: цвет, высота, линии
  private val stack = ArrayList<Triple<Int, Double, Int>>()

  fun parse(): MTextContent {
    var i = 0
    while (i < text.length) {
      val char = text[i]
      when {
        char == '{' -> {
          stack.add(Triple(color, scale, decoration))
          i++
        }

        char == '}' -> {
          stack.removeLastOrNull()?.let { (c, s, d) -> change { color = c; scale = s; decoration = d } }
          i++
        }

        char != '\\' || i + 1 >= text.length -> {
          buffer.append(char)
          i++
        }

        else -> i = escape(i + 1)
      }
    }
    flush()
    val plain = runs.isEmpty() || runs.size == 1 && runs[0].let { it.color == TEXT_COLOR_ENTITY && it.scale == 1.0 && it.decoration == 0 }
    return MTextContent(out.toString(), if (plain) null else runs)
  }

  // текст с прежним оформлением — во фрагмент; соседние фрагменты одного вида сливаются
  private fun flush() {
    if (buffer.isEmpty()) return
    out.append(if (special) replaceSpecialCodes(buffer.toString()) else buffer)
    buffer.clear()
    val last = runs.lastOrNull()
    val run = DxfTextRun(out.length, color, scale, decoration)
    if (last != null && last.color == color && last.scale == scale && last.decoration == decoration) runs[runs.size - 1] = run
    else runs.add(run)
  }

  private inline fun change(update: () -> Unit) {
    flush()
    update()
  }

  // разбирает код после обратной косой черты, возвращает индекс, с которого продолжать
  private fun escape(codeIndex: Int): Int {
    val code = text[codeIndex]
    val next = codeIndex + 1
    when (code) {
      'P', 'X' -> buffer.append('\n')
      '~' -> buffer.append(MTEXT_NBSP)
      'N' -> buffer.append(MTEXT_COLUMN_BREAK)
      '\\', '{', '}' -> buffer.append(code)

      'S' -> {
        val end = parameterEnd(next)
        buffer.append(text.substring(next, end).replace('^', '/').replace('#', '/'))
        return end + 1
      }

      // коды с параметром до точки с запятой
      'f', 'F', 'H', 'W', 'Q', 'T', 'A', 'C', 'c', 'p' -> {
        val end = parameterEnd(next)
        val param = text.substring(next, end).trim()
        when (code) {
          // 0 (BYBLOCK) и 256 (BYLAYER) — цвет самой сущности
          'C' -> param.toIntOrNull()?.let {
            change { color = if (it in 1..255) DxfColors.aciToRgb(it) else TEXT_COLOR_ENTITY }
          }

          // \c — COLORREF: младший байт красный, старший синий
          'c' -> param.toIntOrNull()?.let {
            change { color = ((it and 0xFF) shl 16) or (it and 0xFF00) or ((it shr 16) and 0xFF) }
          }

          'H' -> {
            val relative = param.endsWith('x') || param.endsWith('X')
            val value = (if (relative) param.dropLast(1) else param).toDoubleOrNull()
            if (value != null && value > 0 && (relative || height > 0)) {
              change { scale = if (relative) scale * value else value / height }
            }
          }
        }
        return if (end < text.length) end + 1 else end
      }

      // подчёркивание, надчёркивание, зачёркивание: заглавная включает, строчная выключает
      'L', 'l', 'O', 'o', 'K', 'k' -> {
        val bit = when (code.uppercaseChar()) {
          'L' -> TEXT_UNDERLINE
          'O' -> TEXT_OVERLINE
          else -> TEXT_STRIKE
        }
        change { decoration = if (code.isUpperCase()) decoration or bit else decoration and bit.inv() }
      }

      else -> buffer.append('\\').append(code)
    }
    return next
  }

  private fun parameterEnd(from: Int): Int = text.indexOf(';', from).takeIf { it >= 0 } ?: text.length
}

/** Строка подписи MTEXT: [scale] — её высота (наибольшая у фрагментов) от высоты текста; [runs] — см. [mTextLine]. */
internal class MTextLine(val scale: Double, val runs: List<DxfTextRun>?)

/**
 * Строка из символов [start, end) текста с фрагментами [runs]: фрагменты от начала строки, высота — от высоты строки;
 * null — строка цвета сущности, одной высоты и без линий.
 */
internal fun mTextLine(runs: List<DxfTextRun>?, start: Int, end: Int): MTextLine {
  if (runs == null) return MTextLine(1.0, null)
  var first = 0
  while (first < runs.size - 1 && runs[first].end <= start) first++
  var last = first
  while (last < runs.size - 1 && runs[last].end < end) last++
  var scale = 0.0
  for (r in first..last) scale = maxOf(scale, runs[r].scale)
  if (scale <= 0) scale = 1.0
  val only = runs[first]
  if (first == last && only.color == TEXT_COLOR_ENTITY && only.decoration == 0) return MTextLine(scale, null)
  val result = ArrayList<DxfTextRun>(last - first + 1)
  for (r in first..last) {
    val run = runs[r]
    result += DxfTextRun(maxOf(minOf(run.end, end) - start, 0), run.color, run.scale / scale, run.decoration)
  }
  return MTextLine(scale, result)
}
