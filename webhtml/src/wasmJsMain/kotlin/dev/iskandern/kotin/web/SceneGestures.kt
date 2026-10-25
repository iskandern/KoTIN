package dev.iskandern.kotin.web

import androidx.compose.ui.geometry.Offset
import dev.iskandern.kotin.CameraMode
import dev.iskandern.kotin.CameraPose
import dev.iskandern.kotin.SceneCameraController
import kotlinx.browser.window
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.Event
import org.w3c.dom.events.WheelEvent
import org.w3c.dom.pointerevents.PointerEvent
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.max
import kotlin.time.TimeSource

// Жесты камеры — те же, что `sceneGestures` app (CameraGestures.kt), на pointer-событиях страницы. Координаты — в
// пикселях устройства, как у Compose (плотность — devicePixelRatio): от них скорость инерции и вращения.

/** Касание в событии: как PointerInputChange Compose — положение сейчас и в прошлом событии, нажато ли. */
private class Change(
  val id: Int, val position: Offset, val previousPosition: Offset, val pressed: Boolean, val previousPressed: Boolean,
  val uptimeMillis: Long, val mouse: Boolean,
) {
  val changedToDown: Boolean get() = pressed && !previousPressed
  val positionChanged: Boolean get() = position != previousPosition
}

private class GestureEvent(val changes: List<Change>, val primaryPressed: Boolean, val shift: Boolean)

private class Tracked(var position: Offset, var pressed: Boolean, val mouse: Boolean)

/**
 * Жесты сцены на [element]: события в канал, разбор — корутиной в [scope] (там же инерция и движения камеры).
 * Возвращает, как их снять.
 */
