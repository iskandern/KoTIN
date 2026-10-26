package dev.iskandern.kotin.web

import dev.iskandern.kotin.DesignTokens
import kotlinx.browser.document
import org.w3c.dom.HTMLElement
import kotlin.math.roundToInt

/** Цвет CSS из ARGB [DesignTokens]; [alpha] — непрозрачность поверх своей. */
fun cssArgb(argb: Long, alpha: Float = 1f): String {
  val a = ((argb ushr 24) and 0xFF) / 255f * alpha
  val r = (argb ushr 16) and 0xFF
  val g = (argb ushr 8) and 0xFF
  val b = argb and 0xFF
  if (a >= 1f) return "#" + (argb and 0xFFFFFF).toString(16).padStart(6, '0').uppercase()
  return "rgba($r, $g, $b, ${round4(a)})"
}

private fun round4(v: Float): String = ((v * 10_000).roundToInt() / 10_000.0).toString().removeSuffix(".0")

private fun bezier(p: FloatArray) = "cubic-bezier(${p.joinToString()})"

/** Мягкая тень (softShadow app) высотой [elevation]: [DesignTokens.SHADOW_STEPS] колец, сдвиг вниз. */
private fun softShadow(elevation: Int): String {
  val steps = DesignTokens.SHADOW_STEPS
  val color = cssArgb(DesignTokens.INK, DesignTokens.SHADOW_ALPHA / steps)
  val dy = round4(elevation * DesignTokens.SHADOW_OFFSET)
  return (1..steps).joinToString { "0 ${dy}px 0 ${round4(elevation.toFloat() * it / steps)}px $color" }
}

/** Тень бокового меню: та же, но копиями со сдвигом вправо — скруглены как само меню. */
private fun drawerShadow(elevation: Int): String {
  val steps = DesignTokens.SHADOW_STEPS
  val color = cssArgb(DesignTokens.INK, DesignTokens.SHADOW_ALPHA / steps)
  return (1..steps).joinToString { "${round4(elevation.toFloat() * it / steps)}px 0 0 $color" }
}

/**
 * Тень платформы (Modifier.shadow) высотой [elevation] — приближённо: ключевая и рассеянная чёрные тени. 3 dp —
 * плавающие карточки, 8 dp — пустой экран.
 */
private fun platformShadow(elevation: Int): String {
  val black = DesignTokens.BLACK
  return if (elevation <= DesignTokens.FLOAT_ELEVATION) {
    "0 1px 3px ${cssArgb(black, 0.14f)}, 0 1px 1.5px ${cssArgb(black, 0.10f)}"
  } else {
    "0 3px ${elevation}px ${cssArgb(black, 0.14f)}, 0 1px 3px ${cssArgb(black, 0.10f)}"
  }
}

/** Переменные CSS `:root` из [DesignTokens] — до первой композиции: в `kotin.css` только `var(--…)`. */
fun applyDesignTokens() {
  val t = DesignTokens
  val vars = linkedMapOf(
    "scene" to cssArgb(t.SCENE),
    "card" to cssArgb(t.CARD),
    "subtle" to cssArgb(t.SUBTLE),
    "outline" to cssArgb(t.OUTLINE),
    "ink" to cssArgb(t.INK),
    "muted" to cssArgb(t.MUTED),
    "accent" to cssArgb(t.ACCENT),
    "accent-soft" to cssArgb(t.ACCENT_SOFT),
    "accent-ink" to cssArgb(t.ACCENT_INK),
    "warning" to cssArgb(t.WARNING),
    "error" to cssArgb(t.ERROR),
    "selection" to cssArgb(t.SELECTION),
    "highlight" to cssArgb(t.HIGHLIGHT),
    "check-outline" to cssArgb(t.CHECK_OUTLINE),
    "handle" to cssArgb(t.HANDLE),
    "scrim" to cssArgb(t.INK, t.SCRIM_ALPHA),
    "pressed" to cssArgb(t.INK, t.PRESSED_ALPHA),
    "hint" to cssArgb(t.INK, t.HINT_ALPHA),
    "on-hint" to cssArgb(t.WHITE, t.ON_HINT_ALPHA),
    "white" to cssArgb(t.WHITE),
    "black" to cssArgb(t.BLACK),
    "axis-x" to cssArgb(t.AXIS_X),
    "axis-y" to cssArgb(t.AXIS_Y),
    "axis-z" to cssArgb(t.AXIS_Z),
    "los" to bezier(t.LINEAR_OUT_SLOW_IN),
    "fos" to bezier(t.FAST_OUT_SLOW_IN),
    "cell-line" to "${t.CELL_LINE}px",
    "motion" to "${t.MOTION_MS}ms",
    "exit" to "${t.EXIT_MS}ms",
    "appear-scale" to t.APPEAR_SCALE.toString(),
    "float-shadow" to platformShadow(t.FLOAT_ELEVATION),
    "empty-shadow" to platformShadow(8),
    "soft-8" to softShadow(8),
    "soft-12" to softShadow(12),
    "soft-16" to softShadow(16),
    "soft-24" to softShadow(24),
    "drawer-shadow" to drawerShadow(24),
  )
  val root = document.documentElement as HTMLElement
  for ((name, value) in vars) root.style.setProperty("--$name", value)
}
