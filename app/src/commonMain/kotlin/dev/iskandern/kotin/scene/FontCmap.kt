package dev.iskandern.kotin.scene

/**
 * Символы BMP, для которых в шрифте TrueType/OpenType [bytes] есть глиф: по таблице cmap (формат 4 или 12, Unicode).
 * Нужно, чтобы знаки, которых нет в замене шрифта (⌀ и т. п.), рисовать шрифтом интерфейса. Не разобралось — пусто.
 */
fun fontCharacters(bytes: ByteArray): Set<Char> {
  fun u16(at: Int) = ((bytes[at].toInt() and 0xFF) shl 8) or (bytes[at + 1].toInt() and 0xFF)
  fun u32(at: Int) = (u16(at).toLong() shl 16) or u16(at + 2).toLong()
  val result = HashSet<Char>()
  try {
    val numTables = u16(4)
    var cmap = -1
    for (i in 0 until numTables) {
      val record = 12 + i * 16
      if (bytes[record].toInt() == 'c'.code && bytes[record + 1].toInt() == 'm'.code &&
        bytes[record + 2].toInt() == 'a'.code && bytes[record + 3].toInt() == 'p'.code
      ) cmap = u32(record + 8).toInt()
    }
    if (cmap < 0) return result
    // подтаблица Unicode: Windows (3, 1 / 3, 10) или Unicode (0, *)
    var best = -1
    var bestFormat = 0
    for (i in 0 until u16(cmap + 2)) {
      val record = cmap + 4 + i * 8
      val platform = u16(record)
      val encoding = u16(record + 2)
      if (platform != 0 && !(platform == 3 && (encoding == 1 || encoding == 10))) continue
      val offset = cmap + u32(record + 4).toInt()
      val format = u16(offset)
      // формат 12 полнее формата 4
      if (format == 4 && bestFormat == 0 || format == 12 && bestFormat != 12) {
        best = offset
        bestFormat = format
      }
    }
    when (bestFormat) {
      4 -> {
        val segments = u16(best + 6) / 2
        val ends = best + 14
        val starts = ends + segments * 2 + 2
        val deltas = starts + segments * 2
        val rangeOffsets = deltas + segments * 2
        for (s in 0 until segments) {
          val start = u16(starts + s * 2)
          val end = u16(ends + s * 2)
          val delta = u16(deltas + s * 2)
          val rangeOffset = u16(rangeOffsets + s * 2)
          for (c in start..minOf(end, 0xFFFE)) {
            val glyph = if (rangeOffset == 0) (c + delta) and 0xFFFF else {
              val at = rangeOffsets + s * 2 + rangeOffset + (c - start) * 2
              val g = u16(at)
              if (g == 0) 0 else (g + delta) and 0xFFFF
            }
            if (glyph != 0) result.add(c.toChar())
          }
        }
      }
      12 -> {
        val groups = u32(best + 12).toInt()
        for (g in 0 until groups) {
          val group = best + 16 + g * 12
          val start = u32(group)
          val end = minOf(u32(group + 4), 0xFFFFL)
          val glyph = u32(group + 8)
          for (c in start..end) if (glyph + (c - start) != 0L) result.add(c.toInt().toChar())
        }
      }
    }
  } catch (_: IndexOutOfBoundsException) {
    result.clear()
  }
  return result
}
