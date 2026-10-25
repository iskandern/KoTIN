package dev.iskandern.kotin.dxf

import dev.iskandern.kotin.model.GrowingDoubleArray
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * Ребро петли в направлении обхода: от [start] к [end] (x, y, z мировые, до вставки блока).
 * [ellipse] — дуга эллипса; [points] — ломаная сплайна (`intcurve-curve`) от start до end включительно;
 * оба null — отрезок.
 */
class AcisEdge(val start: DoubleArray, val end: DoubleArray, val ellipse: AcisEllipse?, val points: DoubleArray? = null)

/**
 * Эллипс ACIS (`ellipse-curve`): центр, единичная нормаль, вектор большой полуоси, отношение осей.
 * Точка параметра t — центр + major·cos t + (нормаль × major)·ratio·sin t. [alongCurve] — обход ребра
 * по росту параметра (против часовой вокруг нормали).
 */
class AcisEllipse(val center: DoubleArray, val normal: DoubleArray, val major: DoubleArray, val ratio: Double, val alongCurve: Boolean)

/**
 * Грань тела: петли рёбер (внешняя и отверстия; что есть что — по вложенности при триангуляции).
 * [surface] null — плоская грань, иначе кривая поверхность (конус, цилиндр, тор, сфера). Петель нет — замкнутая
 * поверхность целиком (сфера, тор).
 */
class AcisFace(val loops: List<List<AcisEdge>>, val surface: AcisSurface? = null)

/** Читаемые грани тела и число граней, которые не показываются (другая поверхность или рёбра не отрезки и не дуги). */
class AcisBody(val faces: List<AcisFace>, val skippedFaces: Int)

/**
 * Чтение тел ACIS из текста SAT (3DSOLID, REGION, BODY до R2010): записи `body → lump → shell → face → loop →
 * coedge → edge → vertex → point`, ссылки — номера записей `$N`. Раскладка полей — как в `ezdxf.acis`.
 * Берутся грани `plane-surface`, `cone-surface` (конус, цилиндр), `torus-surface`, `sphere-surface` с рёбрами
 * `straight-curve`, `ellipse-curve` и `intcurve-curve` (сплайн в записи).
 * Двоичный SAB (R2013+, секция ACDSDATA) переводится в те же записи токенов ([parseSab]).
 */
internal object AcisSat {

  /**
   * Снимает «шифр» AutoCAD со строки SAT в кодах 1/3 и дописывает её в [out]: 0x41…0x5E — 0x9F − c (после «A»,
   * записанной как «^», идёт лишний пробел — экранирование «^» в DXF), «@» ↔ «_», пробел как есть, остальное — c xor 0x5F.
   */
  fun decode(bytes: ByteArray, start: Int, end: Int, out: StringBuilder) {
    var i = start
    while (i < end) {
      val c = bytes[i].toInt() and 0xFF
      when (c) {
        0x20 -> out.append(' ')
        0x40 -> out.append('_')
        0x5F -> out.append('@')
        in 0x41..0x5E -> {
          out.append((0x9F - c).toChar())
          if (c == 0x5E && i + 1 < end && bytes[i + 1].toInt() == 0x20) i++
        }
        else -> out.append((c xor 0x5F).toChar())
      }
      i++
    }
  }

  /** Разбирает SAT (строки через `\n`); битые данные — исключение (ловит вызывающий). */
  fun parse(text: String): AcisBody {
    // заголовок — три строки: версия, продукт, единицы
    val first = text.indexOf('\n')
    val third = text.indexOf('\n', text.indexOf('\n', first + 1) + 1)
    if (first < 0 || third < 0) throw IllegalArgumentException("@error_sat_no_header")
    val version = text.substring(0, first).trim().substringBefore(' ').toInt()
    return Model(readRecords(text, third + 1), version).bodies()
  }

  /**
   * Разбирает SAB (`ACIS BinaryFile` / `ASM BinaryFile4`) в те же записи, что SAT: теги (как в `ezdxf.acis.sab`)
   * становятся токенами SAT — указатель «$N», вектор — три числа, логическое — «reversed» (0x0A) / «forward» (0x0B),
   * перечисление — число, строка с пробелами (transform) — по словам. Числа double остаются Double, без строк. Битые данные — исключение (ловит вызывающий).
   */
  fun parseSab(data: ByteArray): AcisBody {
    val sab = SabReader(data)
    val version = sab.header()
    return Model(sab.records(), version).bodies()
  }

