package dev.iskandern.kotin.drawingworker

import dev.iskandern.kotin.io.DrawingFormat
import dev.iskandern.kotin.io.JsBytesInput
import dev.iskandern.kotin.landxml.StationNotation
import dev.iskandern.kotin.model.Journal
import dev.iskandern.kotin.model.packGeometry

@JsFun("() => Date.now()")
private external fun now(): Double

@JsFun("() => []")
private external fun newArray(): JsAny

@JsFun("(type) => self.postMessage({ type })")
private external fun postSimple(type: String)

@JsFun("(bytes) => self.postMessage({ type: 'progress', bytes })")
private external fun postProgress(bytes: Double)

@JsFun("(message) => self.postMessage({ type: 'log', message })")
private external fun postLog(message: String)

@JsFun("(message) => self.postMessage({ type: 'error', message })")
private external fun postError(message: String)

@JsFun("(packed, transfer) => self.postMessage({ type: 'done', packed }, transfer)")
private external fun postDone(packed: JsAny, transfer: JsAny)

// сообщение от главного потока: { bytes: Int8Array, stationLabel, stationDecimal } — содержимое файла и запись пикетов
@JsFun(
  """(handler) => { self.onmessage = (event) =>
    handler(event.data.bytes, String(event.data.stationLabel ?? ''), String(event.data.stationDecimal ?? '')); }""",
)
private external fun onBytes(handler: (JsAny, String, String) -> Unit)

private fun parse(bytes: JsAny, stationLabel: String, stationDecimal: String) {
  try {
    var last = 0.0
    val progress = { bytesRead: Long ->
      // сообщения о прогрессе — не чаще 20 раз в секунду
      val time = now()
      if (time - last >= 50) {
        last = time
        postProgress(bytesRead.toDouble())
      }
    }
    // байты уже целиком в памяти — размер известен и для ?file=
    val input = JsBytesInput(bytes)
    val default = StationNotation()
    val notation = StationNotation(stationLabel.ifEmpty { default.label }, stationDecimal.firstOrNull() ?: default.decimal)
    val geometry = DrawingFormat.read(input, input.size.toLong(), notation, progress)
    val transfer = newArray()
    val packed = packGeometry(geometry, transfer)
    postDone(packed, transfer)
  } catch (e: Throwable) {
    postError(e.message ?: e::class.simpleName.orEmpty())
  }
}

fun main() {
  // журнал разбора — в главный поток: там есть строки ресурсов для перевода
  Journal.sink = ::postLog
  onBytes(::parse)
  // обработчик установлен: теперь главный поток может присылать файл
  postSimple("ready")
}
