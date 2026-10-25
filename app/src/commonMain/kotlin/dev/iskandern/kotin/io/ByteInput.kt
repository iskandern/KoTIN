package dev.iskandern.kotin.io

/**
 * Источник байтов файла — вместо java.io.InputStream, которого нет на web.
 * [read] возвращает число прочитанных байтов или -1 в конце.
 */
interface ByteInput : AutoCloseable {
  fun read(buffer: ByteArray, offset: Int, length: Int): Int

  override fun close() {}
}

class ByteArrayInput(private val bytes: ByteArray, private var position: Int = 0) : ByteInput {
  override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
    if (position >= bytes.size) return -1
    val n = minOf(length, bytes.size - position)
    bytes.copyInto(buffer, offset, position, position + n)
    position += n
    return n
  }
}

/** Сначала отдаёт уже прочитанное начало файла ([head] c [from] до [until]), потом остальное из [rest]. */
internal class PrefixedInput(
  private val head: ByteArray,
  private var from: Int,
  private val until: Int,
  private val rest: ByteInput,
) : ByteInput {
  override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
    if (from >= until) return rest.read(buffer, offset, length)
    val n = minOf(length, until - from)
    head.copyInto(buffer, offset, from, from + n)
    from += n
    return n
  }

  override fun close() = rest.close()
}

internal fun ByteInput.readAllBytes(): ByteArray {
  var result = ByteArray(64 * 1024)
  var size = 0
  while (true) {
    if (size == result.size) result = result.copyOf(size * 2)
    val n = read(result, size, result.size - size)
    if (n < 0) return result.copyOf(size)
    size += n
  }
}
