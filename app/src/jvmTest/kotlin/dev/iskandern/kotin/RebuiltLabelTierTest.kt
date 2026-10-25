package dev.iskandern.kotin

import dev.iskandern.kotin.scene.buildRenderScene
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Ступень подписей после пересборки сцены (оси X/Y, «в плоскость», лист). */
class RebuiltLabelTierTest {

  // сцена из [count] подписей по десять символов
  private fun loaded(count: Int): LoadState.Loaded {
    val texts = (0 until count).flatMap { listOf("0", "TEXT", "8", "0", "10", "$it", "20", "0", "30", "0", "40", "1", "1", "abcdefghij") }
    val geometry = readGeometry("0", "SECTION", "2", "ENTITIES", *texts.toTypedArray(), "0", "ENDSEC", "0", "EOF")
    return LoadState.Loaded("a.dxf", sizeBytes = null, geometry, buildRenderScene(geometry), parseMillis = 1, buildMillis = 1)
  }

  // большой вид: одна подпись — все; маленький: тысяча подписей — выключены
  private val big = Viewport(4000, 4000, 1f)
  private val tiny = Viewport(100, 100, 3f)

  private fun state(tier: LabelTier, auto: AutoLabels?) =
    ViewerUiState(shownScene = loaded(1), settings = ViewSettings().withLabelTier(tier), autoLabels = auto)

  @Test
  fun `лёгкая сцена после пересборки — подписи снова все`() {
    val before = state(LabelTier.OFF, AutoLabels(LabelTier.OFF, 1000, serial = 1))
    val after = before.withRebuiltScene(loaded(1), before.settings, big, slow = false, manual = false) { 2 }
    assertEquals(LabelTier.ALL, after.settings.shownLabelTier)
    assertNull(after.autoLabels)
    assertTrue(after.frameCheck)
  }

  @Test
  fun `тяжёлая сцена после пересборки — подписи выключаются, плашка новая`() {
    val before = state(LabelTier.ALL, null)
    val after = before.withRebuiltScene(loaded(1000), before.settings, tiny, slow = false, manual = false) { 7 }
    assertEquals(LabelTier.OFF, after.settings.shownLabelTier)
    assertEquals(LabelTier.OFF, after.autoLabels?.tier)
    assertEquals(1000, after.autoLabels?.labels)
    assertEquals(7, after.autoLabels?.serial)
    assertFalse(after.frameCheck)
  }

  @Test
  fun `ступень та же — плашка не показывается снова`() {
    val before = state(LabelTier.OFF, AutoLabels(LabelTier.OFF, 1000, serial = 3))
    val after = before.withRebuiltScene(loaded(900), before.settings, tiny, slow = false, manual = false) { error("новая плашка") }
    assertEquals(3, after.autoLabels?.serial)
    assertEquals(900, after.autoLabels?.labels)
  }

  @Test
  fun `подписи переключены вручную — пересборка их не трогает`() {
    val before = state(LabelTier.OFF, null)
    val rebuilt = loaded(1)
    val after = before.withRebuiltScene(rebuilt, before.settings, big, slow = false, manual = true) { error("новая плашка") }
    assertEquals(LabelTier.OFF, after.settings.shownLabelTier)
    assertNull(after.autoLabels)
    assertFalse(after.frameCheck)
    assertSame(rebuilt, after.shownScene)
  }

  @Test
  fun `медленное по замеру устройство — на ступень легче`() {
    val before = state(LabelTier.ALL, null)
    val after = before.withRebuiltScene(loaded(1), before.settings, big, slow = true, manual = false) { 4 }
    assertEquals(LabelTier.THIN, after.settings.shownLabelTier)
    assertEquals(4, after.autoLabels?.serial)
    assertFalse(after.frameCheck)
  }

  @Test
  fun `слои пересобранной сцены — из переданных настроек`() {
    val before = state(LabelTier.ALL, null)
    val hidden = before.settings.copy(hiddenLayers = setOf("0"))
    // единственный слой скрыт — нагрузки нет, подписи «все» и замер не нужен
    val after = before.withRebuiltScene(loaded(1000), hidden, tiny, slow = false, manual = false) { 1 }
    assertEquals(setOf("0"), after.settings.hiddenLayers)
    assertEquals(LabelTier.ALL, after.settings.shownLabelTier)
    assertFalse(after.frameCheck)
  }
}