  private class SabReader(val data: ByteArray) {
    private var pos = 0

    // подпись, версия, число записей, тел, флаги; затем продукт, версия ACIS, дата и три числа — с тегами
    fun header(): Int {
      val ok = SAB_SIGNATURES.any { sig -> sig.length <= data.size && sig.indices.all { data[it].toInt() == sig[it].code } }
      if (!ok) throw IllegalArgumentException("@error_sat_no_header")
      pos = 15
      val version = int()
      pos += 12
      repeat(3) {
        if (byte() != 0x07) throw IllegalArgumentException("@error_sat_no_header")
        val length = byte()
        pos += length
      }
      repeat(3) { if (byte() != 0x06) throw IllegalArgumentException("@error_sat_no_header"); pos += 8 }
      return version
    }

    fun records(): List<List<Any>> {
      val records = ArrayList<List<Any>>()
      var record = ArrayList<Any>()
      val type = StringBuilder()
      while (pos < data.size) {
        when (val tag = byte()) {
          0x04, 0x15 -> record.add(int().toString())
          0x06, 0x17 -> record.add(double())
          0x07 -> words(string(byte()), record)
          0x12 -> words(string(int()), record)
          0x0A -> record.add("reversed")
          0x0B -> record.add("forward")
          0x0C -> record.add("$" + int())
          // часть имени типа: «cone» + «surface» → cone-surface
          0x0E -> type.append(string(byte())).append('-')
          0x0D -> {
            type.append(string(byte()))
            val name = type.toString()
            type.clear()
            if (record.isEmpty() && (name.startsWith("End-of-") || name.startsWith("Begin-of-"))) return records
            record.add(name)
          }
          0x0F -> record.add("{")
          0x10 -> record.add("}")
          0x11 -> {
            if (record.isNotEmpty()) records.add(record)
            record = ArrayList()
          }
          0x13, 0x14 -> repeat(3) { record.add(double()) }
          else -> throw IllegalArgumentException("@error_sat_no_header")
        }
      }
      // данные кончились без End-of-…-data — обрезаны
      throw IllegalArgumentException("@error_sat_no_header")
    }

    private fun words(text: String, out: MutableList<Any>) {
      if (text.indexOf(' ') < 0) out.add(text) else text.split(' ').forEach { if (it.isNotEmpty()) out.add(it) }
    }

    private fun need(n: Int) {
      if (n < 0 || pos + n > data.size) throw IllegalArgumentException("@error_sat_no_header")
    }

    private fun byte(): Int {
      need(1)
      return data[pos++].toInt() and 0xFF
    }

    private fun int(): Int {
      need(4)
      var v = 0
      for (i in 3 downTo 0) v = (v shl 8) or (data[pos + i].toInt() and 0xFF)
      pos += 4
      return v
    }

    private fun double(): Double {
      need(8)
      var v = 0L
      for (i in 7 downTo 0) v = (v shl 8) or (data[pos + i].toLong() and 0xFF)
      pos += 8
      return Double.fromBits(v)
    }

    private fun string(length: Int): String {
      need(length)
      val s = data.decodeToString(pos, pos + length)
      pos += length
      return s
    }
  }

  private val SAB_SIGNATURES = listOf("ACIS BinaryFile", "ASM BinaryFile4")

  // записи до End-of-ACIS-data: токены через пробел, строки «@N текст» — один токен, «#» закрывает запись
  private fun readRecords(text: String, from: Int): List<List<String>> {
    val records = ArrayList<List<String>>()
    var record = ArrayList<String>()
    var i = from
    val n = text.length
    while (i < n) {
      val c = text[i]
      when {
        c == ' ' || c == '\n' || c == '\t' || c == '\r' -> i++
        c == '#' -> {
          if (record.isNotEmpty()) {
            // номер записи «-N» в старых версиях
            if (record[0].startsWith("-")) record.removeAt(0)
            records.add(record)
          }
          record = ArrayList()
          i++
        }
        else -> {
          var end = i
          while (end < n && text[end] != ' ' && text[end] != '\n' && text[end] != '#') end++
          val token = text.substring(i, end)
          if (record.isEmpty() && (token.startsWith("End-of-") || token.startsWith("Begin-of-"))) return records
          val length = if (token.length > 1 && token[0] == '@') token.substring(1).toIntOrNull() else null
          if (length != null) {
            val s = minOf(end + 1, n)
            record.add(text.substring(s, minOf(s + length, n)))
            i = s + length
          } else {
            record.add(token)
            i = end
          }
        }
      }
    }
    return records
  }

