package dev.iskandern.kotin

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.sin

/**
 * Оси чертежа X, Y (север), Z (вверх) на экране при азимуте [yaw] и угле места [pitch] камеры: для каждой по три
 * числа подряд в [out] — (вправо, вверх по экрану, к зрителю), длина вектора 1. Сцена: X чертежа — X, Y чертежа — −Z,
 * Z чертежа — Y.
 */
internal fun axesOnScreen(yaw: Float, pitch: Float, out: FloatArray = FloatArray(9)): FloatArray {
  val sy = sin(yaw)
  val cy = cos(yaw)
  val sp = sin(pitch)
  val cp = cos(pitch)
  // вправо (cos yaw, 0, −sin yaw), вверх (−sin p·sin y, cos p, −sin p·cos y), к камере (cos p·sin y, sin p, cos p·cos y)
  out[0] = cy; out[1] = -sp * sy; out[2] = cp * sy
  out[3] = sy; out[4] = sp * cy; out[5] = -cp * cy
  out[6] = 0f; out[7] = cp; out[8] = sp
  return out
}

/**
 * Оси на экране для текущей позы: линии и три подписи читают их на одном кадре, считаются они один раз и в один буфер —
 * без мусора. Не derivedStateOf: тот различает значения по equals, а буфер один и тот же. Только из UI-потока.
 */
private class ScreenAxes {
  private val axes = FloatArray(9)
  private var yaw = Float.NaN
  private var pitch = Float.NaN

  fun at(yaw: Float, pitch: Float): FloatArray {
    if (yaw != this.yaw || pitch != this.pitch) {
      axesOnScreen(yaw, pitch, axes)
      this.yaw = yaw
      this.pitch = pitch
    }
    return axes
  }
}

/**
 * Индикатор осей в углу экрана: поворачивается вместе с камерой, тап ([onHome]) — плавный возврат к исходному виду
 * сцены. На каждом кадре поворота перерисовываются только три линии: круг — фон без перерисовки, подписи — свои слои,
 * нарисованные один раз и только сдвигаемые (на слабом планшете подписи на общем холсте стоили до 1,5 мс на кадр).
 * Подписи — оси файла: при переставленных осях ([swapXY]) на север смотрит X, на восток — Y.
 */
@Composable
fun AxisIndicator(controller: SceneCameraController, swapXY: Boolean, onHome: () -> Unit, modifier: Modifier = Modifier) {
  val measurer = rememberTextMeasurer()
  val home by rememberUpdatedState(onHome)
  // подписи осей не меняются (кроме перестановки X и Y): меряются один раз
  val labels = remember(measurer, swapXY) {
    AXIS_NAMES.indices.map { i ->
      val name = if (swapXY && i < 2) AXIS_NAMES[1 - i] else AXIS_NAMES[i]
      measurer.measure(name, TextStyle(color = AXIS_COLORS[i], fontSize = 11.sp, fontWeight = FontWeight.Bold))
    }
  }
  val axes = remember(controller) { ScreenAxes() }
  Box(
    modifier
      .size(AXIS_INDICATOR_SIZE)
      .background(KotinColors.Card, CircleShape)
      .border(1.dp, KotinColors.Outline, CircleShape)
      .pointerInput(Unit) { detectTapGestures { home() } },
  ) {
    Canvas(Modifier.matchParentSize()) {
      val center = Offset(size.width / 2, size.height / 2)
      val len = size.minDimension * AXIS_LENGTH
      val a = axes.at(controller.viewYaw, controller.viewPitch)
      // дальние оси первыми: ближняя рисуется поверх; три оси — сортировка руками, без списка
      var first = 0; var second = 1; var third = 2
      if (a[first * 3 + 2] > a[second * 3 + 2]) { val t = first; first = second; second = t }
      if (a[second * 3 + 2] > a[third * 3 + 2]) { val t = second; second = third; third = t }
      if (a[first * 3 + 2] > a[second * 3 + 2]) { val t = first; first = second; second = t }
      val width = 2.5.dp.toPx()
      for (k in 0..2) {
        val i = if (k == 0) first else if (k == 1) second else third
        drawLine(AXIS_COLORS[i], center, center + Offset(a[i * 3], -a[i * 3 + 1]) * len, strokeWidth = width, cap = StrokeCap.Round)
      }
    }
    labels.forEachIndexed { i, label ->
      val width = with(LocalDensity.current) { label.size.width.toDp() }
      val height = with(LocalDensity.current) { label.size.height.toDp() }
      Canvas(
        Modifier.size(width, height).graphicsLayer {
          // подпись за концом оси; ось, смотрящая на зрителя, — подпись у центра
          val a = axes.at(controller.viewYaw, controller.viewPitch)
          val side = AXIS_INDICATOR_SIZE.toPx()
          val reach = side * AXIS_LENGTH + 8.dp.toPx()
          translationX = side / 2 + a[i * 3] * reach - size.width / 2
          translationY = side / 2 - a[i * 3 + 1] * reach - size.height / 2
        },
      ) { drawText(label) }
    }
  }
}

private val AXIS_INDICATOR_SIZE = 72.dp

/** Длина оси — доля стороны индикатора. */
private const val AXIS_LENGTH = 0.32f

private val AXIS_NAMES = listOf("X", "Y", "Z")
private val AXIS_COLORS = listOf(KotinColors.AxisX, KotinColors.AxisY, KotinColors.AxisZ)
