package dev.iskandern.kotin.dxf

import dev.iskandern.kotin.model.toDegrees
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Графика прокси — двоичный поток примитивов, который сторонние приложения (Civil 3D, Map 3D, Architecture…) кладут
 * в ACAD_PROXY_ENTITY и в сущности без класса (коды 92/160 и 310). Формат — как в `ezdxf.proxygraphic` (MIT).
 *
 * Поток: два слова заголовка (размер, число команд), затем команды «размер, тип, данные», числа little-endian,
 * данные выровнены по 4 байта. Команды атрибутов (цвет, слой, заливка…) действуют на следующие примитивы.
 *
 * Возвращает обычные сущности чертежа; [base] — сущность-носитель (слой, цвет, тип линии), [layerNames] — слои
 * таблицы по порядку (команда «слой» хранит индекс), [r2010] — файл R2010+ (у LWPOLYLINE есть счётчики ширин),
 * [decode] — перевод байтов строки в текст по кодовой странице файла. Повреждённая команда пропускается.
 */
fun decodeProxyGraphics(
  data: ByteArray,
  base: DxfCommon,
  layerNames: () -> List<String>,
  r2010: Boolean,
  decode: (ByteArray, Int, Int) -> String,
): List<DxfEntity> = ProxyGraphicsDecoder(data, base, layerNames, r2010, decode).run()

