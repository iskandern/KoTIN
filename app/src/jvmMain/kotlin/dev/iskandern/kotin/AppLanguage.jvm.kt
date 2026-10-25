package dev.iskandern.kotin

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import java.util.Locale
import java.util.prefs.Preferences

// язык системы — до первой подмены
private val systemLocale: Locale = Locale.getDefault()

@Composable
actual fun rememberPrefsStore(): PrefsStore = remember {
  val node = Preferences.userRoot().node("dev/iskandern/kotin")
  object : PrefsStore {
    override fun load(key: String): String? = node.get(key, null)

    override fun save(key: String, value: String?) {
      if (value == null) node.remove(key) else node.put(key, value)
      node.flush()
    }
  }
}

@Composable
actual fun ProvideLanguage(tag: String, content: @Composable () -> Unit) {
  applyLocale(tag)
  key(tag) { content() }
}

actual fun applyLocale(tag: String) = Locale.setDefault(Locale.forLanguageTag(tag))

actual fun systemLanguage(): String = systemLocale.language
