package dev.iskandern.kotin.web

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import dev.iskandern.kotin.DesignTokens
import dev.iskandern.kotin.SceneCameraController
import dev.iskandern.kotin.axesOnScreen
import dev.iskandern.kotin.web.dom.Div
import dev.iskandern.kotin.web.dom.Svg
import dev.iskandern.kotin.web.dom.SvgTag
import dev.iskandern.kotin.web.dom.Text
import org.w3c.dom.Element

/**
 * Индикатор осей в углу экрана: поворачивается вместе с камерой, нажатие ([onHome]) — плавный возврат к исходному виду
 * сцены. Поворот не перекомпоновывает: линии и подписи двигаются напрямую в SVG по позе камеры. Подписи — оси файла:
 * при переставленных осях ([swapXY]) на север смотрит X, на восток — Y.
 */
@Composable
fun AxisIndicator(controller: SceneCameraController, swapXY: Boolean, onHome: () -> Unit) {
  val home by rememberUpdatedState(onHome)
  val lines = remember { arrayOfNulls<Element>(3) }
  val labels = remember { arrayOfNulls<Element>(3) }
  Div({
    classes("axis-indicator")
    attr("role", "button")
    onClick { home() }
  }) {
    Svg({ attr("width", "$SIZE"); attr("height", "$SIZE"); attr("viewBox", "0 0 $SIZE $SIZE") }) {
      for (k in 0..2) SvgTag("line", {
        attr("stroke-width", "2.5")
        attr("stroke-linecap", "round")
      }, ref = { lines[k] = it; {} })
      for (i in 0..2) {
        val name = if (swapXY && i < 2) AXIS_NAMES[1 - i] else AXIS_NAMES[i]
        SvgTag("text", {
          classes("axis-label")
          attr("fill", AXIS_COLORS[i])
          attr("text-anchor", "middle")
          attr("dominant-baseline", "central")
        }, ref = { labels[i] = it; {} }) { Text(name) }
      }
    }
  }
  LaunchedEffect(controller) {
    val axes = FloatArray(9)
    snapshotFlow { controller.pose }.collect {
      val a = axesOnScreen(controller.viewYaw, controller.viewPitch, axes)
      val c = SIZE / 2f
      val len = SIZE * AXIS_LENGTH
      // дальние оси первыми: ближняя рисуется поверх
      var first = 0; var second = 1; var third = 2
      if (a[first * 3 + 2] > a[second * 3 + 2]) { val t = first; first = second; second = t }
      if (a[second * 3 + 2] > a[third * 3 + 2]) { val t = second; second = third; third = t }
      if (a[first * 3 + 2] > a[second * 3 + 2]) { val t = first; first = second; second = t }
      for (k in 0..2) {
        val i = if (k == 0) first else if (k == 1) second else third
        val line = lines[k] ?: continue
        line.setAttribute("x1", "$c")
        line.setAttribute("y1", "$c")
        line.setAttribute("x2", "${c + a[i * 3] * len}")
        line.setAttribute("y2", "${c - a[i * 3 + 1] * len}")
        line.setAttribute("stroke", AXIS_COLORS[i])
      }
      // подпись за концом оси; ось, смотрящая на зрителя, — подпись у центра
      val reach = SIZE * AXIS_LENGTH + 8f
      for (i in 0..2) {
        val label = labels[i] ?: continue
        label.setAttribute("x", "${c + a[i * 3] * reach}")
        label.setAttribute("y", "${c - a[i * 3 + 1] * reach}")
      }
    }
  }
}

private const val SIZE = DesignTokens.AXIS_SIZE

/** Длина оси — доля стороны индикатора. */
private const val AXIS_LENGTH = 0.32f

private val AXIS_NAMES = listOf("X", "Y", "Z")
private val AXIS_COLORS = listOf("var(--axis-x)", "var(--axis-y)", "var(--axis-z)")
