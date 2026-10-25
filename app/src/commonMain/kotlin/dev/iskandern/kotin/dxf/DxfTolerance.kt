package dev.iskandern.kotin.dxf

/**
 * Строка TOLERANCE (код 1) → строки рамки, в каждой — тексты ячеек. `^J` начинает новую строку рамки, `%%v`
 * разделяет ячейки, `{\Fgdt;…}` — шрифт символов GDT: буквы заменяются похожими знаками ([gdtSymbol]).
 * Пустые ячейки пропускаются, остальное форматирование MTEXT снимается.
 */
internal fun parseTolerance(raw: String): List<List<String>> =
  replaceGdtFont(raw).split("^J", "^j").map { line ->
    line.split("%%v", "%%V")
      .map { replaceSpecialCodes(stripMTextFormatting(it)).replace(MTEXT_NBSP, ' ').replace('\n', ' ').trim() }
      .filter { it.isNotEmpty() }
  }.filter { it.isNotEmpty() }

/**
 * Символ GDT по строчной букве шрифта gdt.shx; остальные знаки — как есть. Точных символов (∠ ⊥ ⏥ ∥ ⌭ ⌰ Ⓜ …) нет
 * в шрифте web-версии — вместо них близкие знаки, которые есть везде, и буквы.
 */
internal fun gdtSymbol(letter: Char): String = when (letter) {
  'a' -> "<" // наклон ∠
  'b' -> "_|_" // перпендикулярность ⊥
  'c' -> "▱" // плоскостность ⏥
  'd' -> "◚" // форма поверхности ⌓
  'e' -> "○" // круглость
  'f' -> "//" // параллельность ∥
  'g' -> "/○/" // цилиндричность ⌭
  'h' -> "➚" // радиальное биение ↗
  'i' -> "☰" // симметричность ⌯
  'j' -> "⌖" // позиция
  'k' -> "◠" // форма линии ⌒
  'l' -> "(L)" // минимум материала Ⓛ
  'm' -> "(M)" // максимум материала Ⓜ
  'n' -> "Ø" // диаметр ⌀
  'p' -> "(P)" // выступающее поле Ⓟ
  'r' -> "◎" // соосность
  's' -> "(S)" // независимо от размера Ⓢ
  't' -> "➚➚" // полное биение ⌰
  'u' -> "—" // прямолинейность ⏤
  else -> letter.toString()
}

// группы {…} со шрифтом gdt: буквы → символы, сам код шрифта убирается; остальное — без изменений
private fun replaceGdtFont(text: String): String {
  if (!text.contains("gdt", ignoreCase = true)) return text
  val result = StringBuilder(text.length)
  // шрифт gdt на каждом уровне вложенности скобок
  val stack = ArrayList<Boolean>()
  var gdt = false
  var i = 0
  while (i < text.length) {
    val c = text[i]
    when {
      c == '{' -> {
        stack.add(gdt)
        result.append(c)
        i++
      }
      c == '}' -> {
        if (stack.isNotEmpty()) gdt = stack.removeAt(stack.size - 1)
        result.append(c)
        i++
      }
      c == '\\' && i + 1 < text.length && (text[i + 1] == 'F' || text[i + 1] == 'f') -> {
        val end = text.indexOf(';', i).let { if (it < 0) text.length else it }
        gdt = text.substring(i + 2, end).trim().startsWith("gdt", ignoreCase = true)
        i = end + 1
      }
      c == '\\' && i + 1 < text.length -> {
        result.append(c).append(text[i + 1])
        i += 2
      }
      // %%v — разделитель ячеек и в шрифте gdt
      c == '%' && text.startsWith("%%", i) && i + 2 < text.length -> {
        result.append(text, i, i + 3)
        i += 3
      }
      c == '^' && i + 1 < text.length -> {
        result.append(c).append(text[i + 1])
        i += 2
      }
      gdt -> {
        result.append(gdtSymbol(c))
        i++
      }
      else -> {
        result.append(c)
        i++
      }
    }
  }
  return result.toString()
}
