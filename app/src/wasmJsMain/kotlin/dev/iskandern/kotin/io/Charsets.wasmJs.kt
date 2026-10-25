package dev.iskandern.kotin.io

// TextDecoder браузера знает windows-125x, Shift_JIS, GBK, EUC-KR, Big5 и т. п.
@JsFun("(name) => { try { return new TextDecoder(name); } catch (e) { return null; } }")
private external fun createTextDecoder(name: String): JsAny?

@JsFun("(size) => new Uint8Array(size)")
private external fun newUint8Array(size: Int): JsAny

@JsFun("(array, index, value) => { array[index] = value; }")
private external fun setByte(array: JsAny, index: Int, value: Int)

@JsFun("(decoder, array) => decoder.decode(array)")
private external fun decode(decoder: JsAny, array: JsAny): String

internal actual fun codePageDecoder(name: String): ((bytes: ByteArray, start: Int, end: Int) -> String)? {
  val decoder = createTextDecoder(name) ?: return null
  return { bytes, start, end ->
    val array = newUint8Array(end - start)
    for (i in start until end) setByte(array, i - start, bytes[i].toInt() and 0xFF)
    decode(decoder, array)
  }
}
