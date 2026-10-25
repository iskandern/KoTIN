package dev.iskandern.kotin

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import org.jetbrains.compose.resources.FontResource
import dev.iskandern.kotin.resources.Res
import dev.iskandern.kotin.resources.golos_text_medium
import dev.iskandern.kotin.resources.golos_text_regular
import dev.iskandern.kotin.resources.golos_text_semibold
import dev.iskandern.kotin.resources.jetbrains_mono_bold
import dev.iskandern.kotin.resources.jetbrains_mono_medium
import dev.iskandern.kotin.resources.jetbrains_mono_regular
import dev.iskandern.kotin.resources.jetbrains_mono_semibold_italic

/** Файл шрифта из `composeResources/font` с весом и начертанием. */
class FontSpec(val resource: FontResource, val weight: FontWeight = FontWeight.Normal, val style: FontStyle = FontStyle.Normal)

/** Шрифт из ресурсов; на web — null, пока не загружен (приходит по сети). */
@Composable
expect fun rememberFont(spec: FontSpec): Font?

/** Семейство из всех [specs]; на web — null, пока не загружены все: текст не мигает шрифтом платформы. */
@Composable
fun rememberFontFamily(specs: List<FontSpec>): FontFamily? {
  val fonts = specs.map { rememberFont(it) }
  return remember(fonts) { if (fonts.all { it != null }) FontFamily(fonts.filterNotNull()) else null }
}

/** Golos Text — шрифт интерфейса и подписей сцены без своей гарнитуры. */
internal val GOLOS_TEXT = FontSpec(Res.font.golos_text_regular)
internal val GOLOS_FONTS = listOf(
  GOLOS_TEXT,
  FontSpec(Res.font.golos_text_medium, FontWeight.Medium),
  FontSpec(Res.font.golos_text_semibold, FontWeight.SemiBold),
)

/** JetBrains Mono — числа, координаты, пикеты; курсив 600 — имя трассы. */
internal val MONO_REGULAR = FontSpec(Res.font.jetbrains_mono_regular)
internal val MONO_BOLD = FontSpec(Res.font.jetbrains_mono_bold, FontWeight.Bold)
internal val MONO_ITALIC = FontSpec(Res.font.jetbrains_mono_semibold_italic, FontWeight.SemiBold, FontStyle.Italic)
internal val MONO_FONTS = listOf(MONO_REGULAR, FontSpec(Res.font.jetbrains_mono_medium, FontWeight.Medium), MONO_BOLD, MONO_ITALIC)
