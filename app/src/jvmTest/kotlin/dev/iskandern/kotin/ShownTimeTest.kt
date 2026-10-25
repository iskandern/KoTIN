package dev.iskandern.kotin

import dev.iskandern.kotin.scene.buildRenderScene
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class ShownTimeTest {

  private fun loaded(name: String): LoadState.Loaded {
    val geometry = readGeometry("0", "SECTION", "2", "ENTITIES", "0", "ENDSEC", "0", "EOF")
    return LoadState.Loaded(name, sizeBytes = null, geometry, buildRenderScene(geometry), parseMillis = 1, buildMillis = 1)
  }

  @Test
  fun `первый кадр показанной сцены записывает время`() {
    val shown = loaded("a.dxf")
    val state = ViewerUiState(loadState = shown, shownScene = shown).shownAfter(shown.scene, 120)
    assertEquals(120L, state.showMillis)
  }

  @Test
  fun `повторный кадр время не перезаписывает`() {
    val shown = loaded("a.dxf")
    val first = ViewerUiState(shownScene = shown).shownAfter(shown.scene, 120)
    assertSame(first, first.shownAfter(shown.scene, 500))
  }

  @Test
  fun `кадр прежней сцены после смены не засчитывается новой`() {
    val old = loaded("a.dxf")
    val current = loaded("b.dxf")
    val state = ViewerUiState(shownScene = current).shownAfter(old.scene, 90)
    assertNull(state.showMillis)
  }

  @Test
  fun `без показанной сцены состояние не меняется`() {
    val state = ViewerUiState()
    assertSame(state, state.shownAfter(loaded("a.dxf").scene, 10))
  }
}