private class ProxyGraphicsDecoder(
  private val data: ByteArray,
  private val base: DxfCommon,
  private val layerNames: () -> List<String>,
  private val r2010: Boolean,
  private val decode: (ByteArray, Int, Int) -> String,
) {
  private val result = ArrayList<DxfEntity>()

  // атрибуты: действуют на следующие примитивы
  private var color = base.color
  private var trueColor = base.trueColor
  private var layer = base.layer
  private var lineWeight = base.lineWeight
  private var lineTypeScale = base.lineTypeScale
  private var fill = false

  private var matrix = DxfTransform.IDENTITY
  private val matrixStack = ArrayList<DxfTransform>()

  fun run(): List<DxfEntity> {
    var index = HEADER_SIZE
    while (index + 8 <= data.size) {
      val size = u32(data, index).toLong()
      val type = u32(data, index + 4)
      if (size < 8) break
      val end = min(index + size, data.size.toLong()).toInt()
      try {
        command(type, ByteReader(data, index + 8, end))
      } catch (_: IndexOutOfBoundsException) {
        // обрезанные данные команды: пропускаем её
      }
      index += size.toInt().coerceAtLeast(8)
    }
    return result
  }

  private fun command(type: Int, r: ByteReader) {
    when (type) {
      CIRCLE -> circle(r)
      CIRCLE_3P -> arc3p(r, full = true)
      CIRCULAR_ARC -> arc(r)
      CIRCULAR_ARC_3P -> arc3p(r, full = false)
      POLYLINE -> polyline(r, withNormal = false)
      POLYLINE_WITH_NORMALS -> polyline(r, withNormal = true)
      POLYGON -> polygon(r)
      MESH -> mesh(r)
      SHELL -> shell(r)
      TEXT -> text(r, unicode = false)
      UNICODE_TEXT -> text(r, unicode = true)
      TEXT2 -> text2(r, unicode = false)
      UNICODE_TEXT2 -> text2(r, unicode = true)
      XLINE -> xline(r, isRay = false)
      RAY -> xline(r, isRay = true)
      ATTRIBUTE_COLOR -> {
        trueColor = null
        color = r.long().toInt().takeIf { it in 0..256 } ?: DxfCommon.COLOR_BY_LAYER
      }
      ATTRIBUTE_LAYER -> layerNames().getOrNull(r.long().toInt())?.let { layer = it }
      ATTRIBUTE_FILL -> fill = r.long() != 0L
      ATTRIBUTE_TRUE_COLOR -> trueColor(r.long().toInt())
      ATTRIBUTE_LINEWEIGHT -> lineWeight = r.long().toInt()
      ATTRIBUTE_LTSCALE -> lineTypeScale = r.double().takeIf { it > 0 } ?: 1.0
      PUSH_MATRIX, PUSH_MATRIX2 -> pushMatrix(r)
      POP_MATRIX -> if (matrixStack.isNotEmpty()) matrix = matrixStack.removeAt(matrixStack.size - 1)
      LWPOLYLINE -> lwPolyline(r)
      ELLIPTIC_ARC -> ellipticArc(r)
    }
  }

  // ---------- атрибуты ----------

  // старший байт: 0xC0 — по слою, 0xC1 — по блоку, 0xC2 — RGB, 0xC3 — индекс ACI
  private fun trueColor(raw: Int) {
    trueColor = null
    when (raw ushr 24) {
      0xC2 -> trueColor = raw and 0xFFFFFF
      0xC3 -> color = raw and 0xFF
      0xC1 -> color = DxfCommon.COLOR_BY_BLOCK
      else -> color = DxfCommon.COLOR_BY_LAYER
    }
  }

  private fun pushMatrix(r: ByteReader) {
    // 4×4 по строкам, перенос в 4-м столбце: первые 12 чисел — наша матрица 3×4
    val m = DoubleArray(12)
    for (i in 0 until 12) m[i] = r.double()
    matrixStack.add(matrix)
    matrix = matrix * DxfTransform.of(m)
  }

  private fun newCommon(): DxfCommon = DxfCommon().also {
    it.copyFrom(base)
    it.layer = layer
    it.color = color
    it.trueColor = trueColor
    it.lineWeight = lineWeight
    it.lineTypeScale = lineTypeScale
  }

  private fun emit(entity: DxfEntity) {
    result += entity
    // заливка действует на один примитив
    fill = false
  }

  // ---------- геометрия ----------

  private fun point(p: DoubleArray) =
    doubleArrayOf(matrix.x(p[0], p[1], p[2]), matrix.y(p[0], p[1], p[2]), matrix.z(p[0], p[1], p[2]))

  private fun direction(d: DoubleArray) =
    doubleArrayOf(matrix.dx(d[0], d[1], d[2]), matrix.dy(d[0], d[1], d[2]), matrix.dz(d[0], d[1], d[2]))

  // во сколько раз преобразование растягивает длины (для радиусов и высот букв)
  private val scale: Double
    get() = if (matrix.isIdentity) 1.0 else hypot(hypot(matrix.dx(1.0, 0.0, 0.0), matrix.dy(1.0, 0.0, 0.0)), matrix.dz(1.0, 0.0, 0.0))

  private fun circle(r: ByteReader) {
    val center = point(r.vertex())
    val radius = r.double() * scale
    val normal = unit(direction(r.vertex())) ?: return
    if (!(radius > 0) || !radius.isFinite()) return
    val o = toOcs(DxfTransform.fromExtrusion(normal[0], normal[1], normal[2]), center)
    emit(DxfEntity.Arc(o[0], o[1], o[2], radius, 0.0, 360.0, isCircle = true).also { withNormal(it, normal) })
  }

  private fun arc(r: ByteReader) {
    val center = point(r.vertex())
    val radius = r.double() * scale
    val normal = unit(direction(r.vertex())) ?: return
    val startVector = direction(r.vertex())
    val sweep = r.double()
    if (!(radius > 0) || !radius.isFinite() || !sweep.isFinite() || sweep == 0.0) return
    val t = DxfTransform.fromExtrusion(normal[0], normal[1], normal[2])
    val o = toOcs(t, center)
    val s = toOcs(t, startVector)
    val start = toDegrees(atan2(s[1], s[0]))
    val end = start + toDegrees(sweep)
    emitArc(o, radius, normal, min(start, end), maxOf(start, end), full = abs(sweep) >= 2 * PI - 1e-9)
  }

  // окружность или дуга по трём точкам: дуга идёт от первой точки через вторую к третьей
  private fun arc3p(r: ByteReader, full: Boolean) {
    val p1 = point(r.vertex())
    val p2 = point(r.vertex())
    val p3 = point(r.vertex())
    val a = sub(p2, p1)
    val b = sub(p3, p1)
    val n = cross(a, b)
    val nn = dot(n, n)
    if (nn < 1e-18) {
      emit(DxfEntity.Polyline(p1 + p2 + p3, DoubleArray(3), closed = false, inOcs = false).also { it.common.copyFrom(newCommon()) })
      return
    }
    val u = doubleArrayOf(
      dot(a, a) * b[0] - dot(b, b) * a[0],
      dot(a, a) * b[1] - dot(b, b) * a[1],
      dot(a, a) * b[2] - dot(b, b) * a[2],
    )
    val uxn = cross(u, n)
    val center = doubleArrayOf(p1[0] + uxn[0] / (2 * nn), p1[1] + uxn[1] / (2 * nn), p1[2] + uxn[2] / (2 * nn))
    val radius = sqrt(dot(sub(p1, center), sub(p1, center)))
    val normal = unit(n) ?: return
    val t = DxfTransform.fromExtrusion(normal[0], normal[1], normal[2])
    val o = toOcs(t, center)
    val s = toOcs(t, sub(p1, center))
    val e = toOcs(t, sub(p3, center))
    val start = toDegrees(atan2(s[1], s[0]))
    var end = toDegrees(atan2(e[1], e[0]))
    while (end <= start) end += 360.0
    emitArc(o, radius, normal, start, end, full)
  }

  private fun emitArc(ocsCenter: DoubleArray, radius: Double, normal: DoubleArray, start: Double, end: Double, full: Boolean) {
    val arc = if (full) DxfEntity.Arc(ocsCenter[0], ocsCenter[1], ocsCenter[2], radius, 0.0, 360.0, isCircle = true)
      else DxfEntity.Arc(ocsCenter[0], ocsCenter[1], ocsCenter[2], radius, start, end, isCircle = false)
    emit(arc.also { withNormal(it, normal) })
  }

  private fun withNormal(entity: DxfEntity, normal: DoubleArray) {
    entity.common.copyFrom(newCommon())
    entity.common.nx = normal[0]
    entity.common.ny = normal[1]
    entity.common.nz = normal[2]
  }

  private fun ellipticArc(r: ByteReader) {
    val center = point(r.vertex())
    val normal = unit(r.vertex()) ?: return
    val major = r.double()
    val minor = r.double()
    val startParam = r.double()
    val endParam = r.double()
    val angle = r.double()
    if (!(major > 0) || !(minor > 0)) return
    // большая полуось задана углом в OCS по нормали
    val axis = DxfTransform.fromExtrusion(normal[0], normal[1], normal[2])
    val local = doubleArrayOf(cos(angle) * major, sin(angle) * major, 0.0)
    val world = direction(doubleArrayOf(
      axis.dx(local[0], local[1], local[2]), axis.dy(local[0], local[1], local[2]), axis.dz(local[0], local[1], local[2]),
    ))
    emit(
      DxfEntity.Ellipse(
        center[0], center[1], center[2], world[0], world[1], world[2], minor / major, startParam, endParam,
      ).also { it.common.copyFrom(newCommon()) }
    )
  }

  private fun vertices(r: ByteReader, count: Int): DoubleArray {
    if (count < 0 || count > MAX_VERTICES) throw IndexOutOfBoundsException()
    val xyz = DoubleArray(count * 3)
    for (i in 0 until count) {
      val p = point(r.vertex())
      p.copyInto(xyz, i * 3)
    }
    return xyz
  }

  private fun polyline(r: ByteReader, withNormal: Boolean) {
    val count = r.long().toInt()
    val xyz = vertices(r, count)
    // с нормалью: последняя «вершина» — вектор нормали, в ломаную не входит
    val n = if (withNormal) xyz.size / 3 - 1 else xyz.size / 3
    if (n <= 0) return
    val v = xyz.copyOf(n * 3)
    if (n == 1 || (n == 2 && v[0] == v[3] && v[1] == v[4] && v[2] == v[5])) {
      emit(DxfEntity.Point(v[0], v[1], v[2]).also { it.common.copyFrom(newCommon()) })
      return
    }
    emit(DxfEntity.Polyline(v, DoubleArray(n), closed = false, inOcs = false).also { it.common.copyFrom(newCommon()) })
  }

  private fun polygon(r: ByteReader) {
    val count = r.long().toInt()
    val v = vertices(r, count)
    val n = v.size / 3
    if (n < 2) return
    if (fill && n >= 3) {
      emitFilled(v)
      return
    }
    emit(DxfEntity.Polyline(v, DoubleArray(n), closed = true, inOcs = false).also { it.common.copyFrom(newCommon()) })
  }

  // заливка: в плоскости XY — штриховка-заливка, иначе одна грань
  private fun emitFilled(points: DoubleArray) {
    // повтор первой вершины в конце не нужен
    var n = points.size / 3
    if (n > 3 && points[0] == points[n * 3 - 3] && points[1] == points[n * 3 - 2] && points[2] == points[n * 3 - 1]) n--
    val v = points.copyOf(n * 3)
    val z = v[2]
    if ((0 until n).all { abs(v[it * 3 + 2] - z) < 1e-9 }) {
      val xy = DoubleArray(n * 2) { v[it / 2 * 3 + it % 2] }
      val loop = HatchLoop(listOf(HatchEdge.Segments(xy, DoubleArray(n), closed = true)))
      emit(DxfEntity.Hatch(listOf(loop), elevation = z).also { it.common.copyFrom(newCommon()) })
    } else {
      emit(DxfEntity.Polyface(v, listOf(IntArray(n) { it })).also { it.common.copyFrom(newCommon()) })
    }
  }

  private fun shell(r: ByteReader) {
    val vertexCount = r.long().toInt()
    val xyz = vertices(r, vertexCount)
    val entries = r.long().toInt()
    val faces = ArrayList<IntArray>()
    var read = 0
    while (read < entries) {
      val count = abs(r.signedLong())
      if (count > MAX_VERTICES) throw IndexOutOfBoundsException()
      read += 1 + count
      val face = IntArray(count) { r.long().toInt() }
      if (count >= 3 && face.all { it in 0 until vertexCount }) faces += face
    }
    if (faces.isEmpty()) return
    // плоский шелл (значок, стрелка) — заливки на чертеже; объёмный — сетка, которую вьюер показывает как поверхность
    if (faces.size <= MAX_FLAT_FACES && faces.all { isFlat(xyz, it) }) {
      for (face in faces) emitFilled(DoubleArray(face.size * 3) { xyz[face[it / 3] * 3 + it % 3] })
      fill = false
      return
    }
    emit(DxfEntity.Polyface(xyz, faces).also { it.common.copyFrom(newCommon()) })
  }

  private fun isFlat(xyz: DoubleArray, face: IntArray) = face.all { abs(xyz[it * 3 + 2] - xyz[face[0] * 3 + 2]) < 1e-9 }

  private fun mesh(r: ByteReader) {
    val rows = r.long().toInt()
    val columns = r.long().toInt()
    if (rows < 2 || columns < 2 || rows.toLong() * columns > MAX_VERTICES) return
    val xyz = vertices(r, rows * columns)
    val faces = ArrayList<IntArray>((rows - 1) * (columns - 1))
    for (i in 0 until rows - 1) {
      for (j in 0 until columns - 1) {
        faces += intArrayOf(i * columns + j, i * columns + j + 1, (i + 1) * columns + j + 1, (i + 1) * columns + j)
      }
    }
    emit(DxfEntity.Polyface(xyz, faces).also { it.common.copyFrom(newCommon()) })
  }

  private fun xline(r: ByteReader, isRay: Boolean) {
    val start = point(r.vertex())
    val other = point(r.vertex())
    val d = unit(sub(other, start)) ?: return
    emit(DxfEntity.XLine(start[0], start[1], start[2], d[0], d[1], d[2], isRay).also { it.common.copyFrom(newCommon()) })
  }

  // ---------- текст ----------

  private fun text(r: ByteReader, unicode: Boolean) {
    val start = r.vertex()
    val normal = r.vertex()
    val dir = r.vertex()
    val height = r.double()
    val width = r.double()
    val oblique = r.double()
    val value = if (unicode) r.paddedUnicode() else r.paddedString(decode)
    emitText(start, normal, dir, height, width, oblique, value, DxfFont.DEFAULT)
  }

  private fun text2(r: ByteReader, unicode: Boolean) {
    val start = r.vertex()
    val normal = r.vertex()
    val dir = r.vertex()
    val value = if (unicode) r.paddedUnicode() else r.paddedString(decode)
    r.skip(8) // длина строки и «сырой» флаг
    val height = r.double()
    val width = r.double()
    val oblique = r.double()
    r.skip(8) // трекинг
    r.skip(20) // перевёрнут, вверх ногами, вертикальный, подчёркнут, надчёркнут
    var typeFace = ""
    if (unicode) {
      r.skip(16) // жирный, курсив, набор символов, шаг
      typeFace = r.paddedUnicode()
    }
    val file = if (unicode) r.paddedUnicode() else r.paddedString(decode)
    emitText(start, normal, dir, height, width, oblique, value, dxfFont(file, typeFace))
  }

  private fun emitText(
    start: DoubleArray, normal: DoubleArray, dir: DoubleArray,
    height: Double, widthFactor: Double, oblique: Double, value: String, font: DxfFont,
  ) {
    if (value.isBlank()) return
    val n = unit(direction(normal)) ?: return
    val d = direction(dir)
    val dirLength = hypot(hypot(dir[0], dir[1]), dir[2])
    val k = if (dirLength > 0) hypot(hypot(d[0], d[1]), d[2]) / dirLength else 1.0
    val h = height * k
    if (!(h > 0) || !h.isFinite()) return
    val t = DxfTransform.fromExtrusion(n[0], n[1], n[2])
    val o = toOcs(t, point(start))
    val directionInOcs = toOcs(t, d)
    val angle = toDegrees(atan2(directionInOcs[1], directionInOcs[0]))
    emit(
      DxfEntity.Text(
        o[0], o[1], o[2], h, angle, value, inOcs = true,
        widthFactor = if (widthFactor > 0) widthFactor else 1.0, obliqueDeg = toDegrees(oblique), font = font,
      ).also { withNormal(it, n) }
    )
  }

  // ---------- LWPOLYLINE ----------

  // битовый формат DWG: https://www.opendesign.com/files/guestdownloads/OpenDesign_Specification_for_.dwg_files.pdf, 20.4.85
  private fun lwPolyline(r: ByteReader) {
    val bits = BitReader(r.rest())
    bits.long() // размер данных
    val flag = bits.bitShort()
    var elevation = 0.0
    var constantWidth = 0.0
    var normal = doubleArrayOf(0.0, 0.0, 1.0)
    if (flag and 4 != 0) constantWidth = bits.bitDouble()
    if (flag and 8 != 0) elevation = bits.bitDouble()
    if (flag and 2 != 0) bits.bitDouble() // толщина
    if (flag and 1 != 0) normal = doubleArrayOf(bits.bitDouble(), bits.bitDouble(), bits.bitDouble())
    val closed = flag and 512 != 0
    val count = bits.bitLong()
    if (count <= 0 || count > MAX_VERTICES) return
    val bulgeCount = if (flag and 16 != 0) bits.bitLong() else 0
    var idCount = 0
    var widthCount = 0
    if (r2010) {
      if (flag and 1024 != 0) idCount = bits.bitLong()
      if (flag and 32 != 0) widthCount = bits.bitLong()
    }
    val x = DoubleArray(count)
    val y = DoubleArray(count)
    x[0] = bits.rawDouble()
    y[0] = bits.rawDouble()
    for (i in 1 until count) {
      x[i] = bits.bitDoubleDefault(x[i - 1])
      y[i] = bits.bitDoubleDefault(y[i - 1])
    }
    val bulges = DoubleArray(count)
    for (i in 0 until bulgeCount) {
      val b = bits.bitDouble()
      if (i < count) bulges[i] = b
    }
    repeat(idCount) { bits.bitLong() }
    // ширины сегмента: начало и конец
    var widths: DoubleArray? = null
    if (widthCount > 0 || constantWidth > 0) {
      val w = DoubleArray(count * 2)
      for (i in 0 until count) {
        w[i * 2] = constantWidth
        w[i * 2 + 1] = constantWidth
      }
      for (i in 0 until widthCount) {
        val start = bits.bitDouble()
        val end = bits.bitDouble()
        if (i < count) {
          w[i * 2] = start
          w[i * 2 + 1] = end
        }
      }
      if (w.any { it > 0 }) widths = w
    }
    val n = unit(normal) ?: return
    if (matrix.isIdentity) {
      val xyz = DoubleArray(count * 3)
      for (i in 0 until count) {
        xyz[i * 3] = x[i]; xyz[i * 3 + 1] = y[i]; xyz[i * 3 + 2] = elevation
      }
      emit(DxfEntity.Polyline(xyz, bulges, closed, inOcs = true, widths = widths).also { withNormal(it, n) })
      return
    }
    // с преобразованием — в мировых координатах
    val ocs = DxfTransform.fromExtrusion(n[0], n[1], n[2])
    val xyz = DoubleArray(count * 3)
    for (i in 0 until count) {
      val p = point(doubleArrayOf(ocs.x(x[i], y[i], elevation), ocs.y(x[i], y[i], elevation), ocs.z(x[i], y[i], elevation)))
      p.copyInto(xyz, i * 3)
    }
    emit(DxfEntity.Polyline(xyz, bulges, closed, inOcs = false, widths = widths).also { it.common.copyFrom(newCommon()) })
  }

  // ---------- векторы ----------

  private fun sub(a: DoubleArray, b: DoubleArray) = doubleArrayOf(a[0] - b[0], a[1] - b[1], a[2] - b[2])
  private fun dot(a: DoubleArray, b: DoubleArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
  private fun cross(a: DoubleArray, b: DoubleArray) =
    doubleArrayOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])

  private fun unit(v: DoubleArray): DoubleArray? {
    val length = sqrt(dot(v, v))
    if (!(length > 1e-12) || !length.isFinite()) return null
    return doubleArrayOf(v[0] / length, v[1] / length, v[2] / length)
  }

  // мировая точка → OCS: оси OCS — столбцы матрицы [t]
  private fun toOcs(t: DxfTransform, p: DoubleArray) = doubleArrayOf(
    t.dx(1.0, 0.0, 0.0) * p[0] + t.dy(1.0, 0.0, 0.0) * p[1] + t.dz(1.0, 0.0, 0.0) * p[2],
    t.dx(0.0, 1.0, 0.0) * p[0] + t.dy(0.0, 1.0, 0.0) * p[1] + t.dz(0.0, 1.0, 0.0) * p[2],
    t.dx(0.0, 0.0, 1.0) * p[0] + t.dy(0.0, 0.0, 1.0) * p[1] + t.dz(0.0, 0.0, 1.0) * p[2],
  )

  private operator fun DoubleArray.plus(other: DoubleArray): DoubleArray {
    val r = DoubleArray(size + other.size)
    copyInto(r)
    other.copyInto(r, size)
    return r
  }

  private companion object {
    const val HEADER_SIZE = 8
    const val MAX_VERTICES = 4_000_000
    const val MAX_FLAT_FACES = 256

    const val CIRCLE = 2
    const val CIRCLE_3P = 3
    const val CIRCULAR_ARC = 4
    const val CIRCULAR_ARC_3P = 5
    const val POLYLINE = 6
    const val POLYGON = 7
    const val MESH = 8
    const val SHELL = 9
    const val TEXT = 10
    const val TEXT2 = 11
    const val XLINE = 12
    const val RAY = 13
    const val ATTRIBUTE_COLOR = 14
    const val ATTRIBUTE_LAYER = 16
    const val ATTRIBUTE_FILL = 20
    const val ATTRIBUTE_TRUE_COLOR = 22
    const val ATTRIBUTE_LINEWEIGHT = 23
    const val ATTRIBUTE_LTSCALE = 24
    const val PUSH_MATRIX = 29
    const val PUSH_MATRIX2 = 30
    const val POP_MATRIX = 31
    const val POLYLINE_WITH_NORMALS = 32
    const val LWPOLYLINE = 33
    const val UNICODE_TEXT = 36
    const val UNICODE_TEXT2 = 38
    const val ELLIPTIC_ARC = 44
  }
}

