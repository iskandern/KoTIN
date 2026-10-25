package dev.iskandern.kotin

import kotlinx.coroutines.CompletableDeferred
import dev.iskandern.kotin.model.Journal
import dev.iskandern.kotin.model.SceneGeometry
import dev.iskandern.kotin.io.JsBytesInput
import dev.iskandern.kotin.model.UiText
import dev.iskandern.kotin.model.unpackGeometry

// drawingworker.js (разбор любого формата) лежит рядом с index.html; сообщения: ready, progress{bytes}, log{message}, done{packed}, error{message}
@JsFun(
  """(url, onMessage, onFailure) => {
    const worker = new Worker(url);
    worker.onmessage = (event) => onMessage(event.data.type, event.data);
    worker.onerror = (event) => { event.preventDefault(); onFailure(event.message || '@error_worker'); };
    return worker;
  }""",
)
private external fun startWorker(url: String, onMessage: (String, JsAny) -> Unit, onFailure: (String) -> Unit): JsAny

@JsFun("(worker, bytes, stationLabel, stationDecimal) => worker.postMessage({ bytes, stationLabel, stationDecimal }, [bytes.buffer])")
private external fun sendBytes(worker: JsAny, bytes: JsAny, stationLabel: String, stationDecimal: String)

@JsFun("(worker) => worker.terminate()")
private external fun terminate(worker: JsAny)

@JsFun("(data) => data.bytes")
private external fun messageBytes(data: JsAny): Double

@JsFun("(data) => data.packed")
private external fun messagePacked(data: JsAny): JsAny

@JsFun("(data) => String(data.message)")
private external fun messageText(data: JsAny): String

private class WorkerUnavailable(message: String) : Exception(message)

actual suspend fun parseGeometry(source: DrawingSource, onProgress: (bytesRead: Long) -> Unit): SceneGeometry {
  val bytes = (source.open() as? JsBytesInput)?.array ?: return parseGeometryInline(source, onProgress)
  val packed = try {
    parseInWorker(bytes, onProgress)
  } catch (e: WorkerUnavailable) {
    // Worker не создался или не запустился (старый браузер, нет drawingworker.js) — читаем в главном потоке
    Journal.log(UiText.key("log_worker_unavailable", UiStrings.format(e.message.orEmpty())))
    return parseGeometryInline(source, onProgress)
  }
  return unpackGeometry(packed)
}

private suspend fun parseInWorker(bytes: JsAny, onProgress: (Long) -> Unit): JsAny {
  val result = CompletableDeferred<JsAny>()
  var ready = false
  var worker: JsAny? = null
  val started = try {
    startWorker(
      "drawingworker.js",
      onMessage = { type, data ->
        when (type) {
          "ready" -> {
            ready = true
            // строк ресурсов в Worker нет: запись пикетов — отсюда
            val notation = stationNotation()
            sendBytes(worker!!, bytes, notation.label, notation.decimal.toString())
          }
          "progress" -> onProgress(messageBytes(data).toLong())
          // журнал разбора из Worker — ключи UiText, переводятся и печатаются здесь
          "log" -> Journal.log(messageText(data))
          "done" -> result.complete(messagePacked(data))
          "error" -> result.completeExceptionally(IllegalStateException(messageText(data)))
        }
      },
      onFailure = { message ->
        // до готовности — Worker просто не поднялся (запасной путь), после — сбой разбора
        result.completeExceptionally(if (ready) IllegalStateException(message) else WorkerUnavailable(message))
      },
    )
  } catch (e: Throwable) {
    throw WorkerUnavailable(e.message ?: "@error_worker_create")
  }
  worker = started
  try {
    return result.await()
  } finally {
    terminate(started)
  }
}
