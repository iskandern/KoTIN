package dev.iskandern.kotin

import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.coroutineScope
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.max
import kotlin.time.TimeSource
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Жесты управления [SceneCameraController] в его текущем [SceneCameraController.mode].
 * [onLongPress] — долгое нажатие на месте одним пальцем или основной кнопкой мыши: экранная точка и размер вида.
 * Нажатие, в котором камеру двигали или клали второй палец, долгим не считается. [onTap] — короткое нажатие на месте
 * так же одним пальцем или основной кнопкой мыши.
 */
fun Modifier.sceneGestures(
  controller: SceneCameraController,
  onTap: (Offset, IntSize) -> Unit = { _, _ -> },
  onLongPress: (Offset, IntSize) -> Unit = { _, _ -> },
): Modifier = this
  .pointerInput(controller) {
    coroutineScope {
      // движение камеры держит контроллер: касание гасит и инерцию, и переход по команде
      while (true) {
        val end = awaitPointerEventScope {
          mapGesture(
            controller,
            onTap = onTap,
            onLongPress = onLongPress,
            onDown = { controller.stopMotion() },
            // наклон двумя пальцами закончился (даже если один палец ещё на экране): инерция наклона и сразу возврат из резиновой зоны
            onTiltEnd = { v ->
              controller.startMotion(this@coroutineScope) {
                v?.let { controller.tiltFling(it) }
                controller.settleTilt()
              }
            },
          )
        }
        // после жеста: инерция сдвига, затем возврат из резиновой зоны угла
        if (end != null || !controller.motionActive) {
          controller.startMotion(this@coroutineScope) {
            end?.let {
              if (it.orbit) controller.orbitFling(it.velocity.x, it.velocity.y)
              else controller.fling(it.velocity.x, it.velocity.y, it.at.x, it.at.y, size.width.toFloat(), size.height.toFloat())
            }
            controller.settleTilt()
          }
        }
      }
    }
  }
  .pointerInput(controller) {
    awaitPointerEventScope {
      // опора прошлого шага колеса: тот же курсор и поза, которую оставил этот шаг, — рельеф под курсором тот же
      var anchoredAt: Offset? = null
      var anchoredPose: CameraPose? = null
      var anchoredMode: CameraMode? = null
      while (true) {
        val e = awaitPointerEvent()
        if (e.type != PointerEventType.Scroll) continue
        val c = e.changes.firstOrNull() ?: continue
        if (c.position != anchoredAt || controller.pose != anchoredPose || controller.mode != anchoredMode) {
          controller.anchorAt(c.position.x, c.position.y, size.width.toFloat(), size.height.toFloat())
        }
        // величина деления зависит от платформы (в браузере — десятки), поэтому шаг одного события ограничен
        val factor = exp((c.scrollDelta.y * WHEEL_ZOOM).coerceIn(-MAX_WHEEL_STEP, MAX_WHEEL_STEP))
        controller.zoom(factor, c.position.x, c.position.y, size.width.toFloat(), size.height.toFloat())
        anchoredAt = c.position
        anchoredPose = controller.pose
        anchoredMode = controller.mode
        e.changes.forEach { it.consume() }
      }
    }
  }

private enum class TwoFingerMode { UNDECIDED, TILT, ZOOM_ROTATE }

private class FlingStart(val velocity: Offset, val at: Offset, val orbit: Boolean)

private class Sample(val time: Long, val position: Offset)

/** Скорость пальца (px/с) по последним ~100 мс до отпускания; палец, задержавшийся перед отрывом, скорости не даёт. */
private fun releaseVelocity(samples: List<Sample>, upTime: Long): Offset {
  val last = samples.lastOrNull() ?: return Offset.Zero
  if (upTime - last.time > FLING_STOP_MS) return Offset.Zero
  val first = samples.first { last.time - it.time <= FLING_WINDOW_MS }
  val dt = (last.time - first.time) / 1000f
  if (dt < 0.01f) return Offset.Zero
  return (last.position - first.position) / dt
}

