package dev.iskandern.kotin

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.github.erkko68.filament.compose.FilamentApp

@JsFun("() => new URLSearchParams(window.location.search).get('file')")
private external fun fileParameter(): String?

@JsFun("() => window.location.hash.length > 1 ? decodeURIComponent(window.location.hash.substring(1)) : null")
private external fun hashFile(): String?

@JsFun("(callback) => { const listener = () => callback(); window.addEventListener('hashchange', listener); return listener; }")
private external fun addHashListener(callback: () -> Unit): JsAny

@JsFun("(listener) => window.removeEventListener('hashchange', listener)")
private external fun removeHashListener(listener: JsAny)

// index.html?file=путь/к.dxf или index.html#путь/к.dxf — открыть файл с того же сервера; смена #… открывает другой;
// index.html?story=<имя> — история интерфейса (см. Stories)
fun main() {
  val story = storyFromUrl()
  if (story != null) {
    exportComposeLayout()
    FilamentApp {
      val viewModel = remember { ViewerViewModel() }
      LaunchedEffect(viewModel) { runStory(story, viewModel) }
      ViewerScreen(injected = viewModel)
    }
  } else {
    viewerApp()
  }
}

private fun viewerApp() = FilamentApp {
  var url by remember { mutableStateOf(hashFile() ?: fileParameter()) }
  DisposableEffect(Unit) {
    val listener = addHashListener { url = hashFile() }
    onDispose { removeHashListener(listener) }
  }
  val source = remember(url) { url?.let(::urlDrawingSource) }
  ViewerScreen(source)
}