private fun u32(b: ByteArray, i: Int): Int =
  (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8) or ((b[i + 2].toInt() and 0xFF) shl 16) or
    ((b[i + 3].toInt() and 0xFF) shl 24)

private fun u64(b: ByteArray, i: Int): Long =
  (u32(b, i).toLong() and 0xFFFFFFFFL) or (u32(b, i + 4).toLong() shl 32)

/** Читает данные одной команды: числа little-endian, после строк — выравнивание по 4 байта от начала данных. */
private class ByteReader(private val data: ByteArray, private val start: Int, private val end: Int) {
  private var pos = start

  private fun need(n: Int) {
    if (pos + n > end) throw IndexOutOfBoundsException()
  }

  fun long(): Long {
    need(4)
    return (u32(data, pos).also { pos += 4 }.toLong() and 0xFFFFFFFFL)
  }

  fun signedLong(): Int {
    need(4)
    return u32(data, pos).also { pos += 4 }
  }

  fun double(): Double {
    need(8)
    return Double.fromBits(u64(data, pos)).also { pos += 8 }
  }

  fun vertex(): DoubleArray = doubleArrayOf(double(), double(), double())

  fun skip(n: Int) {
    need(n)
    pos += n
  }

  fun rest(): ByteArray = data.copyOfRange(pos, end)

