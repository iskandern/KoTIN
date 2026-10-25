package dev.iskandern.kotin

import dev.iskandern.kotin.model.UiText
import java.io.File

// русские строки ресурсов: тесты сверяют имена так, как их покажет экран
private val STRINGS: Map<String, String> by lazy {
  val xml = File("src/commonMain/composeResources/values/strings.xml").readText()
  Regex("""<string name="(\w+)">(.*?)</string>""").findAll(xml).associate {
    it.groupValues[1] to it.groupValues[2].replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
  }
}

/** Текст [UiText] по-русски, как его покажет `localized`; ключ без строки в ресурсах — ошибка теста. */
fun ru(text: String): String {
  if (text.isEmpty() || text[0] != UiText.MARK) return text
  var end = 1
  while (end < text.length && (text[end].isLetterOrDigit() || text[end] == '_')) end++
  val key = text.substring(1, end)
  val template = STRINGS[key] ?: error("нет строки ресурса «$key» для «$text»")
  val rest = text.substring(end)
  if (rest.isEmpty() || rest[0] != UiText.SEPARATOR) return template + rest
  val args = rest.substring(1).split(UiText.SEPARATOR)
  return Regex("""%(\d+)\$[sd]""").replace(template) { args[it.groupValues[1].toInt() - 1] }
}
