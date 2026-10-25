package dev.iskandern.kotin

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.ResourceEnvironment
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.getSystemResourceEnvironment
import dev.iskandern.kotin.model.Journal
import dev.iskandern.kotin.model.UiText
import dev.iskandern.kotin.resources.Res
import dev.iskandern.kotin.resources.allStringResources
import kotlin.concurrent.Volatile

/**
 * Перевод [UiText] вне Compose — для журнала: строки ресурсов на текущем языке загружаются в [load] (заново — после
 * смены языка), дальше [format] работает синхронно из любого потока. До загрузки ключи печатаются как есть.
 */
object UiStrings {
  @Volatile
  private var table: Map<String, String> = emptyMap()

  // язык (и прочее окружение ресурсов), для которого загружена [table]
  @Volatile
  private var loadedFor: ResourceEnvironment? = null

  private val PLACEHOLDER = Regex("""%(\d+)\$[sd]""")

  /**
   * Загружает строки (если язык сменился) и ставит журналу вывод с переводом. Не на главном потоке: загрузку строки
   * кэш Compose Resources запускает в корутине первого запросившего, а `stringResource` на Android ждёт её через
   * runBlocking на главном — корутина с главного потока не дождалась бы его, экран оставался серым (#203).
   */
  suspend fun load() = withContext(Dispatchers.Default) {
    val environment = getSystemResourceEnvironment()
    if (environment != loadedFor) {
      table = Res.allStringResources.mapValues { getString(environment, it.value) }
      loadedFor = environment
    }
    Journal.sink = { println(format(it)) }
  }

  /** Строка ресурсов [key] на текущем языке; до [load] — null. */
  fun string(key: String): String? = table[key]

  fun format(text: String): String {
    if (text.isEmpty() || text[0] != UiText.MARK) return text
    var end = 1
    while (end < text.length && (text[end].isLetterOrDigit() || text[end] == '_')) end++
    val template = table[text.substring(1, end)] ?: return text
    val rest = text.substring(end)
    if (rest.isEmpty() || rest[0] != UiText.SEPARATOR) return template + rest
    val args = rest.substring(1).split(UiText.SEPARATOR)
    return PLACEHOLDER.replace(template) { args.getOrElse(it.groupValues[1].toInt() - 1) { "" } }
  }
}