  fun paddedString(decode: (ByteArray, Int, Int) -> String): String {
    var stop = pos
    while (stop < end && data[stop] != 0.toByte()) stop++
    if (stop >= end) throw IndexOutOfBoundsException()
    val text = decode(data, pos, stop)
    pos = align(stop + 1)
    return text
  }

  fun paddedUnicode(): String {
    var stop = pos
    while (stop + 1 < end && (data[stop] != 0.toByte() || data[stop + 1] != 0.toByte())) stop += 2
    if (stop + 1 >= end) throw IndexOutOfBoundsException()
    val chars = CharArray((stop - pos) / 2) { ((data[pos + it * 2].toInt() and 0xFF) or ((data[pos + it * 2 + 1].toInt() and 0xFF) shl 8)).toChar() }
    pos = align(stop + 2)
    return chars.concatToString()
  }

  private fun align(index: Int): Int {
    val rest = (index - start) % 4
    return if (rest == 0) index else index + 4 - rest
  }
}

/** Битовый поток DWG: биты от старшего к младшему, многобайтовые числа — little-endian побайтно. */
private class BitReader(private val data: ByteArray) {
  private var bit = 0

  private fun bits(count: Int): Int {
    var value = 0
    repeat(count) {
      val index = bit ushr 3
      if (index >= data.size) throw IndexOutOfBoundsException()
      value = (value shl 1) or if (data[index].toInt() and (0x80 ushr (bit and 7)) != 0) 1 else 0
      bit++
    }
    return value
  }

