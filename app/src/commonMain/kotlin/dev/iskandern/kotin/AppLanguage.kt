package dev.iskandern.kotin

import androidx.compose.runtime.Composable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.getPluralString
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.getSystemResourceEnvironment
import dev.iskandern.kotin.resources.Res
import dev.iskandern.kotin.resources.allPluralStringResources
import dev.iskandern.kotin.resources.allStringResources

/** Язык интерфейса. [nativeName] — название на самом языке, не переводится. */
enum class AppLanguage(val tag: String, val nativeName: String) {
  RU("ru", "Русский"),
  EN("en", "English");

  companion object {
    /** Выбранный в панели ([tag]), а не выбирали — язык системы, если он есть среди этих, иначе английский. */
    fun of(tag: String?): AppLanguage =
      entries.firstOrNull { it.tag == tag } ?: entries.firstOrNull { it.tag == systemLanguage() } ?: EN
  }
}

/** Язык системы — код ISO 639 строчными («ru»), без страны; подмена из [applyLocale] на него не влияет. */
expect fun systemLanguage(): String

/**
 * Выбор пользователя между запусками (язык, скрытая подсказка): Android — SharedPreferences, desktop — Preferences,
 * web — localStorage. [save] с null удаляет значение.
 */
interface PrefsStore {
  fun load(key: String): String?
  fun save(key: String, value: String?)
}

/** Ключ языка интерфейса в [PrefsStore]: тег языка, нет значения — по языку системы. */
const val PREF_LANGUAGE = "language"

@Composable
expect fun rememberPrefsStore(): PrefsStore

/**
 * Ставит язык строк ресурсов [tag] и показывает [content]. Compose Resources берут язык из
 * платформы (Locale по умолчанию, в web — navigator.language), поэтому его подменяем там; смена языка пересоздаёт [content].
 * Поэтому внутри — только интерфейс: сцена, движок и камера снаружи и смену языка не замечают.
 */
@Composable
expect fun ProvideLanguage(tag: String, content: @Composable () -> Unit)

/** Подменяет язык строк ресурсов на [tag]: то, что делает [ProvideLanguage], без пересоздания. */
expect fun applyLocale(tag: String)

/**
 * Загружает строки ресурсов языка [tag] в кэш Compose Resources до смены языка. На web строка читается асинхронно, и
 * без кэша первый кадр на новом языке рисовался с пустыми подписями: высота разделов менялась, меню дёргалось.
 * Из кэша строка берётся в той же композиции — первый кадр сразу с текстом.
 */
suspend fun preloadStrings(tag: String) {
  applyLocale(tag)
  val environment = getSystemResourceEnvironment()
  // не на главном потоке — см. [UiStrings.load]
  withContext(Dispatchers.Default) {
    Res.allStringResources.values.forEach { getString(environment, it) }
    Res.allPluralStringResources.values.forEach { getPluralString(environment, it, 1) }
  }
}
