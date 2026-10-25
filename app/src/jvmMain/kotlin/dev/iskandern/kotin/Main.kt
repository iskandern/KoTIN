package dev.iskandern.kotin

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import org.jetbrains.compose.resources.painterResource
import dev.iskandern.kotin.resources.Res
import dev.iskandern.kotin.resources.kotin_icon
import java.io.File

/** Путь к файлу (DXF, LandXML) можно передать первым аргументом — файл откроется сразу. */
fun main(args: Array<String>) {
  val file = args.firstOrNull()?.let(::File)
  val initial = if (file != null && file.isFile) file.toDrawingSource() else null
  application {
    // значок окна и панели задач; в Dock macOS без упаковки остаётся значок Java
    Window(
      onCloseRequest = ::exitApplication,
      title = APP_NAME,
      icon = painterResource(Res.drawable.kotin_icon),
      state = rememberWindowState(size = DpSize(1400.dp, 900.dp)),
    ) {
      ViewerScreen(initialSource = initial)
    }
  }
}