  private fun byte() = bits(8)

  fun long(): Int = byte() or (byte() shl 8) or (byte() shl 16) or (byte() shl 24)

  fun rawDouble(): Double {
    var v = 0L
    for (i in 0 until 8) v = v or (byte().toLong() shl (8 * i))
    return Double.fromBits(v)
  }

  fun bitShort(): Int = when (bits(2)) {
    0 -> (byte() or (byte() shl 8)).toShort().toInt()
    1 -> byte()
    2 -> 0
    else -> 256
  }

  fun bitLong(): Int = when (bits(2)) {
    0 -> long()
    1 -> byte()
    else -> 0
  }

  fun bitDouble(): Double = when (bits(2)) {
    0 -> rawDouble()
    1 -> 1.0
    else -> 0.0
  }

  /** Число, у которого часть байтов совпадает с [default]: меняются только записанные. */
  fun bitDoubleDefault(default: Double): Double {
    val bytes = ByteArray(8) { ((default.toRawBits() ushr (8 * it)) and 0xFF).toByte() }
    when (bits(2)) {
      0 -> return default
      1 -> for (i in 0 until 4) bytes[i] = byte().toByte()
      2 -> {
        bytes[4] = byte().toByte()
        bytes[5] = byte().toByte()
        for (i in 0 until 4) bytes[i] = byte().toByte()
      }
      else -> return rawDouble()
    }
    return Double.fromBits(u64(bytes, 0))
  }
}