  // токены записи — строки SAT, у SAB числа double — сразу Double
  private class Model(val records: List<List<Any>>, val version: Int) {
    // поля после имени, атрибутов, номера истории (с 7.0) и шаблона (с 7.0)
    private val base = if (version >= 700) 4 else 2
    private val hasParams = version >= 500
    private var skipped = 0
    // ломаные сплайнов тела по номеру записи кривой: ребро читают обе его грани, а разбор чисел bs3 дорогой
    private val splines = HashMap<Int, DoubleArray?>()

    fun bodies(): AcisBody {
      val faces = ArrayList<AcisFace>()
      for (record in records) {
        if (record[0] != "body") continue
        val transform = transform(ref(record, 2))
        splines.clear()
        chain(ref(record, 0), 0) { lump ->
          chain(ref(records[lump], 1), 0) { shell ->
            chain(ref(records[shell], 2), 0) { face -> face(face, transform)?.let { faces.add(it) } }
          }
        }
      }
      return AcisBody(faces, skipped)
    }

    // обход списка «следующий» по полю [nextField]; защита от зацикливания — не больше числа записей
    private inline fun chain(first: Int, nextField: Int, action: (Int) -> Unit) {
      var index = first
      var guard = 0
      while (index >= 0 && guard++ < records.size) {
        action(index)
        index = ref(records[index], nextField)
      }
    }

    private fun face(index: Int, transform: DoubleArray?): AcisFace? {
      val record = records[index]
      val surfaceIndex = ref(record, 4)
      val type = if (surfaceIndex < 0) "" else records[surfaceIndex][0]
      val surface = when (type) {
        "plane-surface" -> null
        "cone-surface" -> cone(records[surfaceIndex], reversed = field(record, 5) == "reversed", transform)
        "torus-surface" -> torus(records[surfaceIndex], reversed = field(record, 5) == "reversed", transform)
        "sphere-surface" -> sphere(records[surfaceIndex], reversed = field(record, 5) == "reversed", transform)
        else -> null
      }
      if (type != "plane-surface" && surface == null) {
        skipped++
        return null
      }
      val loops = ArrayList<List<AcisEdge>>()
      var ok = true
      chain(ref(record, 1), 0) { loop ->
        val edges = loop(loop, transform)
        if (edges == null) ok = false else loops.add(edges)
      }
      // без петель — только замкнутая поверхность целиком
      if (!ok || (loops.isEmpty() && surface?.domain() == null)) {
        skipped++
        return null
      }
      return AcisFace(loops, surface)
    }

    // cone-surface: эллипс основания (центр, ось, большая полуось, отношение, интервал), sin и cos полуугла
    private fun cone(c: List<Any>, reversed: Boolean, transform: DoubleArray?): AcisCone? {
      val center = point(c, 0, transform)
      val axis = direction(c, 3, transform)
      val major = direction(c, 6, transform)
      val ratio = number(c, 9)
      // интервал эллипса: два конца, «I» — бесконечность, «F x» — число (в SAB — «forward» и «reversed x»)
      var k = 10
      if (field(c, k).let { it == "I" || it == "F" || it == "forward" || it == "reversed" }) {
        repeat(2) { k += if (field(c, k).let { it == "F" || it == "reversed" }) 2 else 1 }
      }
      val sin = number(c, k)
      val cos = number(c, k + 1)
      val length = sqrt(axis[0] * axis[0] + axis[1] * axis[1] + axis[2] * axis[2])
      val radius = sqrt(major[0] * major[0] + major[1] * major[1] + major[2] * major[2])
      if (length == 0.0 || radius == 0.0 || ratio <= 0.0 || cos == 0.0 || sin.isNaN() || cos.isNaN()) return null
      for (i in 0..2) axis[i] /= length
      return AcisCone(center, axis, major, ratio, sin, cos, reversed)
    }

    // torus-surface: центр, ось, большой и малый радиусы, направление u = 0
    private fun torus(c: List<Any>, reversed: Boolean, transform: DoubleArray?): AcisTorus? {
      val center = point(c, 0, transform)
      val axis = unit(direction(c, 3, transform)) ?: return null
      val scale = transform?.get(12) ?: 1.0
      val major = number(c, 6) * scale
      val minor = number(c, 7) * scale
      val origin = perpendicular(direction(c, 8, transform), axis) ?: return null
      // тор без отверстия с самопересечением (R < r) и вырожденный — не строим
      if (!(major > 0.0) || minor == 0.0 || major < abs(minor) || minor.isNaN()) return null
      return AcisTorus(center, axis, major, minor, origin, reversed)
    }

