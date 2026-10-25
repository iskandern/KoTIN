package dev.iskandern.kotin.io

@JsFun("(array) => array.length")
private external fun arrayLength(array: JsAny): Int

@JsFun("(array, index) => array[index]")
private external fun byteAt(array: JsAny, index: Int): Int

/** Файл уже в памяти браузера (Int8Array); байты копируются в Kotlin порциями по мере чтения. */
class JsBytesInput(val array: JsAny) : ByteInput {
  val size = arrayLength(array)
  private var position = 0

  override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
    if (position >= size) return -1
    val n = minOf(length, size - position)
    for (i in 0 until n) buffer[offset + i] = byteAt(array, position + i).toByte()
    position += n
    return n
  }
}
