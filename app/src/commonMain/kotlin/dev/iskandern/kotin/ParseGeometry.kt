package dev.iskandern.kotin

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import dev.iskandern.kotin.io.DrawingFormat
import dev.iskandern.kotin.landxml.StationNotation
import dev.iskandern.kotin.model.SceneGeometry

/**
 * Читает файл (формат — [DrawingFormat]) в геометрию сцены. [onProgress] получает число прочитанных байтов.
 * На web работа идёт в Web Worker (если доступен), на остальных платформах — в вызывающем потоке.
 */
expect suspend fun parseGeometry(source: DrawingSource, onProgress: (bytesRead: Long) -> Unit): SceneGeometry

/** Разбор в текущем потоке — основной путь на desktop/Android и запасной на web. */
internal suspend fun parseGeometryInline(source: DrawingSource, onProgress: (bytesRead: Long) -> Unit): SceneGeometry {
  val context = currentCoroutineContext()
  return DrawingFormat.read(source.open(), source.sizeBytes, stationNotation()) { bytesRead ->
    // последний вызов — в конце чтения, перед сборкой сцены: отмена не ждёт сборки
    context.ensureActive()
    onProgress(bytesRead)
  }
}

/** Запись пикетов LandXML на языке интерфейса — из строк ресурсов ([UiStrings]); до их загрузки — российская. */
internal fun stationNotation(): StationNotation {
  val default = StationNotation()
  return StationNotation(
    UiStrings.string("station_label") ?: default.label,
    UiStrings.string("station_decimal")?.firstOrNull() ?: default.decimal,
  )
}
