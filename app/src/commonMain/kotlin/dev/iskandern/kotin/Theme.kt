package dev.iskandern.kotin

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily

/** Цвета интерфейса KoTIN: светлая тема, карточки поверх светлой сцены. */
object KotinColors {
  /** Фон сцены — тот же, что `BACKGROUND` в SceneView и `DxfColors.BACKGROUND`. */
  val Scene = Color(0xFFE3E7EB)
  val Card = Color(0xFFFFFFFF)
  /** Подложка внутри карточки: пустой слот, поля свойств. */
  val Subtle = Color(0xFFF3F5F7)
  val Outline = Color(0xFFD3D9DF)
  val Ink = Color(0xFF15202B)
  val Muted = Color(0xFF56636F)
  val Accent = Color(0xFF1F5EFF)
  val AccentSoft = Color(0xFFE8EEFF)
  val AccentInk = Color(0xFF1440B8)
  val Warning = Color(0xFF8A5A00)
  val Error = Color(0xFFC4312A)
  /** Выбранное: подложка карточки и строки слоя (подсветка в сцене — [Highlight]). */
  val Selection = Color(0x47FFD600)
  /** Жёлтый подсветки выбранного в сцене (HIGHLIGHT_RGB в SceneView). */
  val Highlight = Color(0xFFF5C400)
  /** Рамка пустого флажка. */
  val CheckOutline = Color(0xFF8A96A1)
  /** Ручка шторки на телефоне. */
  val Handle = Color(0xFFC7CED5)
  /** Затемнение [Ink]: сцена под боковым меню, нажатая кнопка карточки, тёмная плашка-подсказка. */
  val Scrim = Ink.copy(alpha = 0.32f)
  val Pressed = Ink.copy(alpha = 0.10f)
  val Hint = Ink.copy(alpha = 0.86f)
  /** Оси индикатора OXYZ: тёмные насыщенные, читаются на белом круге. */
  val AxisX = Color(0xFFD03A3A)
  val AxisY = Color(0xFF23894C)
  val AxisZ = Color(0xFF2F6FE0)
}

private val LightScheme = lightColorScheme(
  primary = KotinColors.Accent,
  onPrimary = Color.White,
  primaryContainer = KotinColors.AccentSoft,
  onPrimaryContainer = KotinColors.AccentInk,
  secondary = KotinColors.Accent,
  onSecondary = Color.White,
  secondaryContainer = KotinColors.AccentSoft,
  onSecondaryContainer = KotinColors.AccentInk,
  background = KotinColors.Scene,
  onBackground = KotinColors.Ink,
  surface = KotinColors.Card,
  onSurface = KotinColors.Ink,
  surfaceVariant = KotinColors.Subtle,
  onSurfaceVariant = KotinColors.Muted,
  outline = KotinColors.Outline,
  outlineVariant = KotinColors.Scene,
  error = KotinColors.Error,
)

/** Все стили M3 — гарнитурой [family]. */
private fun kotinTypography(family: FontFamily) = Typography().run {
  copy(
    displayLarge = displayLarge.copy(fontFamily = family),
    displayMedium = displayMedium.copy(fontFamily = family),
    displaySmall = displaySmall.copy(fontFamily = family),
    headlineLarge = headlineLarge.copy(fontFamily = family),
    headlineMedium = headlineMedium.copy(fontFamily = family),
    headlineSmall = headlineSmall.copy(fontFamily = family),
    titleLarge = titleLarge.copy(fontFamily = family),
    titleMedium = titleMedium.copy(fontFamily = family),
    titleSmall = titleSmall.copy(fontFamily = family),
    bodyLarge = bodyLarge.copy(fontFamily = family),
    bodyMedium = bodyMedium.copy(fontFamily = family),
    bodySmall = bodySmall.copy(fontFamily = family),
    labelLarge = labelLarge.copy(fontFamily = family),
    labelMedium = labelMedium.copy(fontFamily = family),
    labelSmall = labelSmall.copy(fontFamily = family),
  )
}

private val LocalMonoFamily = staticCompositionLocalOf<FontFamily> { FontFamily.Monospace }

/** JetBrains Mono — числа, координаты, пикеты: `Text(…, fontFamily = MonoFamily)`. Доступен внутри [KotinTheme]. */
val MonoFamily: FontFamily
  @Composable @ReadOnlyComposable get() = LocalMonoFamily.current

/** Тема KoTIN: цвета [KotinColors], текст — Golos Text, числа — [MonoFamily]. */
@Composable
fun KotinTheme(content: @Composable () -> Unit) {
  val ui = rememberFontFamily(GOLOS_FONTS)
  val mono = rememberFontFamily(MONO_FONTS)
  // web: шрифты приходят по сети (≈ 0,1 с); до них интерфейса нет, иначе текст мигнёт шрифтом платформы
  if (ui == null || mono == null) return
  val typography = remember(ui) { kotinTypography(ui) }
  MaterialTheme(colorScheme = LightScheme, typography = typography) {
    CompositionLocalProvider(LocalMonoFamily provides mono, content = content)
  }
}
