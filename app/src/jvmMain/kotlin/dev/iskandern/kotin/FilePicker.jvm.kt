package dev.iskandern.kotin

import org.jetbrains.compose.resources.StringResource
import dev.iskandern.kotin.resources.open_hint_desktop
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import org.jetbrains.compose.resources.stringResource
import dev.iskandern.kotin.io.asByteInput
import dev.iskandern.kotin.resources.Res
import dev.iskandern.kotin.resources.open_file
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

fun File.toDrawingSource() = DrawingSource(name = name, sizeBytes = length(), open = { inputStream().asByteInput() })

@Composable
actual fun rememberFilePicker(onPicked: (DrawingSource) -> Unit): () -> Unit {
  val callback by rememberUpdatedState(onPicked)
  val title = stringResource(Res.string.open_file)
  return remember {
    {
      val dialog = FileDialog(null as Frame?, title, FileDialog.LOAD).apply {
        // .xml — LandXML; формат определяется по содержимому. На Windows FileDialog фильтр не вызывает —
        // там работает маска в имени файла
        setFilenameFilter { _, name -> name.endsWith(".dxf", ignoreCase = true) || name.endsWith(".xml", ignoreCase = true) }
        if (System.getProperty("os.name").startsWith("Windows")) file = "*.dxf;*.xml"
        isVisible = true
      }
      val name = dialog.file
      if (name != null) callback(File(dialog.directory, name).toDrawingSource())
    }
  }
}

actual val openFileHint: StringResource = Res.string.open_hint_desktop
