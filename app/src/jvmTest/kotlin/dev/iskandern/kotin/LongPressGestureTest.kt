package dev.iskandern.kotin

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import io.github.erkko68.filament.compose.scene.Position
import io.github.erkko68.filament.compose.scene.Projection
import io.github.erkko68.filament.compose.scene.rememberCameraState
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Долгое нажатие в `sceneGestures`: жест идёт через настоящую раскладку Compose (`ImageComposeScene`), время —
 * настоящее, поэтому паузы в тестах реальные (порог долгого нажатия — 500 мс).
 */
@OptIn(ExperimentalComposeUiApi::class)
class LongPressGestureTest {

  private class Harness {
    val presses = CopyOnWriteArrayList<Pair<Offset, IntSize>>()
    lateinit var controller: SceneCameraController
    val scene = ImageComposeScene(400, 300, Density(1f)) {
      val state = rememberCameraState(
        initialEye = Position(0f, 60f, 80f),
        initialTarget = Position(0f, 0f, 0f),
        initialProjection = Projection.Perspective(fovDegrees = 45.0, near = 1.0, far = 1000.0),
      )
      val controller = remember { SceneCameraController(state, 100f, 45.0) }
      this@Harness.controller = controller
      var tick by remember { mutableStateOf(0) }
      Box(Modifier.fillMaxSize().sceneGestures(controller) { position, size -> presses.add(position to size); tick++ })
    }

    init { scene.render() }

    private val held = PointerButtons(isPrimaryPressed = true)

    fun press(x: Float, y: Float) =
      scene.sendPointerEvent(PointerEventType.Press, Offset(x, y), buttons = held, button = PointerButton.Primary)

    fun move(x: Float, y: Float) = scene.sendPointerEvent(PointerEventType.Move, Offset(x, y), buttons = held)

    fun release(x: Float, y: Float) =
      scene.sendPointerEvent(PointerEventType.Release, Offset(x, y), buttons = PointerButtons(), button = PointerButton.Primary)

    /** Пауза с отрисовкой кадров: паузу ждёт и сама сцена, и сопрограмма жеста. */
    fun hold(millis: Long) {
      val end = System.currentTimeMillis() + millis
      while (System.currentTimeMillis() < end) {
        Thread.sleep(20)
        scene.render()
      }
    }

    fun close() = scene.close()
  }

  private fun withHarness(block: Harness.() -> Unit) {
    val harness = Harness()
    try {
      harness.block()
    } finally {
      harness.close()
    }
  }

  @Test
  fun `нажатие на месте дольше порога - долгое нажатие с точкой и размером вида`() = withHarness {
    press(120f, 80f)
    hold(750)
    release(120f, 80f)
    hold(100)

    assertEquals(1, presses.size)
    val (position, size) = presses.single()
    assertEquals(120f, position.x, 1f)
    assertEquals(80f, position.y, 1f)
    assertEquals(IntSize(400, 300), size)
  }

  @Test
  fun `быстрое нажатие - не долгое`() = withHarness {
    press(120f, 80f)
    hold(120)
    release(120f, 80f)
    hold(700)

    assertTrue(presses.isEmpty(), "сработало: $presses")
  }

  @Test
  fun `сдвиг дальше порога - панорамирование, а не долгое нажатие`() = withHarness {
    val before = controller.pose
    press(120f, 80f)
    move(160f, 80f)
    move(200f, 80f)
    hold(750)
    release(200f, 80f)
    hold(100)

    assertTrue(presses.isEmpty(), "сработало: $presses")
    assertTrue(controller.pose != before, "камера должна была сдвинуться")
  }

  @Test
  fun `после долгого нажатия камера до отпускания не двигается`() = withHarness {
    press(120f, 80f)
    hold(750)
    assertEquals(1, presses.size)
    val held = controller.pose
    move(200f, 140f)
    move(260f, 200f)
    hold(100)
    release(260f, 200f)
    hold(300)

    assertEquals(held, controller.pose)
    assertEquals(1, presses.size)
  }
}
