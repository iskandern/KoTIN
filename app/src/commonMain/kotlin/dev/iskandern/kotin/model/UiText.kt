package dev.iskandern.kotin.model

/**
 * Переводимый текст, упакованный в строку: «@ключ» или «@ключ␟арг␟арг» — имя строкового ресурса и аргументы
 * его шаблона. Так читатели пишут имена слоёв, групп и свойств: строка остаётся идентификатором (скрытые слои,
 * подсветка), а переводит её экран при показе (`localized`). Строка без «@» и неизвестный ключ показываются как есть;
 * текст после ключа без разделителя (суффикс « (2)» у повторного имени) дописывается к переводу.
 */
object UiText {
  const val MARK = '@'
  const val SEPARATOR = '\u001F'

  fun key(name: String, vararg args: Any): String = buildString {
    append(MARK).append(name)
    for (arg in args) append(SEPARATOR).append(arg)
  }
}
