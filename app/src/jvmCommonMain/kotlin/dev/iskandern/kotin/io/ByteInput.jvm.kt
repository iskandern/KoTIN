package dev.iskandern.kotin.io

import java.io.InputStream

fun InputStream.asByteInput(): ByteInput = object : ByteInput {
  override fun read(buffer: ByteArray, offset: Int, length: Int): Int = this@asByteInput.read(buffer, offset, length)

  override fun close() = this@asByteInput.close()
}
