package dev.iskandern.kotin

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import io.github.erkko68.filament.compose.scene.Position
import io.github.erkko68.filament.compose.scene.Projection
import io.github.erkko68.filament.compose.scene.rememberCameraState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Команда камеры во время движения: инерция жеста гаснет, переход доходит до цели, а команда поверх незаконченного
 * перехода подхватывает его с ходу. Раньше переход обрывался на первом кадре, где инерция сдвинула камеру, и нажатие
 * (оси, указатель на чертёж) терялось.
 */
@OptIn(ExperimentalComposeUiApi::class)
class CameraMotionTest {

  private companion object {
    const val FRAME_NANOS = 16_000_000L
  }

  private class Harness {
    lateinit var controller: SceneCameraController
    var command by mutableStateOf<CameraCommand?>(null)
    private var refitTo by mutableStateOf<CameraPose?>(null)
    fun refit(home: CameraPose) { refitTo = home }
    val scene = ImageComposeScene(400, 300, Density(1f)) {
      val state = rememberCameraState(
        initialEye = Position(0f, 60f, 80f),
        initialTarget = Position(0f, 0f, 0f),
        initialProjection = Projection.Perspective(fovDegrees = 45.0, near = 1.0, far = 1000.0),
      )
      val controller = remember { SceneCameraController(state, 100f, 45.0) }
      this@Harness.controller = controller
      val motions = rememberCoroutineScope()
      LaunchedEffect(command) { command?.let { controller.execute(it, motions) } }
      LaunchedEffect(refitTo) { refitTo?.let { controller.refitHome(it, motions) } }
      Box(Modifier.fillMaxSize().sceneGestures(controller))
    }

    // условное время, нс: и кадров, и событий указателя — скорость маха контроллер берёт из меток времени событий,
    // так что тест не зависит от загрузки машины. У render по умолчанию время кадра 0, и анимации стоят.
    private var clock = 1_000_000_000L

    init { scene.render(clock) }

    private val held = PointerButtons(isPrimaryPressed = true)

    /** Быстрый мах вправо — 30 px за 8 мс условного времени: после отпускания остаётся инерция сдвига. */
    fun swipe() {
      scene.sendPointerEvent(PointerEventType.Press, Offset(100f, 150f), timeMillis = clock / 1_000_000, buttons = held, button = PointerButton.Primary)
      for (i in 1..6) {
        clock += 8_000_000L
        scene.sendPointerEvent(PointerEventType.Move, Offset(100f + i * 30f, 150f), timeMillis = clock / 1_000_000, buttons = held)
        scene.render(clock)
      }
      scene.sendPointerEvent(
        PointerEventType.Release, Offset(280f, 150f), timeMillis = clock / 1_000_000, buttons = PointerButtons(), button = PointerButton.Primary,
      )
      scene.render(clock)
    }

    /** [count] кадров с условным временем по 16 мс: инерция и переход по команде идут детерминированно, без сна. */
    fun frames(count: Int) = repeat(count) {
      clock += FRAME_NANOS
      scene.render(clock)
    }
  }

  @Test
  fun commandReplacesInertia() {
    val h = Harness()
    try {
      val home = h.controller.pose
      h.swipe()
      h.frames(3)
      assertTrue(h.controller.motionActive, "после маха должна идти инерция")
      h.command = CameraCommand.Home
      // переход 450 мс — около 30 кадров, с запасом
      h.frames(60)
      val pose = h.controller.pose
      assertEquals(home.pivotX, pose.pivotX, 1e-3f)
      assertEquals(home.pivotZ, pose.pivotZ, 1e-3f)
      assertEquals(home.distance, pose.distance, 1e-3f)
      assertTrue(!h.controller.motionActive)
    } finally {
      h.scene.close()
    }
  }

  /** Нетронутая камера вписывается в новый размер кадра; после жеста поза остаётся, а «Исходный вид» берёт новый дом. */
  @Test
  fun refitHomeFollowsUntouchedViewOnly() {
    val h = Harness()
    try {
      val home = h.controller.pose
      val wider = home.copy(distance = home.distance * 2)
      h.refit(wider)
      h.frames(60)
      assertEquals(wider.distance, h.controller.pose.distance, 1e-3f)

      h.swipe()
      h.frames(400)
      val touched = h.controller.pose
      h.refit(home)
      h.frames(60)
      assertEquals(touched, h.controller.pose)

      h.command = CameraCommand.Home
      h.frames(60)
      assertEquals(home.distance, h.controller.pose.distance, 1e-3f)
    } finally {
      h.scene.close()
    }
  }

  @Test
  fun commandDuringTransitionContinuesWithoutStop() {
    val h = Harness()
    try {
      val home = h.controller.pose
      h.command = CameraCommand.FlyTo(home.copy(pivotX = home.pivotX + 400f))
      h.frames(12)
      assertTrue(h.controller.motionActive, "переход ещё идёт")
      assertTrue(h.controller.pose.pivotX > home.pivotX + 50f, "переход уже сдвинул камеру")
      // вторая команда посреди перехода: кадр на запуск нового движения, дальше камера не замирает, а сразу едет к цели
      h.command = CameraCommand.FlyTo(home.copy(pivotX = home.pivotX - 400f))
      h.frames(1)
      val at = h.controller.pose
      h.frames(3)
      val moved = at.pivotX - h.controller.pose.pivotX
      val path = at.pivotX - (home.pivotX - 400f)
      // с ходу (LinearOutSlowIn) за два-три кадра проходится 20–30 % пути, с разгона (FastOutSlowIn) — меньше 3 %
      assertTrue(moved > path * 0.1f, "за первые кадры пройдено ${moved / path} пути")
      h.frames(40)
      assertEquals(home.pivotX - 400f, h.controller.pose.pivotX, 1e-3f)
      assertTrue(!h.controller.motionActive)
    } finally {
      h.scene.close()
    }
  }
}
