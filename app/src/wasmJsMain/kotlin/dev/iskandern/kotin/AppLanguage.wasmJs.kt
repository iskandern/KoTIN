package dev.iskandern.kotin

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember

// ключи localStorage общие у всех страниц адреса — со своим префиксом
private const val KEY_PREFIX = "kotin."

// localStorage может быть недоступен (приватный режим, запрет cookie) — тогда выбор живёт до перезагрузки
@JsFun("(key) => { try { return localStorage.getItem(key); } catch (e) { return null; } }")
private external fun storageGet(key: String): String?

@JsFun("(key, value) => { try { if (value == null) localStorage.removeItem(key); else localStorage.setItem(key, value); } catch (e) {} }")
private external fun storageSet(key: String, value: String?)

// Compose Resources берут язык из navigator.language(s): подменяем их, пока задан window.__kotinLocale
@JsFun(
  """() => {
    if (window.__kotinLocaleInstalled) return;
    window.__kotinLocaleInstalled = true;
    for (const name of ['language', 'languages']) {
      const original = Object.getOwnPropertyDescriptor(Navigator.prototype, name);
      Object.defineProperty(Navigator.prototype, name, Object.assign({}, original, {
        get: function () {
          const custom = window.__kotinLocale;
          if (!custom) return original.get.apply(this);
          return name === 'language' ? custom : [custom];
        },
      }));
    }
  }""",
)
private external fun installLocaleOverride()

@JsFun("(tag) => { window.__kotinLocale = tag; }")
private external fun setLocaleOverride(tag: String)

// язык браузера мимо подмены
@JsFun("() => { const custom = window.__kotinLocale; window.__kotinLocale = null; const l = navigator.language || ''; window.__kotinLocale = custom; return l; }")
private external fun browserLanguage(): String

@Composable
actual fun rememberPrefsStore(): PrefsStore = remember {
  object : PrefsStore {
    override fun load(key: String): String? = storageGet(KEY_PREFIX + key)

    override fun save(key: String, value: String?) = storageSet(KEY_PREFIX + key, value)
  }
}

@Composable
actual fun ProvideLanguage(tag: String, content: @Composable () -> Unit) {
  applyLocale(tag)
  key(tag) { content() }
}

actual fun applyLocale(tag: String) {
  installLocaleOverride()
  setLocaleOverride(tag)
}

actual fun systemLanguage(): String = browserLanguage().substringBefore('-').lowercase()
