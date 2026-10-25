package dev.iskandern.kotin.landxml

import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream

/**
 * Тот же рельеф, что [SyntheticLandXml], как 3DFACE в ASCII DXF — эталон скорости: LandXML должен читаться не медленнее.
 * Координаты — с 8 знаками после точки, как обычно пишут DXF (быстрый путь [dev.iskandern.kotin.io.ByteNumbers]).
 */
class DxfBaseline(private val tin: SyntheticLandXml) {

  fun write(out: OutputStream) {
    val w = out.bufferedWriter(Charsets.US_ASCII)
    w.write("  0\nSECTION\n  2\nENTITIES\n")
    val sb = StringBuilder(512)
    tin.forEachFace { _, a, b, c ->
      sb.setLength(0)
      sb.append("  0\n3DFACE\n  8\nTIN\n")
      vertex(sb, 0, a)
      vertex(sb, 1, b)
      vertex(sb, 2, c)
      vertex(sb, 3, c)
      w.append(sb)
    }
    w.write("  0\nENDSEC\n  0\nEOF\n")
    w.flush()
  }

  fun bytes(): ByteArray = ByteArrayOutputStream().also { write(it) }.toByteArray()

  fun writeTo(file: File) = BufferedOutputStream(file.outputStream(), 1 shl 16).use { write(it) }

  // X — восток, Y — север, Z — отметка
  private fun vertex(sb: StringBuilder, k: Int, index: Int) {
    sb.append(' ').append(10 + k).append('\n')
    appendShort(sb, tin.east(index)).append('\n')
    sb.append(' ').append(20 + k).append('\n')
    appendShort(sb, tin.north(index)).append('\n')
    sb.append(' ').append(30 + k).append('\n')
    appendShort(sb, tin.elevation(index)).append('\n')
  }

  private fun appendShort(sb: StringBuilder, micro: Long): StringBuilder {
    val units = micro / 10_000L
    sb.append(units / 100_000_000L).append('.')
    val frac = (units % 100_000_000L).toString()
    for (k in frac.length until 8) sb.append('0')
    return sb.append(frac)
  }
}