fun attachSceneGestures(
  element: HTMLElement, controller: SceneCameraController, scope: CoroutineScope,
  onTap: (Offset, Float, Float) -> Unit, onLongPress: (Offset, Float, Float) -> Unit,
): () -> Unit {
  val events = Channel<GestureEvent>(Channel.UNLIMITED)
  val pointers = LinkedHashMap<Int, Tracked>()
  val ratio = { window.devicePixelRatio.toFloat() }
  fun position(e: PointerEvent): Offset {
    val rect = element.getBoundingClientRect()
    return Offset(((e.clientX - rect.left) * ratio()).toFloat(), ((e.clientY - rect.top) * ratio()).toFloat())
  }
  // событие — все отслеживаемые касания: изменившееся с новым положением, остальные на месте
  fun send(e: PointerEvent, id: Int, position: Offset, pressed: Boolean) {
    val time = e.timeStamp.toDouble().toLong()
    val changes = pointers.map { (pid, t) ->
      if (pid == id) Change(pid, position, t.position, pressed, t.pressed, time, t.mouse)
      else Change(pid, t.position, t.position, t.pressed, t.pressed, time, t.mouse)
    }
    pointers[id]?.let { it.position = position; it.pressed = pressed }
    events.trySend(GestureEvent(changes, (e.buttons.toInt() and 1) != 0, e.shiftKey))
  }
  val down: (Event) -> Unit = { event ->
    val e = event as PointerEvent
    element.setPointerCapture(e.pointerId)
    val mouse = e.pointerType == "mouse"
    if (e.pointerId !in pointers) {
      val p = position(e)
      pointers[e.pointerId] = Tracked(p, false, mouse)
      send(e, e.pointerId, p, true)
    }
    e.preventDefault()
  }
  val move: (Event) -> Unit = { event ->
    val e = event as PointerEvent
    if (pointers[e.pointerId]?.pressed == true) send(e, e.pointerId, position(e), true)
  }
  val up: (Event) -> Unit = { event ->
    val e = event as PointerEvent
    if (pointers[e.pointerId]?.pressed == true) {
      send(e, e.pointerId, position(e), false)
      pointers.remove(e.pointerId)
    }
  }
  // контекстное меню браузера — правой кнопкой вращают
  val menu: (Event) -> Unit = { it.preventDefault() }

  // колесо: опора прошлого шага — тот же курсор и поза, которую оставил этот шаг, — рельеф под курсором тот же
  var anchoredAt: Offset? = null
  var anchoredPose: CameraPose? = null
  var anchoredMode: CameraMode? = null
  val wheel: (Event) -> Unit = { event ->
    val e = event as WheelEvent
    e.preventDefault()
    val rect = element.getBoundingClientRect()
    val p = Offset(((e.clientX - rect.left) * ratio()).toFloat(), ((e.clientY - rect.top) * ratio()).toFloat())
    val w = element.clientWidth * ratio()
    val h = element.clientHeight * ratio()
    if (p != anchoredAt || controller.pose != anchoredPose || controller.mode != anchoredMode) controller.anchorAt(p.x, p.y, w, h)
    // в браузере деление колеса — десятки, поэтому шаг одного события ограничен
    val dy = if (e.shiftKey && e.deltaX == 0.0) 0.0 else e.deltaY
    val factor = exp((dy.toFloat() * WHEEL_ZOOM).coerceIn(-MAX_WHEEL_STEP, MAX_WHEEL_STEP))
    controller.zoom(factor, p.x, p.y, w, h)
    anchoredAt = p
    anchoredPose = controller.pose
    anchoredMode = controller.mode
  }

  element.addEventListener("pointerdown", down)
  element.addEventListener("pointermove", move)
  element.addEventListener("pointerup", up)
  element.addEventListener("pointercancel", up)
  element.addEventListener("contextmenu", menu)
  addWheel(element, wheel)

  val job = scope.launch {
    coroutineScope {
      // движение камеры держит контроллер: касание гасит и инерцию, и переход по команде
      while (true) {
        val w = element.clientWidth * ratio()
        val h = element.clientHeight * ratio()
        val end = mapGesture(
          events, controller, w, h, 8f * ratio(),
          onTap = { onTap(it, w, h) },
          onLongPress = { onLongPress(it, w, h) },
          onDown = { controller.stopMotion() },
          // наклон двумя пальцами закончился: инерция наклона и сразу возврат из резиновой зоны
          onTiltEnd = { v ->
            controller.startMotion(this@coroutineScope) {
              v?.let { controller.tiltFling(it) }
              controller.settleTilt()
            }
          },
        )
        // после жеста: инерция сдвига, затем возврат из резиновой зоны угла
        if (end != null || !controller.motionActive) {
          controller.startMotion(this@coroutineScope) {
            end?.let {
              if (it.orbit) controller.orbitFling(it.velocity.x, it.velocity.y)
              else controller.fling(it.velocity.x, it.velocity.y, it.at.x, it.at.y, w, h)
            }
            controller.settleTilt()
          }
        }
      }
    }
  }
  return {
    job.cancel()
    element.removeEventListener("pointerdown", down)
    element.removeEventListener("pointermove", move)
    element.removeEventListener("pointerup", up)
    element.removeEventListener("pointercancel", up)
    element.removeEventListener("contextmenu", menu)
    element.removeEventListener("wheel", wheel)
  }
}

@JsFun("(element, listener) => element.addEventListener('wheel', listener, { passive: false })")
private external fun addWheel(element: HTMLElement, listener: (Event) -> Unit)

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

/**
 * Один жест от первого касания до отпускания (см. `mapGesture` app). Возвращает скорость сдвига (px/с) и точку
 * отпускания, если жест — свайп одним пальцем.
 */
