package dev.iskandern.kotin

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import dev.iskandern.kotin.resources.Res
import dev.iskandern.kotin.resources.open_file
import io.github.erkko68.filament.compose.FilamentApp
import org.jetbrains.compose.resources.stringResource

@JsFun("() => new URLSearchParams(window.location.search).get('file')")
private external fun fileParameter(): String?

@JsFun("() => window.location.hash.length > 1 ? decodeURIComponent(window.location.hash.substring(1)) : null")
private external fun hashFile(): String?

@JsFun("(callback) => { const listener = () => callback(); window.addEventListener('hashchange', listener); return listener; }")
private external fun addHashListener(callback: () -> Unit): JsAny

@JsFun("(listener) => window.removeEventListener('hashchange', listener)")
private external fun removeHashListener(listener: JsAny)

@JsFun("() => window.kotinHidePreview?.()")
private external fun hidePreview()

// index.html?file=путь/к.dxf или index.html#путь/к.dxf — открыть файл с того же сервера; смена #… открывает другой
fun main() = FilamentApp {
  var url by remember { mutableStateOf(hashFile() ?: fileParameter()) }
  DisposableEffect(Unit) {
    val listener = addHashListener { url = hashFile() }
    onDispose { removeHashListener(listener) }
  }
  val source = remember(url) { url?.let(::urlDrawingSource) }
  ViewerScreen(source)
  HidePreviewWhenReady()
}

// заставка из index.html уходит, когда интерфейс нарисован целиком: шрифты (до них KotinTheme пуст) и строки
// (до них тексты пустые) загружены, и кадр с ними показан
@Composable
private fun HidePreviewWhenReady() {
  val fonts = rememberFontFamily(GOLOS_FONTS) != null && rememberFontFamily(MONO_FONTS) != null
  val strings = stringResource(Res.string.open_file).isNotEmpty()
  if (fonts && strings) LaunchedEffect(Unit) {
    withFrameNanos { }
    withFrameNanos { }
    hidePreview()
  }
}
