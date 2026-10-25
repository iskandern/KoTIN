package dev.iskandern.kotin

import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CameraUiStateTest {

  private val pose = CameraPose(1f, 2f, 3f, 40f, 0.5f, 0.7f)

  @Test
  fun `команда ждёт исполнения и снимается подтверждением`() {
    val show = CameraCommand.ShowTop(10f, 20f, 100f)
    val sent = CameraUiState().send(show)
    assertEquals(show, sent.command)
    assertNull(sent.consumed(show).command)
  }

  @Test
  fun `подтверждение чужой команды новую не снимает`() {
    val sent = CameraUiState().send(CameraCommand.North)
    assertEquals(CameraCommand.North, sent.consumed(CameraCommand.Home).command)
  }

  @Test
  fun `новая сцена сбрасывает команду, но не схему управления`() {
    val state = CameraUiState(CameraMode.ORBIT, CameraCommand.Home).forNewScene()
    assertNull(state.command)
    assertEquals(CameraMode.ORBIT, state.mode)
  }

  @Test
  fun `у каждого вида файла своя поза`() {
    val file = Any()
    val memory = CameraPoseMemory().also { it.reset(file) }
    val model = CameraViewKey(sheet = -1, swapXY = false, flattened = false)
    val sheet = model.copy(sheet = 0)
    val other = pose.copy(yaw = 1.5f)
    memory.remember(file, model, pose)
    memory.remember(file, sheet, other)
    assertEquals(pose, memory.recall(model))
    assertEquals(other, memory.recall(sheet))
    // поза с другими осями или без «в плоскость» — другие координаты, чужая поза не подходит
    assertNull(memory.recall(model.copy(swapXY = true)))
    assertNull(memory.recall(model.copy(flattened = true)))
  }

  @Test
  fun `новый файл забывает позы, а запись закрывающегося экрана прежнего отбрасывается`() {
    val old = Any()
    val new = Any()
    val view = CameraViewKey(-1, swapXY = false, flattened = false)
    val memory = CameraPoseMemory()
    memory.reset(old)
    memory.remember(old, view, pose)
    memory.reset(new)
    assertNull(memory.recall(view))
    // экран прежнего файла закрывается уже после открытия нового и сбрасывает свою позу
    memory.remember(old, view, pose)
    assertNull(memory.recall(view))
    memory.remember(new, view, pose.copy(distance = 5f))
    assertEquals(5f, memory.recall(view)?.distance)
  }

  @Test
  fun `поза по положению глаза и pivot`() {
    fun near(a: Float, b: Float) = abs(a - b) < 1e-5f
    // глаз строго над pivot: сверху, азимут 0
    val top = CameraPose.lookingAt(0f, 0f, 0f, 0f, 50f, 0f)
    assertTrue(near(top.distance, 50f))
    assertTrue(near(top.pitch, (PI / 2).toFloat()))
    // глаз южнее pivot (+Z) на одном уровне: вид сбоку, азимут 0, угол места 0
    val side = CameraPose.lookingAt(1f, 2f, 3f, 1f, 2f, 13f)
    assertTrue(near(side.distance, 10f))
    assertTrue(near(side.yaw, 0f))
    assertTrue(near(side.pitch, 0f))
    // глаз восточнее pivot (+X): азимут 90°
    assertTrue(near(CameraPose.lookingAt(0f, 0f, 0f, 5f, 0f, 0f).yaw, (PI / 2).toFloat()))
  }
}
