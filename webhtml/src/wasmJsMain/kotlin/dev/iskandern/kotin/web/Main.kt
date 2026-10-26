package dev.iskandern.kotin.web

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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

// index.html?file=путь/к.dxf или index.html#путь/к.dxf — открыть файл с того же сервера; смена #… открывает другой
fun main() {
  applyDesignTokens()
  Filament.initJs { filamentReady = true }
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
