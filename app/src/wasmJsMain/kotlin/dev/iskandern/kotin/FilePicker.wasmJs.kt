package dev.iskandern.kotin

import org.jetbrains.compose.resources.StringResource
import dev.iskandern.kotin.resources.Res
import dev.iskandern.kotin.resources.open_hint_web
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.await
import dev.iskandern.kotin.io.JsBytesInput
import kotlin.js.Promise

// input держим в DOM, пока открыт диалог: непривязанный элемент браузер может собрать GC до выбора файла,
// и тогда onchange не сработает (файл «не открывается»)
@JsFun(
  """(onPicked) => {
    const input = document.createElement('input');
    input.type = 'file';
    input.accept = '.dxf,.DXF,.xml,.XML';
    input.style.display = 'none';
    const cleanup = () => input.remove();
    input.onchange = () => { const file = input.files[0]; cleanup(); if (file) onPicked(file); };
    input.oncancel = cleanup;
    document.body.appendChild(input);
    input.click();
  }""",
)
private external fun showFileDialog(onPicked: (JsAny) -> Unit)

@JsFun("(file) => file.name")
private external fun fileName(file: JsAny): String

@JsFun("(file) => file.size")
private external fun fileSize(file: JsAny): Double

@JsFun("(file) => file.arrayBuffer().then((buffer) => new Int8Array(buffer))")
private external fun fileBytes(file: JsAny): Promise<JsAny>

@JsFun("(url) => fetch(url).then((r) => { if (!r.ok) throw new Error(r.status + ' ' + url); return r.arrayBuffer(); }).then((b) => new Int8Array(b))")
private external fun urlBytes(url: String): Promise<JsAny>

@Composable
actual fun rememberFilePicker(onPicked: (DrawingSource) -> Unit): () -> Unit {
  val callback by rememberUpdatedState(onPicked)
  return remember {
    {
      showFileDialog { file ->
        callback(DrawingSource(fileName(file), fileSize(file).toLong()) { JsBytesInput(fileBytes(file).await()) })
      }
    }
  }
}

/** Файл по ссылке (для `index.html?file=…`): скачивается целиком при открытии. */
fun urlDrawingSource(url: String) = DrawingSource(url.substringAfterLast('/'), null) { JsBytesInput(urlBytes(url).await()) }

actual val openFileHint: StringResource = Res.string.open_hint_web
