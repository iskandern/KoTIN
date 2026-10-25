package org.jetbrains.compose.resources

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import dev.iskandern.kotin.resources.EN_PLURALS
import dev.iskandern.kotin.resources.EN_STRINGS
import dev.iskandern.kotin.resources.RU_PLURALS
import dev.iskandern.kotin.resources.RU_STRINGS

// То немногое из Compose Resources, что нужно общему коду app (строки и чтение файлов), — без Compose UI. Строки
// зашиты в код (см. :webhtml:generateStrings): ru — язык по умолчанию, en — values-en.

class StringResource(val key: String, internal val index: Int)

class PluralStringResource(val key: String, internal val index: Int)

/** Язык строк: «en» — английские, остальное — русские (values без квалификатора), как выбирает Compose Resources. */
data class ResourceEnvironment(val language: String)

/** Язык браузера с подменой из `applyLocale` (navigator.language). */
expect fun resourceLanguage(): String

/** Файл из composeResources app по пути `files/…`, `font/…`. */
expect suspend fun readResourceBytes(path: String): ByteArray

fun getSystemResourceEnvironment(): ResourceEnvironment = ResourceEnvironment(resourceLanguage())

// язык, на котором строят интерфейс: снимок, поэтому смена языка перекомпоновывает тексты без пересоздания интерфейса
private val composeLanguage = mutableStateOf(ResourceEnvironment(resourceLanguage()))

/** Язык поменяли (`applyLocale`): тексты интерфейса перечитываются. */
fun refreshResourceLanguage() {
  composeLanguage.value = getSystemResourceEnvironment()
}

private fun english(environment: ResourceEnvironment) = environment.language.startsWith("en")

fun stringOf(environment: ResourceEnvironment, resource: StringResource): String =
  (if (english(environment)) EN_STRINGS else RU_STRINGS)[resource.index]

private val PLACEHOLDER = Regex("""%(\d+)\$[ds]""")

internal fun format(template: String, args: Array<out Any>): String =
  if (args.isEmpty()) template else PLACEHOLDER.replace(template) { args.getOrNull(it.groupValues[1].toInt() - 1)?.toString() ?: "" }

suspend fun getString(environment: ResourceEnvironment, resource: StringResource): String = stringOf(environment, resource)

suspend fun getString(resource: StringResource, vararg formatArgs: Any): String =
  format(stringOf(getSystemResourceEnvironment(), resource), formatArgs)

fun pluralOf(environment: ResourceEnvironment, resource: PluralStringResource, quantity: Int, args: Array<out Any>): String {
  val forms = (if (english(environment)) EN_PLURALS else RU_PLURALS)[resource.index]
  val category = if (english(environment)) englishPlural(quantity) else russianPlural(quantity)
  // формы: zero, one, two, few, many, other
  val template = forms[category] ?: forms[OTHER] ?: ""
  return format(template, args)
}

suspend fun getPluralString(environment: ResourceEnvironment, resource: PluralStringResource, quantity: Int, vararg formatArgs: Any): String =
  pluralOf(environment, resource, quantity, formatArgs)

suspend fun getPluralString(resource: PluralStringResource, quantity: Int, vararg formatArgs: Any): String =
  pluralOf(getSystemResourceEnvironment(), resource, quantity, formatArgs)

/** Строка на текущем языке ([refreshResourceLanguage]). */
@Composable
fun stringResource(resource: StringResource): String = stringOf(composeLanguage.value, resource)

@Composable
fun stringResource(resource: StringResource, vararg formatArgs: Any): String =
  format(stringOf(composeLanguage.value, resource), formatArgs)

@Composable
fun pluralStringResource(resource: PluralStringResource, quantity: Int, vararg formatArgs: Any): String =
  pluralOf(composeLanguage.value, resource, quantity, formatArgs)

private const val ONE = 1
private const val FEW = 3
private const val MANY = 4
private const val OTHER = 5

private fun englishPlural(n: Int) = if (n == 1) ONE else OTHER

// правила CLDR для целых: 1, 21, 31… — one; 2–4, 22–24… — few; остальное — many
private fun russianPlural(n: Int): Int {
  val mod10 = n % 10
  val mod100 = n % 100
  return when {
    mod10 == 1 && mod100 != 11 -> ONE
    mod10 in 2..4 && mod100 !in 12..14 -> FEW
    else -> MANY
  }
}