private suspend fun mapGesture(
  events: Channel<GestureEvent>,
  controller: SceneCameraController,
  w: Float, h: Float, slop: Float,
  onTap: (Offset) -> Unit,
  onLongPress: (Offset) -> Unit,
  onDown: () -> Unit,
  onTiltEnd: (Float?) -> Unit,
): FlingStart? {
  var event: GestureEvent
  do {
    event = events.receive()
  } while (!event.changes.all { it.changedToDown })
  onDown()
  val map = controller.mode == CameraMode.MAP
  // мышь: вторичная схема — правая/средняя кнопка или Shift
  val secondary = event.changes[0].mouse && (!event.primaryPressed || event.shift)
  // опора: вращение — вокруг того, что в центре кадра; сдвиг — под пальцем
  if (map == secondary) controller.anchorAt(w / 2f, h / 2f, w, h) else controller.anchorAt(event.changes[0].position.x, event.changes[0].position.y, w, h)
  val samples = ArrayList<Sample>()
  var lastPos = event.changes[0].position
  var upTime = event.changes[0].uptimeMillis
  var multi = false
  var moved = 0f
  var startedPanning = false
  var twoFinger = TwoFingerMode.UNDECIDED
  var accY = 0f
  var accSpan = 0f
  var accRot = 0f
  val tiltSamples = ArrayList<Sample>()
  var tiltEnded = false
  var lastOrbit = false
  val pressMark = TimeSource.Monotonic.markNow()
  var longPressed = false

  while (true) {
    val waitsLongPress = !secondary && !longPressed && !multi && moved <= slop
    val remaining = LONG_PRESS_MS - pressMark.elapsedNow().inWholeMilliseconds
    val e = if (waitsLongPress) withTimeoutOrNull(max(remaining, 0L)) { events.receive() } else events.receive()
    if (e == null) {
      longPressed = true
      onLongPress(lastPos)
      continue
    }
    val pressed = e.changes.filter { it.pressed }
    upTime = e.changes[0].uptimeMillis
    if (longPressed) {
      if (pressed.isEmpty()) break
      continue
    }
    if (twoFinger == TwoFingerMode.TILT && !tiltEnded && pressed.size < 2) {
      tiltEnded = true
      val v = releaseVelocity(tiltSamples, upTime).y * TILT_SPEED * TILT_FLING_GAIN
      onTiltEnd(v.takeIf { abs(it) > TILT_FLING_MIN_SPEED })
    }
    if (pressed.isEmpty()) break
    if (pressed.size >= 2) {
      val two = pressed.sortedBy { it.id }
      val a = two[0]
      val b = two[1]
      if (!multi) {
        startedPanning = moved > slop
        val center = (a.position + b.position) / 2f
        controller.anchorAt(center.x, center.y, w, h)
      }
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
    } else {
      val c = pressed.firstOrNull() ?: break
      if (c.positionChanged) {
        val d = c.position - c.previousPosition
        if (!multi) {
          moved += abs(d.x) + abs(d.y)
          samples.add(Sample(c.uptimeMillis, c.position))
          if (samples.size > MAX_SAMPLES) samples.removeAt(0)
          lastPos = c.position
        }
        // карта: палец двигает, оставшийся после двух — вращает; орбита: наоборот
        val orbit = if (map) multi || secondary else !secondary
        lastOrbit = orbit
        if (orbit) controller.orbit(d.x, d.y) else controller.pan(c.position.x, c.position.y, d.x, d.y, w, h)
      }
    }
  }
  if (!secondary && !multi && !longPressed && moved <= slop) onTap(lastPos)
  val v = if (!multi && !longPressed) releaseVelocity(samples, upTime) else Offset.Zero
  return if (v.getDistance() > FLING_MIN_SPEED) FlingStart(v, lastPos, lastOrbit) else null
}

private const val LONG_PRESS_MS = 500L
private const val WHEEL_ZOOM = 0.05f
private const val MAX_WHEEL_STEP = 0.12f
private const val TILT_SPEED = 0.006f
private const val FLING_MIN_SPEED = 150f
private const val TILT_FLING_MIN_SPEED = 0.15f
private const val TILT_FLING_GAIN = 0.4f
private const val FLING_STOP_MS = 50L
private const val FLING_WINDOW_MS = 100L
private const val MAX_SAMPLES = 32
