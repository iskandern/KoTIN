package dev.iskandern.kotin.android

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.iskandern.kotin.DrawingSource
import dev.iskandern.kotin.ViewerScreen
import dev.iskandern.kotin.drawingSource

/** Ключ последнего файла из intent: после пересоздания getIntent() отдаёт первый intent, а не пришедший в onNewIntent. */
private const val KEY_URI = "view_uri"

/** Открывается и из лаунчера, и по VIEW-intent на файл DXF или LandXML (файловый менеджер, adb shell am start -a … -d …). */
class MainActivity : ComponentActivity() {
  private var uri: Uri? = null
  private var source by mutableStateOf<DrawingSource?>(null)

  override fun onCreate(savedInstanceState: Bundle?) {
    enableEdgeToEdge()
    super.onCreate(savedInstanceState)
    show(if (savedInstanceState != null) savedInstanceState.getString(KEY_URI)?.let(Uri::parse) else intent.viewUri())
    setContent { ViewerScreen(initialSource = source) }
  }

  // singleTask: следующий файл приходит сюда, экран и движок остаются
  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    intent.viewUri()?.let(::show)
  }

  override fun onSaveInstanceState(outState: Bundle) {
    super.onSaveInstanceState(outState)
    outState.putString(KEY_URI, uri?.toString())
  }

  private fun show(uri: Uri?) {
    this.uri = uri
    source = uri?.let { applicationContext.drawingSource(it) }
  }
}

private fun Intent?.viewUri(): Uri? = this?.takeIf { it.action == Intent.ACTION_VIEW }?.data
