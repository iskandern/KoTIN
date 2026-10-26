package dev.iskandern.kotin.web

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.iskandern.kotin.SETTLE_MS
import dev.iskandern.kotin.ViewerViewModel
import dev.iskandern.kotin.runStory
import dev.iskandern.kotin.storyFromUrl
import dev.iskandern.kotin.storySettled
import dev.iskandern.kotin.urlDrawingSource
import dev.iskandern.kotin.web.dom.renderComposable
import io.github.erkko68.filament.Filament
import kotlinx.browser.document
import org.w3c.dom.HTMLCanvasElement

/** Canvas сцены: в него рисует движок Filament (один WebGL-контекст на всё время работы). */
val sceneCanvas: HTMLCanvasElement by lazy { document.createElement("canvas") as HTMLCanvasElement }

@JsFun("() => new URLSearchParams(window.location.search).get('file')")
private external fun fileParameter(): String?

@JsFun("() => { const h = decodeURIComponent(window.location.hash.slice(1)); return h || null; }")
private external fun hashFile(): String?

@JsFun("(callback) => { const listener = () => callback(); window.addEventListener('hashchange', listener); return listener; }")
private external fun addHashListener(callback: () -> Unit): JsAny

@JsFun("(listener) => window.removeEventListener('hashchange', listener)")
private external fun removeHashListener(listener: JsAny)

// движок Filament грузится параллельно: интерфейс показывается сразу, сцена — когда он готов
private var filamentReady by mutableStateOf(false)

/**
 * `window.kotinLayout()` — рамки элементов с `data-ui` (`{ имя: {x, y, w, h, visible} }`, px CSS) и `window.kotinIdle()` —
 * история готова ([settled]), переходов CSS нет и рамки стоят [SETTLE_MS] (как у Compose-версии, см. exportComposeLayout).
 */
@JsFun("""(settled, settleMs) => {
  const layout = () => {
    const out = {};
    for (const el of document.querySelectorAll('[data-ui]')) {
      const r = el.getBoundingClientRect();
      const cs = getComputedStyle(el);
      out[el.dataset.ui] = {
        x: r.x, y: r.y, w: r.width, h: r.height,
        visible: r.width > 0 && r.height > 0 && cs.visibility !== 'hidden' &&
          r.x < innerWidth && r.y < innerHeight && r.right > 0 && r.bottom > 0,
      };
    }
    return out;
  };
  let previous = '';
  let stableSince = performance.now();
  window.kotinLayout = layout;
  window.kotinIdle = () => {
    const now = JSON.stringify(layout());
    if (now !== previous) { previous = now; stableSince = performance.now(); }
    const running = document.getAnimations().some(a => a.playState === 'running' && a.effect.getTiming().iterations !== Infinity);
    return settled() && !running && document.fonts.status === 'loaded' && performance.now() - stableSince > settleMs;
  };
}""")
private external fun exportDomLayout(settled: () -> Boolean, settleMs: Int)

// index.html?file=путь/к.dxf или index.html#путь/к.dxf — открыть файл с того же сервера; смена #… открывает другой;
// index.html?story=<имя> — история интерфейса (см. Stories)
fun main() {
  applyDesignTokens()
  Filament.initJs { filamentReady = true }
  val story = storyFromUrl()
  if (story != null) {
    exportDomLayout(::storySettled, SETTLE_MS.toInt())
    renderComposable(document.getElementById("root")!!) {
      val viewModel = remember { ViewerViewModel() }
      LaunchedEffect(viewModel) { runStory(story, viewModel) }
      ViewerScreen(null, filamentReady, viewModel)
    }
    return
  }
  renderComposable(document.getElementById("root")!!) {
    var url by remember { mutableStateOf(hashFile() ?: fileParameter()) }
    DisposableEffect(Unit) {
      val listener = addHashListener { hashFile()?.let { url = it } }
      onDispose { removeHashListener(listener) }
    }
    val source = remember(url) { url?.let(::urlDrawingSource) }
    ViewerScreen(source, filamentReady)
  }
}