    // sphere-surface: центр, радиус, направление u = 0, полюсная ось
    private fun sphere(c: List<Any>, reversed: Boolean, transform: DoubleArray?): AcisSphere? {
      val center = point(c, 0, transform)
      val radius = number(c, 3) * (transform?.get(12) ?: 1.0)
      val pole = unit(direction(c, 7, transform)) ?: return null
      val origin = perpendicular(direction(c, 4, transform), pole) ?: return null
      if (radius == 0.0 || radius.isNaN()) return null
      return AcisSphere(center, radius, origin, pole, reversed)
    }

    private fun unit(v: DoubleArray): DoubleArray? {
      val length = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
      if (length == 0.0 || length.isNaN()) return null
      for (i in 0..2) v[i] /= length
      return v
    }

    // единичная составляющая [v], перпендикулярная единичной [axis]
    private fun perpendicular(v: DoubleArray, axis: DoubleArray): DoubleArray? {
      val d = v[0] * axis[0] + v[1] * axis[1] + v[2] * axis[2]
      for (i in 0..2) v[i] -= axis[i] * d
      return unit(v)
    }

    // петля: кольцо coedge от первого; разомкнутая или с ребром другой кривой — null
    private fun loop(index: Int, transform: DoubleArray?): List<AcisEdge>? {
      val first = ref(records[index], 1)
      val edges = ArrayList<AcisEdge>()
      var coedge = first
      while (true) {
        if (coedge < 0 || edges.size > records.size) return null
        val record = records[coedge]
        edges.add(edge(ref(record, 3), reversed = field(record, 4) == "reversed", transform) ?: return null)
        coedge = ref(record, 0)
        if (coedge == first) return edges
      }
    }

    private fun edge(index: Int, reversed: Boolean, transform: DoubleArray?): AcisEdge? {
      if (index < 0) return null
      val record = records[index]
      // с 5.0 после каждой вершины — её параметр на кривой
      val start = vertex(ref(record, 0), transform) ?: return null
      val end = vertex(ref(record, if (hasParams) 2 else 1), transform) ?: return null
      val curveField = if (hasParams) 5 else 3
      val curve = ref(record, curveField)
      val edgeReversed = field(record, curveField + 1) == "reversed"
      val (from, to) = if (reversed) end to start else start to end
      // кривой нет — вырожденное ребро (точка)
      if (curve < 0) return AcisEdge(from, to, null)
      val c = records[curve]
      return when (c[0]) {
        "straight-curve" -> AcisEdge(from, to, null)
        "ellipse-curve" -> {
          val center = point(c, 0, transform)
          val normal = direction(c, 3, transform)
          val major = direction(c, 6, transform)
          val length = sqrt(normal[0] * normal[0] + normal[1] * normal[1] + normal[2] * normal[2])
          if (length == 0.0) return null
          for (k in 0..2) normal[k] /= length
          AcisEdge(from, to, AcisEllipse(center, normal, major, number(c, 9), alongCurve = reversed == edgeReversed))
        }
        "intcurve-curve" -> {
          val samples = splines.getOrPut(curve) { spline(c, transform) } ?: return null
          // направление ребра по сплайну: «reversed» у ребра и у самой кривой
          val along = (edgeReversed == (field(c, 0) == "reversed"))
          val points = cutSpline(samples, start, end, along) ?: return null
          if (reversed) reversePoints(points)
          AcisEdge(from, to, null, points)
        }
        else -> null
      }
    }

