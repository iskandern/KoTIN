package dev.iskandern.kotin.web

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import dev.iskandern.kotin.OffscreenDrawing
import dev.iskandern.kotin.formatGrouped
import dev.iskandern.kotin.resources.Res
import dev.iskandern.kotin.resources.distance_m
import dev.iskandern.kotin.resources.drawing_pointer
import dev.iskandern.kotin.resources.to_drawing
import dev.iskandern.kotin.roundedDistance
import dev.iskandern.kotin.web.dom.Div
import dev.iskandern.kotin.web.dom.Span
import dev.iskandern.kotin.web.dom.Text
import org.jetbrains.compose.resources.stringResource
import org.w3c.dom.HTMLElement
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.min

/** Поля, занятые другими плавающими элементами, px. */
class Reserved(val left: Int, val top: Int, val right: Int, val bottom: Int)

/**
 * Указатель на чертёж, ушедший из кадра: плашка у края экрана там, где край пересекает луч из центра кадра на чертёж,
 * стрелка в ней — точно по направлению, рядом расстояние до чертежа в плане. Нажатие — [onClick].
 * [reserved] — поля, занятые другими плавающими элементами: плашка в них не заходит. [text] и [clickLabel] — слово
 * на плашке и подпись нажатия: у далёких объектов за кадром они свои, вид тот же.
 *
 * Место плашки и поворот стрелки ставятся напрямую в стиль по каждой позе камеры ([pose] — её смена), а текст
 * пересобирается, лишь когда меняется округлённое расстояние.
 */
@Composable
fun DrawingPointer(
  drawing: () -> OffscreenDrawing?, pose: () -> Any, reserved: Reserved, onClick: () -> Unit,
  text: String = stringResource(Res.string.drawing_pointer),
  clickLabel: String = stringResource(Res.string.to_drawing),
) {
  val drawingNow by rememberUpdatedState(drawing)
  val last = remember { LastDrawing() }
  val current = { drawingNow()?.also { last.value = it } ?: last.value }
  val distance by remember { derivedStateOf { (drawingNow() ?: last.value)?.distance?.let(::roundedDistance) } }
  val plate = remember { arrayOfNulls<HTMLElement>(2) }
  val reservedNow by rememberUpdatedState(reserved)
  Div({ classes("layer"); style { property("inset", "0"); property("pointer-events", "none") } }) {
    Div({
      classes("pointer", "floating", "ripple")
      attr("role", "button")
      attr("aria-label", clickLabel)
      style { property("pointer-events", "auto"); property("visibility", "hidden") }
      onClick { onClick() }
    }, ref = { plate[0] = it as HTMLElement; {} }) {
      Div({ classes("arrow") }, ref = { plate[1] = it as HTMLElement; {} }) { IconView(Icon.Arrow, 16) }
      Span({ classes("label", "body-medium") }) { Text(text) }
      // ближе метра — точка интереса почти над чертежом, расстояние ни о чём не говорит
      distance?.let {
        Span({ classes("distance", "body-medium") }) { Text(stringResource(Res.string.distance_m, formatGrouped(it, 0))) }
      }
    }
  }
  LaunchedEffect(Unit) {
    snapshotFlow { pose(); reservedNow; distance }.collect {
      val element = plate[0] ?: return@collect
      val arrow = plate[1] ?: return@collect
      val parent = element.parentElement as? HTMLElement ?: return@collect
      val d = current() ?: return@collect
      val r = reservedNow
      val width = parent.clientWidth.toFloat()
      val height = parent.clientHeight.toFloat()
      val w = element.offsetWidth.toFloat()
      val h = element.offsetHeight.toFloat()
      // центр плашки ходит по прямоугольнику, внутри которого она целиком помещается между занятыми полями
      val cx = (r.left + width - r.right) / 2f
      val cy = (r.top + height - r.bottom) / 2f
      val hx = ((width - r.left - r.right - w) / 2f).coerceAtLeast(0f)
      val hy = ((height - r.top - r.bottom - h) / 2f).coerceAtLeast(0f)
      val t = min(
        if (abs(d.dirX) > 1e-6f) hx / abs(d.dirX) else Float.POSITIVE_INFINITY,
        if (abs(d.dirY) > 1e-6f) hy / abs(d.dirY) else Float.POSITIVE_INFINITY,
      )
      element.style.transform = "translate(${cx + d.dirX * t - w / 2f}px, ${cy + d.dirY * t - h / 2f}px)"
      element.style.visibility = "visible"
      arrow.style.transform = "rotate(${atan2(d.dirY, d.dirX) * 180 / PI}deg)"
    }
  }
}

private class LastDrawing {
  var value: OffscreenDrawing? = null
}
