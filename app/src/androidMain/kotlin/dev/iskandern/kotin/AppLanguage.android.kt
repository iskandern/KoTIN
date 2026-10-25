package dev.iskandern.kotin

import android.content.Context
import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import java.util.Locale

@Composable
actual fun rememberPrefsStore(): PrefsStore {
  val context = LocalContext.current.applicationContext
  return remember(context) {
    val prefs = context.getSharedPreferences("kotin", Context.MODE_PRIVATE)
    object : PrefsStore {
      override fun load(key: String): String? = prefs.getString(key, null)

      override fun save(key: String, value: String?) {
        prefs.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply()
      }
    }
  }
}

@Composable
actual fun ProvideLanguage(tag: String, content: @Composable () -> Unit) {
  // смена конфигурации (поворот и т. п.) возвращает Locale по умолчанию к системному — подменяем заново
  LocalConfiguration.current
  applyLocale(tag)
  key(tag) { content() }
}

actual fun applyLocale(tag: String) = Locale.setDefault(Locale.forLanguageTag(tag))

actual fun systemLanguage(): String = Resources.getSystem().configuration.locales[0].language
