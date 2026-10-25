package dev.iskandern.kotin

import org.jetbrains.compose.resources.StringResource
import dev.iskandern.kotin.resources.Res
import dev.iskandern.kotin.resources.open_hint_android
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import dev.iskandern.kotin.io.asByteInput
import dev.iskandern.kotin.model.UiText

@Composable
actual fun rememberFilePicker(onPicked: (DrawingSource) -> Unit): () -> Unit {
  val context = LocalContext.current.applicationContext
  val callback by rememberUpdatedState(onPicked)
  // MIME-тип DXF у провайдеров разный, часто его нет вовсе (application/octet-stream) — фильтр по типам, а не
  // по расширению: пропускает свои и неизвестные, а архивы, PDF, картинки и DWG прячет
  val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
    if (uri != null) callback(context.drawingSource(uri))
  }
  return { launcher.launch(DRAWING_MIME_TYPES) }
}

private val DRAWING_MIME_TYPES = arrayOf(
  "application/dxf", "application/x-dxf", "image/vnd.dxf", "image/x-dxf", "application/vnd.dxf",
  "application/xml", "text/xml", "application/landxml+xml", "text/*", "application/octet-stream",
)

/** Файл по content:// или file:// (из выбора документа или из VIEW-intent). */
fun Context.drawingSource(uri: Uri): DrawingSource {
  var name = uri.lastPathSegment ?: "file.dxf"
  var size: Long? = null
  contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
    if (!cursor.moveToFirst()) return@use
    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
    if (nameIndex >= 0) name = cursor.getString(nameIndex) ?: name
    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
  }
  // MediaStore на Android 9 (content://media/external/file/876) не отдаёт DISPLAY_NAME — без расширения в имени
  // карточка не знает формат; берём имя из пути (_data). Столбца может не быть у других провайдеров — тогда как есть.
  if ('.' !in name) runCatching {
    contentResolver.query(uri, arrayOf("_data"), null, null, null)?.use { cursor ->
      if (cursor.moveToFirst()) cursor.getString(0)?.substringAfterLast('/')?.takeIf { it.isNotEmpty() }?.let { name = it }
    }
  }
  return DrawingSource(name, size) {
    (contentResolver.openInputStream(uri) ?: error(UiText.key("error_open_uri", uri))).asByteInput()
  }
}

actual val openFileHint: StringResource = Res.string.open_hint_android
