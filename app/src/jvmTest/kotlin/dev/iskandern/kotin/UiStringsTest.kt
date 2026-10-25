package dev.iskandern.kotin

import kotlinx.coroutines.runBlocking
import org.junit.Test
import dev.iskandern.kotin.model.UiText
import java.io.File
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Ключи [UiText] в исходниках и их перевод вне Compose ([UiStrings]). */
class UiStringsTest {

  @Test
  fun `у каждого ключа в исходниках есть строка ресурсов`() {
    val names = Regex("""<string name="(\w+)">""").findAll(File("src/commonMain/composeResources/values/strings.xml").readText())
      .map { it.groupValues[1] }.toSet()
    // «@ключ» и UiText.key("ключ", …); «@префикс_» (слои труб) дополняется в коде
    val usage = Regex(""""@(\w+)"|UiText\.key\("(\w+)"""")
    val missing = sortedSetOf<String>()
    var keys = 0
    for (file in File("src").walk().filter { it.extension == "kt" && "Test" !in it.path }) {
      for (m in usage.findAll(file.readText())) {
        val key = m.groupValues[1].ifEmpty { m.groupValues[2] }
        if (key.endsWith("_")) continue
        keys++
        if (key !in names) missing.add("$key (${file.name})")
      }
    }
    assertTrue(keys > 100, "ключей в исходниках: $keys")
    assertTrue(missing.isEmpty(), "нет строк ресурсов: $missing")
  }

  @Test
  fun `английский перевод — те же строки и те же подстановки, без кириллицы`() {
    fun read(dir: String) = Regex("""<string name="(\w+)">(.*?)</string>""")
      .findAll(File("src/commonMain/composeResources/$dir/strings.xml").readText())
      .associate { it.groupValues[1] to it.groupValues[2] }
    val ru = read("values")
    val en = read("values-en")
    assertEquals(ru.keys.sorted(), en.keys.sorted(), "имена строк")
    val placeholders = Regex("""%\d+\$[sd]""")
    for ((name, text) in ru) {
      assertEquals(placeholders.findAll(text).map { it.value }.toSet(), placeholders.findAll(en.getValue(name)).map { it.value }.toSet(), name)
      assertTrue(en.getValue(name).none { it in 'а'..'я' || it in 'А'..'Я' || it == 'ё' || it == 'Ё' }, "кириллица в «$name»")
    }
  }

  @Test
  fun `журнал переводится строками ресурсов`() {
    // язык строк — системный; проверка — на русском, как в values/
    val locale = Locale.getDefault()
    Locale.setDefault(Locale.forLanguageTag("ru"))
    try {
      runBlocking { UiStrings.load() }
    } finally {
      Locale.setDefault(locale)
    }
    assertEquals("LandXML: поперечников 3", UiStrings.format(UiText.key("log_cross", 3)))
    assertEquals("файл оборван (байт 42)", UiStrings.format(UiText.key("xml_eof", 42)))
    assertEquals("Поверхность (2)", UiStrings.format("@layer_surface (2)"))
    assertEquals("просто текст", UiStrings.format("просто текст"))
  }
}
