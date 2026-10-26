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

/** Цвета интерфейса KoTIN: светлая тема, карточки поверх светлой сцены; значения — [DesignTokens]. */
object KotinColors {
  /** Фон сцены — тот же, что `BACKGROUND` в SceneView и `DxfColors.BACKGROUND`. */
  val Scene = Color(DesignTokens.SCENE)
  val Card = Color(DesignTokens.CARD)
  /** Подложка внутри карточки: пустой слот, поля свойств. */
  val Subtle = Color(DesignTokens.SUBTLE)
  val Outline = Color(DesignTokens.OUTLINE)
  val Ink = Color(DesignTokens.INK)
  val Muted = Color(DesignTokens.MUTED)
  val Accent = Color(DesignTokens.ACCENT)
  val AccentSoft = Color(DesignTokens.ACCENT_SOFT)
  val AccentInk = Color(DesignTokens.ACCENT_INK)
  val Warning = Color(DesignTokens.WARNING)
  val Error = Color(DesignTokens.ERROR)
  /** Выбранное: подложка карточки и строки слоя (подсветка в сцене — [Highlight]). */
  val Selection = Color(DesignTokens.SELECTION)
  /** Жёлтый подсветки выбранного в сцене (HIGHLIGHT_RGB в SceneView). */
  val Highlight = Color(DesignTokens.HIGHLIGHT)
  /** Рамка пустого флажка. */
  val CheckOutline = Color(DesignTokens.CHECK_OUTLINE)
  /** Ручка шторки на телефоне. */
  val Handle = Color(DesignTokens.HANDLE)
  /** Затемнение [Ink]: сцена под боковым меню, нажатая кнопка карточки, тёмная плашка-подсказка. */
  val Scrim = Ink.copy(alpha = DesignTokens.SCRIM_ALPHA)
  val Pressed = Ink.copy(alpha = DesignTokens.PRESSED_ALPHA)
  val Hint = Ink.copy(alpha = DesignTokens.HINT_ALPHA)
  /** Оси индикатора OXYZ: тёмные насыщенные, читаются на белом круге. */
  val AxisX = Color(DesignTokens.AXIS_X)
  val AxisY = Color(DesignTokens.AXIS_Y)
  val AxisZ = Color(DesignTokens.AXIS_Z)
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
