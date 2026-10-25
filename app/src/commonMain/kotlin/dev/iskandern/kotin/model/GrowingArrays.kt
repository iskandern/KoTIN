package dev.iskandern.kotin.model

/** Растущий массив int без упаковки. */
internal class GrowingIntArray {
  private var values = IntArray(16)
  var size = 0
    private set

  fun add(value: Int) {
    if (size == values.size) values = values.copyOf(size * 2)
    values[size++] = value
  }

  operator fun get(index: Int): Int = values[index]

  operator fun set(index: Int, value: Int) {
    values[index] = value
  }

  fun toArray(): IntArray = values.copyOf(size)
}

/** Растущий массив double без упаковки. */
internal class GrowingDoubleArray {
  private var values = DoubleArray(16)
  var size = 0
    private set

  fun add(value: Double) {
    if (size == values.size) values = values.copyOf(size * 2)
    values[size++] = value
  }

  fun clear() {
    size = 0
  }

  fun setLast(value: Double) {
    values[size - 1] = value
  }

  /** Новая точка (x, 0, 0); y и z дописываются [setPointCoord]. */
  fun addPoint(x: Double) {
    add(x)
    add(0.0)
    add(0.0)
  }

  fun setPointCoord(axis: Int, value: Double) {
    if (size >= 3) values[size - 3 + axis] = value
  }

  operator fun get(index: Int): Double = values[index]

  operator fun set(index: Int, value: Double) {
    values[index] = value
  }

  fun toArray(): DoubleArray = values.copyOf(size)
}