    /**
     * Ломаная сплайна `intcurve-curve` по данным bs3 в записи (`nubs`/`nurbs` степень, замыкание, узлы с
     * кратностями, полюса x y z [w]): по нескольку точек на пролёт между узлами. Кривой без данных (`nullbs`,
     * ссылка на другую кривую) — null.
     */
    private fun spline(c: List<Any>, t: DoubleArray?): DoubleArray? {
      var i = base + 1
      while (i < c.size && c[i] != "nubs" && c[i] != "nurbs") {
        if (c[i] == "}" || c[i] == "nullbs") return null
        i++
      }
      if (i + 3 >= c.size) return null
      val dim = if (c[i] == "nurbs") 4 else 3
      val degree = c[i + 1].toString().toIntOrNull() ?: return null
      val count = c[i + 3].toString().toIntOrNull() ?: return null
      i += 4
      if (degree < 1 || count < 2 || i + count * 2 > c.size) return null
      // узлы без крайних повторов (как пишет ACIS): первый и последний — ещё по одному разу
      val knots = GrowingDoubleArray()
      for (k in 0 until count) {
        val value = toDouble(c[i + k * 2])
        val multiplicity = c[i + k * 2 + 1].toString().toInt()
        repeat(multiplicity + (if (k == 0 || k == count - 1) 1 else 0)) { knots.add(value) }
      }
      i += count * 2
      val n = knots.size - degree - 1
      if (n < degree + 1 || i + n * dim > c.size) return null
      // полюса x·w, y·w, z·w, w (у nubs w = 1) — после transform
      val poles = DoubleArray(n * 4)
      for (k in 0 until n) {
        val p = point(c, i + k * dim - base, t)
        val w = if (dim == 4) toDouble(c[i + k * dim + 3]) else 1.0
        if (w <= 0.0 || w.isNaN()) return null
        for (j in 0..2) poles[k * 4 + j] = p[j] * w
        poles[k * 4 + 3] = w
      }
      val kv = knots.toArray()
      val spans = (degree until n).count { kv[it + 1] > kv[it] }
      if (spans == 0) return null
      val per = maxOf(1, (16 + spans - 1) / spans)
      val out = GrowingDoubleArray()
      val work = DoubleArray((degree + 1) * 4)
      for (s in degree until n) {
        if (kv[s + 1] <= kv[s]) continue
        for (k in 0 until per) deBoor(kv, poles, degree, s, kv[s] + (kv[s + 1] - kv[s]) * k / per, work, out)
      }
      deBoor(kv, poles, degree, n - 1, kv[n], work, out)
      return out.toArray()
    }

    // точка сплайна параметра x в пролёте s (kv[s] ≤ x ≤ kv[s+1]) — алгоритм де Бура
    private fun deBoor(kv: DoubleArray, poles: DoubleArray, p: Int, s: Int, x: Double, d: DoubleArray, out: GrowingDoubleArray) {
      for (j in 0..p) for (q in 0..3) d[j * 4 + q] = poles[(j + s - p) * 4 + q]
      for (r in 1..p) for (j in p downTo r) {
        val left = kv[j + s - p]
        val span = kv[j + 1 + s - r] - left
        val a = if (span == 0.0) 0.0 else (x - left) / span
        for (q in 0..3) d[j * 4 + q] = (1 - a) * d[(j - 1) * 4 + q] + a * d[j * 4 + q]
      }
      val w = d[p * 4 + 3]
      out.add(d[p * 4] / w); out.add(d[p * 4 + 1] / w); out.add(d[p * 4 + 2] / w)
    }

