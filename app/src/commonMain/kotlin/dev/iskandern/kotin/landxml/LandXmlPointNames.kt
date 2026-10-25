package dev.iskandern.kotin.landxml

import dev.iskandern.kotin.model.GrowingDoubleArray
import dev.iskandern.kotin.model.GrowingIntArray

/**
 * Таблица «имя `CgPoint` → координаты сцены» для ссылок (`pntRef`, `Chain`, `refPnt`). Заполняет раздел точек,
 * ищут разделы со ссылками — в [LandXmlSceneBuilder.finish] (стадия ссылок): точки могут стоять в файле после ссылок.
 *
 * Имена лежат одним пулом байтов (UTF-8) со смещениями, строк на точку нет. Индекс «имя → точка» строится при первом
 * поиске и перестраивается, только если после него добавили точки.
 */
internal class LandXmlPointNames {
  private var pool = ByteArray(256)
  private var poolSize = 0
  // начало имени каждой точки в пуле; конец — начало следующей (у последней — poolSize)
  private val starts = GrowingIntArray()
  private val coords = GrowingDoubleArray()
  private var table = IntArray(0)
  private var indexed = -1

  /** Точек добавлено (повторы имён считаются отдельно). */
  val size: Int get() = starts.size

  /** Точка [name] в осях сцены; при повторе имени поиск вернёт последнюю. */
  fun add(name: String, x: Double, y: Double, z: Double) {
    val bytes = name.encodeToByteArray()
    add(bytes, 0, bytes.size, x, y, z)
  }

  /** То же, имя — диапазон байтов UTF-8. */
  fun add(buf: ByteArray, start: Int, end: Int, x: Double, y: Double, z: Double) {
    val length = end - start
    if (poolSize + length > pool.size) pool = pool.copyOf(maxOf(pool.size * 2, poolSize + length))
    buf.copyInto(pool, poolSize, start, end)
    starts.add(poolSize)
    poolSize += length
    coords.add(x)
    coords.add(y)
    coords.add(z)
  }

  /** Координаты точки [name] в [out] с [at]: x, y, z; false — такой точки нет. */
  fun find(name: String, out: DoubleArray, at: Int = 0): Boolean {
    val bytes = name.encodeToByteArray()
    return find(bytes, 0, bytes.size, out, at)
  }

  /** То же, имя — диапазон байтов UTF-8. */
  fun find(buf: ByteArray, start: Int, end: Int, out: DoubleArray, at: Int = 0): Boolean {
    if (indexed != starts.size) buildIndex()
    if (table.isEmpty()) return false
    val mask = table.size - 1
    var slot = hash(buf, start, end) and mask
    while (true) {
      val entry = table[slot]
      if (entry == 0) return false
      if (sameName(entry - 1, buf, start, end)) {
        val i = (entry - 1) * 3
        out[at] = coords[i]
        out[at + 1] = coords[i + 1]
        out[at + 2] = coords[i + 2]
        return true
      }
      slot = (slot + 1) and mask
    }
  }

  private fun buildIndex() {
    val n = starts.size
    indexed = n
    if (n == 0) {
      table = IntArray(0)
      return
    }
    var capacity = 16
    while (capacity < n * 2) capacity *= 2
    table = IntArray(capacity)
    val mask = capacity - 1
    for (e in 0 until n) {
      val from = starts[e]
      val to = endOf(e)
      var slot = hash(pool, from, to) and mask
      while (true) {
        val entry = table[slot]
        // повтор имени: позже в файле — главнее
        if (entry == 0 || sameName(entry - 1, pool, from, to)) {
          table[slot] = e + 1
          break
        }
        slot = (slot + 1) and mask
      }
    }
  }

  private fun endOf(entry: Int): Int = if (entry + 1 < starts.size) starts[entry + 1] else poolSize

  private fun sameName(entry: Int, buf: ByteArray, start: Int, end: Int): Boolean {
    val from = starts[entry]
    val to = endOf(entry)
    if (to - from != end - start) return false
    for (i in 0 until to - from) if (pool[from + i] != buf[start + i]) return false
    return true
  }

  private fun hash(buf: ByteArray, start: Int, end: Int): Int {
    var h = -0x7ee3623b
    for (i in start until end) h = (h xor buf[i].toInt()) * 0x01000193
    return h xor (h ushr 15)
  }
}