/** Один жест от первого касания до отпускания. Возвращает скорость сдвига (px/с) и точку отпускания, если жест — свайп одним пальцем в режиме карты. */
/** [onTiltEnd] вызывается, когда наклон двумя пальцами закончился, со скоростью наклона (рад/с) или null. */
/** [onLongPress] — палец или кнопка простояли на месте дольше `longPressTimeoutMillis`; остаток жеста камеру уже не двигает. */
/** [onTap] — палец или кнопка отпущены на месте раньше. */
private suspend fun AwaitPointerEventScope.mapGesture(
  controller: SceneCameraController,
  onTap: (Offset, IntSize) -> Unit,
  onLongPress: (Offset, IntSize) -> Unit,
  onDown: () -> Unit,
  onTiltEnd: (Float?) -> Unit,
): FlingStart? {
  var event: PointerEvent
  do {
    event = awaitPointerEvent()
  } while (!event.changes.all { it.changedToDownIgnoreConsumed() })
  onDown()
  val map = controller.mode == CameraMode.MAP
  // мышь: вторичная схема — правая/средняя кнопка или Shift
  val secondary = event.changes[0].type == PointerType.Mouse &&
    (!event.buttons.isPrimaryPressed || event.keyboardModifiers.isShiftPressed)
  val w = size.width.toFloat()
  val h = size.height.toFloat()
  val slop = viewConfiguration.touchSlop
  // опора: вращение (вторичная схема на карте, основная в орбите) — вокруг того, что в центре кадра; сдвиг — под пальцем
  if (map == secondary) controller.anchorAt(w / 2f, h / 2f, w, h) else controller.anchorAt(event.changes[0].position.x, event.changes[0].position.y, w, h)
  val samples = ArrayList<Sample>()
  var lastPos = event.changes[0].position
  var upTime = event.changes[0].uptimeMillis
  var multi = false          // были ли два пальца
  var moved = 0f             // путь одного пальца до появления второго
  var startedPanning = false // перемещение уже шло, когда лёг второй палец
  var twoFinger = TwoFingerMode.UNDECIDED
  var accY = 0f              // путь жеста двумя пальцами до выбора: вертикаль / масштаб / поворот
  var accSpan = 0f
  var accRot = 0f
  val tiltSamples = ArrayList<Sample>() // вертикаль середины между пальцами в режиме наклона
  var tiltEnded = false
  var lastOrbit = false      // последнее движение одним пальцем было вращением
  val pressMark = TimeSource.Monotonic.markNow()
  var longPressed = false    // долгое нажатие сработало: до отпускания камера не двигается

  while (true) {
    // долгое нажатие ждём, пока палец почти не сдвинулся и второй не лёг; вторичная схема мыши (вращение) — не в счёт
    val waitsLongPress = !secondary && !longPressed && !multi && moved <= slop
    val remaining = viewConfiguration.longPressTimeoutMillis - pressMark.elapsedNow().inWholeMilliseconds
    val e = if (waitsLongPress) {
      withTimeoutOrNull(max(remaining, 0L)) { awaitPointerEvent() }
    } else {
      awaitPointerEvent()
    }
    if (e == null) {
      longPressed = true
      onLongPress(lastPos, size)
      continue
    }
    val pressed = e.changes.filter { it.pressed }
    upTime = e.changes[0].uptimeMillis
    if (longPressed) {
      e.changes.forEach { it.consume() }
      if (pressed.isEmpty()) break
      continue
    }
    if (twoFinger == TwoFingerMode.TILT && !tiltEnded && pressed.size < 2) {
      // пальцы разошлись: скорость наклона — по последним ~100 мс движения середины между ними
      tiltEnded = true
      val v = releaseVelocity(tiltSamples, upTime).y * TILT_SPEED * TILT_FLING_GAIN
      onTiltEnd(v.takeIf { abs(it) > TILT_FLING_MIN_SPEED })
    }
    if (pressed.isEmpty()) break
    if (pressed.size >= 2) {
      val two = pressed.sortedBy { it.id.value }
      val a = two[0]
      val b = two[1]
      if (!multi) {
        startedPanning = moved > slop
        // лёг второй палец: опора — рельеф под серединой между пальцами
        val center = (a.position + b.position) / 2f
        controller.anchorAt(center.x, center.y, w, h)
      }
      // палец, только что севший на экран, предыдущей позиции не имеет — этот кадр только запоминается
      if (multi && a.previousPressed && b.previousPressed) {
        val a0 = a.previousPosition
        val b0 = b.previousPosition
        val a1 = a.position
        val b1 = b.position
        val center0 = (a0 + b0) / 2f
        val center1 = (a1 + b1) / 2f
        val span0 = (a0 - b0).getDistance()
        val span1 = (a1 - b1).getDistance()
        val dc = center1 - center0
        if (map) {
          if (!startedPanning && twoFinger == TwoFingerMode.UNDECIDED) {
            // наклон — только если жест начался со сдвига по вертикали; зум или поворот первыми отменяют его на весь жест
            var dAngle = atan2(b1.y - a1.y, b1.x - a1.x) - atan2(b0.y - a0.y, b0.x - a0.x)
            if (dAngle > PI) dAngle -= (2 * PI).toFloat() else if (dAngle < -PI) dAngle += (2 * PI).toFloat()
            accY += abs(dc.y)
            accSpan += abs(span1 - span0)
            accRot += abs(dAngle) * span1 / 2f
            if (maxOf(accY, accSpan, accRot) > slop) {
              twoFinger = if (accY >= accSpan && accY >= accRot) TwoFingerMode.TILT else TwoFingerMode.ZOOM_ROTATE
            }
          }
          if (startedPanning || twoFinger == TwoFingerMode.ZOOM_ROTATE) {
            // точки земли под обоими пальцами остаются под ними: сдвиг, зум и heading — один жест
            if (span0 > 0f && !controller.grabTwo(a0, b0, a1, b1, w, h)) {
              controller.pan(center1.x, center1.y, dc.x, dc.y, w, h)
              controller.zoom(span0 / span1, center1.x, center1.y, w, h)
            }
          } else if (twoFinger == TwoFingerMode.TILT) {
            controller.tilt(dc.y * TILT_SPEED)
            tiltSamples.add(Sample(a.uptimeMillis, Offset(0f, center1.y)))
            if (tiltSamples.size > MAX_SAMPLES) tiltSamples.removeAt(0)
          }
        } else if (span0 > 0f) {
          controller.pan(center1.x, center1.y, dc.x, dc.y, w, h)
          controller.zoom(span0 / span1, center1.x, center1.y, w, h)
        }
      }
      multi = true
      e.changes.forEach { it.consume() }
    } else {
      val c = pressed.firstOrNull() ?: break
      if (c.positionChanged()) {
        val d = c.position - c.previousPosition
        if (!multi) {
          moved += abs(d.x) + abs(d.y)
          samples.add(Sample(c.uptimeMillis, c.position))
          if (samples.size > MAX_SAMPLES) samples.removeAt(0)
          lastPos = c.position
        }
        // карты: палец двигает, оставшийся после двух — вращает; орбита: наоборот
        val orbit = if (map) multi || secondary else !secondary
        lastOrbit = orbit
        if (orbit) controller.orbit(d.x, d.y) else controller.pan(c.position.x, c.position.y, d.x, d.y, w, h)
        c.consume()
      }
    }
  }
  // палец почти не сдвинулся, второго не было, долгим нажатие не стало — короткое нажатие
  if (!secondary && !multi && !longPressed && moved <= slop) onTap(lastPos, size)
  val v = if (!multi && !longPressed) releaseVelocity(samples, upTime) else Offset.Zero
  return if (v.getDistance() > FLING_MIN_SPEED) FlingStart(v, lastPos, lastOrbit) else null
}

private const val WHEEL_ZOOM = 0.05f
private const val MAX_WHEEL_STEP = 0.12f
private const val TILT_SPEED = 0.006f
private const val FLING_MIN_SPEED = 150f
private const val TILT_FLING_MIN_SPEED = 0.15f
private const val TILT_FLING_GAIN = 0.4f
private const val FLING_STOP_MS = 50L
private const val FLING_WINDOW_MS = 100L
private const val MAX_SAMPLES = 32
