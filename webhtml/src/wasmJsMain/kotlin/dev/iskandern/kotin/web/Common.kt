package dev.iskandern.kotin.web

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import dev.iskandern.kotin.web.dom.AttrsScope
import dev.iskandern.kotin.web.dom.Div
import dev.iskandern.kotin.web.dom.SvgPath
import dev.iskandern.kotin.web.dom.Svg
import dev.iskandern.kotin.web.dom.px
import kotlinx.browser.window
import kotlinx.coroutines.delay

/** Значки интерфейса — те же контуры, что `KotinIcons` (сетка 24 × 24, круглые концы), путями SVG. */
enum class Icon(val path: String, val width: Float = 2f) {
  Plus("M12 5V19M5 12H19"),
  Minus("M5 12H19"),
  Orbit("M7 12A5 5 0 1 1 17 12A5 5 0 1 1 7 12M2.3 15.7A10.5 3.2 -24 1 0 21.7 8.3A10.5 3.2 -24 1 0 2.3 15.7", 1.8f),
  Map("M3 6L9 4L15 6L21 4V18L15 20L9 18L3 20ZM9 4V18M15 6V20", 1.8f),
  Menu("M4 6H20M4 12H20M4 18H20"),
  Folder("M3 7A2 2 0 0 1 5 5H9L11 7H19A2 2 0 0 1 21 9V17A2 2 0 0 1 19 19H5A2 2 0 0 1 3 17Z"),
  Close("M6 6L18 18M18 6L6 18"),
  Check("M5 12L10 17L19 7", 3.2f),
  ChevronDown("M6 9L12 15L18 9"),
  ChevronUp("M6 15L12 9L18 15"),
  ChevronRight("M9 6L15 12L9 18"),
  ChevronLeft("M15 6L9 12L15 18"),
  Arrow("M5 12H19M13 6L19 12L13 18", 2.4f),
}

/** Значок [icon] [size] × [size] цветом [color] (null — цвет текста); [rotate] — поворот, градусы. */
@Composable
fun IconView(icon: Icon, size: Int, color: String? = null, rotate: Float = 0f, attrs: (AttrsScope.() -> Unit)? = null) {
  Svg({
    classes("icon")
    attr("width", size.toString())
    attr("height", size.toString())
    attr("viewBox", "0 0 24 24")
    attr("stroke-width", icon.width.toString())
    style {
      if (color != null) property("color", color)
      if (rotate != 0f) property("transform", "rotate(${rotate}deg)")
    }
    attrs?.invoke(this)
  }) { SvgPath(icon.path) }
}

/** Кнопка-значок [size] × [size]: без своего фона, нажатие подсвечивается в пределах скругления [radius] (null — 10 px). */
@Composable
fun IconBox(
  icon: Icon, description: String, size: Int, onClick: () -> Unit, radius: String = "10px", color: String? = null, iconSize: Int = 20,
  attrs: (AttrsScope.() -> Unit)? = null,
) {
  Div({
    classes("icon-box", "ripple")
    attr("role", "button")
    attr("aria-label", description)
    attr("title", description)
    style {
      property("width", size.px)
      property("height", size.px)
      property("border-radius", radius)
    }
    onClick { onClick() }
    attrs?.invoke(this)
  }) { IconView(icon, iconSize, color) }
}

/** Цвет CSS из 0xRRGGBB. */
fun cssColor(rgb: Int): String = "#" + (rgb and 0xFFFFFF).toString(16).padStart(6, '0')

/**
 * Показывает [content], пока [visible], и ещё [exitMs] после — на время ухода; [content] получает, видна ли панель:
 * по нему классы переводят её в конечное состояние (появление — переходом CSS). Видимая с самого начала — сразу.
 */
@Composable
fun Appear(visible: Boolean, exitMs: Long = EXIT_MS, content: @Composable (shown: Boolean) -> Unit) {
  var mounted by remember { mutableStateOf(visible) }
  var shown by remember { mutableStateOf(visible) }
  LaunchedEffect(visible) {
    if (visible) {
      mounted = true
      // элемент сначала стоит в начальном состоянии хотя бы кадр — иначе переходу не от чего идти
      withFrameNanos { }
      withFrameNanos { }
      shown = true
    } else {
      shown = false
      delay(exitMs)
      mounted = false
    }
  }
  if (mounted) content(shown)
}

/** Длительность ухода панели, мс (EXIT_MS app). */
const val EXIT_MS = 150L

/** Длительность появления и исчезновения панелей, мс (MOTION_MS app). */
const val MOTION_MS = 200L

/** Последнее не-null значение [value]: панель, которая гаснет, показывает то, что было в ней до закрытия. */
@Composable
fun <T : Any> rememberLast(value: T?): T? {
  val last = remember { LastValue<T>() }
  if (value != null) last.value = value
  return last.value
}

private class LastValue<T : Any> {
  var value: T? = null
}

/** Размер окна в пикселях CSS (= dp Compose). */
class WindowSize(val width: Int, val height: Int)

@Composable
fun rememberWindowSize(): WindowSize {
  var size by remember { mutableStateOf(WindowSize(window.innerWidth, window.innerHeight)) }
  DisposableEffect(Unit) {
    val listener: (org.w3c.dom.events.Event) -> Unit = { size = WindowSize(window.innerWidth, window.innerHeight) }
    window.addEventListener("resize", listener)
    onDispose { window.removeEventListener("resize", listener) }
  }
  return size
}
