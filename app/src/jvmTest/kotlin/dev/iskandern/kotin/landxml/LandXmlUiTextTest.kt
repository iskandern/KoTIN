package dev.iskandern.kotin.landxml

import org.junit.Test
import dev.iskandern.kotin.io.ByteArrayInput
import dev.iskandern.kotin.model.UiText
import dev.iskandern.kotin.ru
import java.io.File
import kotlin.test.assertTrue

/** Слои, группы и свойства LandXML пишутся ключами [UiText]: у каждого ключа фикстур есть строка в ресурсах. */
class LandXmlUiTextTest {

  @Test
  fun `у ключей слоёв, групп и свойств фикстур есть строки ресурсов`() {
    val files = File("src/jvmTest/resources/landxml").walk().filter { it.extension == "xml" }.toList()
    assertTrue(files.isNotEmpty())
    var keys = 0
    for (file in files) {
      val geometry = runCatching {
        LandXmlReader(ByteArrayInput(file.readBytes()), LandXmlReader.SECTIONS.map { it() }, log = {}).read()
      }.getOrNull() ?: continue
      val texts = geometry.layers.flatMap { listOf(it.name, it.group) } +
        geometry.objects.flatMap { o -> listOf(o.name) + o.attributes.flatMap { listOf(it.name, it.value) } }
      for (text in texts) if (text.startsWith(UiText.MARK)) {
        ru(text) // нет строки — ошибка
        keys++
      }
    }
    assertTrue(keys > 0, "в фикстурах нет ни одного ключа")
  }
}
