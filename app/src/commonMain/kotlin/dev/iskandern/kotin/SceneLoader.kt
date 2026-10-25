package dev.iskandern.kotin

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import dev.iskandern.kotin.model.Journal
import dev.iskandern.kotin.model.SceneGeometry
import dev.iskandern.kotin.model.UiText
import dev.iskandern.kotin.model.swappedXY
import dev.iskandern.kotin.scene.RenderScene
import dev.iskandern.kotin.scene.buildRenderScene
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.measureTimedValue

sealed interface LoadState {
  data object Idle : LoadState

  /** [progress] 0..1, null — размер файла неизвестен. */
  data class Loading(val name: String, val progress: Float?, val bytesRead: Long) : LoadState

  class Loaded(
    val name: String,
    val sizeBytes: Long?,
    /** Разобранная геометрия — чтобы пересобрать сцену с другими настройками без повторного чтения. */
    val geometry: SceneGeometry,
    val scene: RenderScene,
    val parseMillis: Long,
    val buildMillis: Long,
    /** Показанный лист — индекс в [SceneGeometry.sheets]; -1 — модель. */
    val sheet: Int = MODEL_SHEET,
    /** Оси X и Y модели поменяны местами (геодезический «север восток»); [geometry] остаётся исходной. */
    val swapXY: Boolean = false,
  ) : LoadState

  data class Failed(val name: String, val message: String) : LoadState
}

/** Читает файл и готовит сцену в фоне. [onProgress] вызывается из фонового потока. */
suspend fun loadScene(source: DrawingSource, onProgress: (LoadState.Loading) -> Unit): LoadState =
  withContext(Dispatchers.Default) {
    UiStrings.load()
    try {
      val (geometry, parseTime) = measureTimedValue {
        parseGeometry(source) { bytesRead ->
          val size = source.sizeBytes
          val progress = if (size != null && size > 0) (bytesRead.toFloat() / size).coerceAtMost(1f) else null
          onProgress(LoadState.Loading(source.name, progress, bytesRead))
        }
      }
      ensureActive()
      val (scene, buildTime) = measureTimedValue { buildRenderScene(geometry) }
      Journal.log(
        UiText.key(
          if (scene.truncated) "log_loaded_truncated" else "log_loaded",
          source.name, parseTime.inWholeMilliseconds, buildTime.inWholeMilliseconds, scene.surfaces.sumOf { it.triangleCount },
          scene.lines.sumOf { it.lineCount }, scene.labels.size, scene.points.size,
        )
      )
      LoadState.Loaded(source.name, source.sizeBytes, geometry, scene, parseTime.inWholeMilliseconds, buildTime.inWholeMilliseconds)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Throwable) {
      Journal.log(UiText.key("log_failed", source.name, e::class.simpleName.orEmpty(), UiStrings.format(e.message.orEmpty())))
      // OutOfMemoryError тоже показываем, а не роняем приложение; сообщение — ключ UiText, экран переводит его при показе
      LoadState.Failed(source.name, e.message ?: e::class.simpleName.orEmpty())
    }
  }

/** Индекс «листа» модели в [LoadState.Loaded.sheet]. */
const val MODEL_SHEET = -1

/**
 * Пересобирает сцену из уже разобранной геометрии: с чертежом в плоскость или без ([flattenDrawing], null — как решит
 * сцена) и для модели или листа [sheet]. [swapXY] — поменять оси X и Y модели; лист — бумага, его оси не меняются.
 */
suspend fun rebuildScene(
  loaded: LoadState.Loaded,
  flattenDrawing: Boolean?,
  sheet: Int = loaded.sheet,
  swapXY: Boolean = loaded.swapXY,
): LoadState.Loaded =
  withContext(Dispatchers.Default) {
    // модель в окна листа переносится здесь, при выборе листа: открытие файла от этого не медленнее
    val sheetGeometry = loaded.geometry.sheets.getOrNull(sheet)?.withModel(loaded.geometry)
    // лист — бумага: отметки у его сущностей случайны, по умолчанию он кладётся в плоскость и виден строго сверху
    val scene = if (sheetGeometry != null) {
      buildRenderScene(sheetGeometry, flattenDrawing ?: true)
    } else {
      buildRenderScene(if (swapXY) loaded.geometry.swappedXY() else loaded.geometry, flattenDrawing)
    }
    LoadState.Loaded(loaded.name, loaded.sizeBytes, loaded.geometry, scene, loaded.parseMillis, loaded.buildMillis, sheet, swapXY)
  }