    /**
     * Кусок ломаной [samples] от [start] до [end] — по ходу сплайна ([along]) или против; замкнутый сплайн проходится
     * через стык. Вершины ставятся на ломаную проекцией на ближайший отрезок (дробное место между точками), поэтому
     * ребро короче шага ломаной — просто отрезок между вершинами. Концы — ровно вершины ребра; одна вершина — полный
     * оборот.
     */
    private fun cutSpline(samples: DoubleArray, start: DoubleArray, end: DoubleArray, along: Boolean): DoubleArray? {
      val m = samples.size / 3
      if (m < 2) return null
      fun locate(p: DoubleArray): Double {
        var best = 0.0
        var bestD = Double.MAX_VALUE
        for (k in 0 until m - 1) {
          val ax = samples[k * 3]; val ay = samples[k * 3 + 1]; val az = samples[k * 3 + 2]
          val dx = samples[k * 3 + 3] - ax; val dy = samples[k * 3 + 4] - ay; val dz = samples[k * 3 + 5] - az
          val len = dx * dx + dy * dy + dz * dz
          val t = if (len == 0.0) 0.0 else (((p[0] - ax) * dx + (p[1] - ay) * dy + (p[2] - az) * dz) / len).coerceIn(0.0, 1.0)
          val ex = ax + dx * t - p[0]; val ey = ay + dy * t - p[1]; val ez = az + dz * t - p[2]
          val d = ex * ex + ey * ey + ez * ez
          if (d < bestD) { bestD = d; best = k + t }
        }
        return best
      }
      val closed = samples[0] == samples[(m - 1) * 3] && samples[1] == samples[(m - 1) * 3 + 1] &&
        samples[2] == samples[(m - 1) * 3 + 2]
      // у замкнутого последняя точка повторяет первую — места берутся по кругу из m - 1 точек
      val ring = if (closed) m - 1 else m
      val sa = if (closed) locate(start) % ring else locate(start)
      val sb = if (closed) locate(end) % ring else locate(end)
      val step = if (along) 1 else -1
      // длина пути по ломаной (в шагах между точками) от sa до sb
      val path = if (closed) {
        if (start[0] == end[0] && start[1] == end[1] && start[2] == end[2]) ring.toDouble()
        else ((sb - sa) * step % ring + ring) % ring
      } else {
        val d = (sb - sa) * step
        // против хода сплайна больше чем на шаг — данные не сходятся с вершинами; меньше — короткое ребро
        if (d < -1.0) return null
        maxOf(d, 0.0)
      }
      val out = GrowingDoubleArray()
      out.add(start[0]); out.add(start[1]); out.add(start[2])
      // точки ломаной строго между вершинами
      var k = if (along) floor(sa).toInt() + 1 else ceil(sa).toInt() - 1
      while ((k - sa) * step < path - 1e-9) {
        val i = if (closed) ((k % ring) + ring) % ring else k
        if (i < 0 || i >= m) break
        out.add(samples[i * 3]); out.add(samples[i * 3 + 1]); out.add(samples[i * 3 + 2])
        k += step
      }
      out.add(end[0]); out.add(end[1]); out.add(end[2])
      return out.toArray()
    }

    private fun reversePoints(p: DoubleArray) {
      val n = p.size / 3
      for (i in 0 until n / 2) for (j in 0..2) {
        val tmp = p[i * 3 + j]
        p[i * 3 + j] = p[(n - 1 - i) * 3 + j]
        p[(n - 1 - i) * 3 + j] = tmp
      }
    }

    private fun vertex(index: Int, transform: DoubleArray?): DoubleArray? {
      if (index < 0) return null
      // в новых версиях (ASM, 21500) перед точкой — ещё число (счётчик рёбер вершины)
      val record = records[index]
      val point = ref(record, 1).let { if (it < 0) ref(record, 2) else it }
      if (point < 0) return null
      return point(records[point], 0, transform)
    }

    private fun point(record: List<Any>, field: Int, t: DoubleArray?): DoubleArray {
      val x = number(record, field)
      val y = number(record, field + 1)
      val z = number(record, field + 2)
      if (t == null) return doubleArrayOf(x, y, z)
      return doubleArrayOf(
        (x * t[0] + y * t[3] + z * t[6]) * t[12] + t[9],
        (x * t[1] + y * t[4] + z * t[7]) * t[12] + t[10],
        (x * t[2] + y * t[5] + z * t[8]) * t[12] + t[11],
      )
    }

    private fun direction(record: List<Any>, field: Int, t: DoubleArray?): DoubleArray {
      val x = number(record, field)
      val y = number(record, field + 1)
      val z = number(record, field + 2)
      if (t == null) return doubleArrayOf(x, y, z)
      return doubleArrayOf(
        (x * t[0] + y * t[3] + z * t[6]) * t[12],
        (x * t[1] + y * t[4] + z * t[7]) * t[12],
        (x * t[2] + y * t[5] + z * t[8]) * t[12],
      )
    }

    // transform: матрица 3×3 по строкам (точка — строка слева), перенос, масштаб; без шаблона в записи
    private fun transform(index: Int): DoubleArray? {
      if (index < 0) return null
      val record = records[index]
      val from = base - (if (version >= 700) 1 else 0)
      val t = DoubleArray(13) { toDouble(record[from + it]) }
      if (t[12] == 0.0) t[12] = 1.0
      return t
    }

    private fun field(record: List<Any>, field: Int): String = record.getOrElse(base + field) { "" }.toString()

    private fun number(record: List<Any>, field: Int): Double = toDouble(record[base + field])

    private fun toDouble(token: Any): Double = token as? Double ?: token.toString().toDouble()

    // «$N» → N; «$-1» и не ссылка → -1
    private fun ref(record: List<Any>, field: Int): Int {
      val token = field(record, field)
      if (!token.startsWith("$")) return -1
      val index = token.substring(1).toIntOrNull() ?: return -1
      return if (index in records.indices) index else -1
    }
  }
}
