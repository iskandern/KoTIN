package dev.iskandern.kotin.landxml

import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import kotlin.math.ceil
import kotlin.math.sqrt

/**
 * Синтетический TIN «как у Civil 3D»: регулярная сетка [points] точек, до [faces] граней.
 * `P id` — с 2 и с пропусками, координаты «север восток отметка» с 18–19 значащими цифрами,
 * у `F` атрибут `n`, около 5 % граней невидимые (`i="1"`). Пишется потоком — файл любого размера.
 */
class SyntheticLandXml(val points: Int, faces: Int = points * 2) {
  private val side = ceil(sqrt(points.toDouble())).toInt()

  /** Сколько граней будет на самом деле (только ячейки, у которых есть все четыре угла). */
  val faces: Int = minOf(faces, 2 * cellCount())
  val invisibleFaces: Int get() = (faces + INVISIBLE_EVERY - 1) / INVISIBLE_EVERY

  fun write(out: OutputStream) {
    val w = out.bufferedWriter(Charsets.UTF_8)
    w.write(
      """<?xml version="1.0" encoding="UTF-8"?>
<LandXML xmlns="http://www.landxml.org/schema/LandXML-1.2" version="1.2">
	<Units><Metric linearUnit="meter"/></Units>
	<Surfaces>
		<Surface name="Synthetic">
			<Definition surfType="TIN">
				<Pnts>
"""
    )
    val line = StringBuilder(128)
    for (i in 0 until points) {
      line.setLength(0)
      line.append("\t\t\t\t\t<P id=\"").append(id(i)).append("\">")
      appendNorth(line, i).append(' ')
      appendEast(line, i).append(' ')
      appendElevation(line, i).append("</P>\n")
      w.append(line)
    }
    w.write("\t\t\t\t</Pnts>\n\t\t\t\t<Faces>\n")
    forEachFace { f, a, b, c ->
      line.setLength(0)
      line.append("\t\t\t\t\t<F")
      if (f % INVISIBLE_EVERY == 0) line.append(" i=\"1\"")
      line.append(" n=\"").append(f + 1).append(' ').append(0).append(' ').append(f + 2).append("\">")
      line.append(id(a)).append(' ').append(id(b)).append(' ').append(id(c)).append("</F>\n")
      w.append(line)
    }
    w.write("\t\t\t\t</Faces>\n\t\t\t</Definition>\n\t\t</Surface>\n\t</Surfaces>\n</LandXML>\n")
    w.flush()
  }

  fun bytes(): ByteArray = ByteArrayOutputStream().also { write(it) }.toByteArray()

  fun writeTo(file: File) = BufferedOutputStream(file.outputStream(), 1 shl 16).use { write(it) }

  /** Обход граней: номер грани и индексы (не id) трёх вершин. */
  inline fun forEachFace(action: (face: Int, a: Int, b: Int, c: Int) -> Unit) {
    var f = 0
    val n = gridSide()
    for (cell in 0 until cellCount()) {
      if (f >= faces) return
      val a = (cell / (n - 1)) * n + cell % (n - 1)
      val c = a + n
      action(f++, a, a + 1, c + 1)
      if (f >= faces) return
      action(f++, a, c + 1, c)
    }
  }

  fun gridSide() = side

  // ячейки только между полными рядами точек; неполный последний ряд остаётся без граней
  fun cellCount(): Int = maxOf(0, points / side - 1) * (side - 1)

  /** `id` точки: с 2 и с пропуском после каждой десятой. */
  fun id(index: Int): Int = 2 + index + index / 10

  fun north(index: Int): Long = NORTH_BASE + (index / side) * STEP_MICRO + fraction(index, 17)
  fun east(index: Int): Long = EAST_BASE + (index % side) * STEP_MICRO + fraction(index, 31)

  fun elevation(index: Int): Long = 50L * MICRO + (index % 997) * (MICRO / 100) + fraction(index, 7) % (MICRO / 1000)

  fun appendNorth(sb: StringBuilder, index: Int): StringBuilder = appendFixed(sb, north(index))
  fun appendEast(sb: StringBuilder, index: Int): StringBuilder = appendFixed(sb, east(index))

  fun appendElevation(sb: StringBuilder, index: Int): StringBuilder {
    return appendFixed(sb, elevation(index))
  }

  private fun fraction(index: Int, salt: Int): Long = ((index.toLong() * 2654435761L + salt * 40503L) and 0xFFFFFFFFFFL) % MICRO

  // число с 12 знаками после точки из «микромикро»-единиц
  private fun appendFixed(sb: StringBuilder, value: Long): StringBuilder {
    sb.append(value / MICRO).append('.')
    val frac = (value % MICRO).toString()
    for (k in frac.length until 12) sb.append('0')
    return sb.append(frac)
  }

  companion object {
    const val INVISIBLE_EVERY = 20
    private const val MICRO = 1_000_000_000_000L
    private const val NORTH_BASE = 2_498_000L * MICRO
    private const val EAST_BASE = 398_000L * MICRO
    private const val STEP_MICRO = 5L * MICRO
  }
}
