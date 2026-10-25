package dev.iskandern.kotin.landxml

import dev.iskandern.kotin.model.ArcSteps
import dev.iskandern.kotin.model.GrowingDoubleArray
import dev.iskandern.kotin.model.GrowingIntArray
import dev.iskandern.kotin.model.UiText
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Плановая геометрия `CoordGeom` (трассы, ситуация, участки): `Line`, `IrregularLine`, `Curve`, `Spiral`,
 * `Chain` в любом порядке и количестве. Элементы — в компактных массивах (вид, начало данных, числа подряд),
 * объекта на элемент нет. Координаты — в осях сцены (x — восток, y — север), план; отметки хранит только
 * `IrregularLine` с `PntList3D` ([KIND_POLYLINE_3D]).
 *
 * Ссылки на точки (`pntRef`, имена в `Chain`) разрешаются в [resolve], центры дуг без `Center` — в [prepare];
 * разбиение дуг ([draw]) — в `finish()`, когда известен размер сцены.
 *
 * Имена элементов — первые в [XmlNames] раздела ([NAMES]), поэтому номера [LINE], [CURVE], … общие для всех разделов.
 */
internal class CoordGeom(
  /** `Chain` берёт отметки точек `CgPoint` ([KIND_POLYLINE_3D]), если хоть у одной она не нулевая; иначе — только план. */
  private val chainElevations: Boolean = false,
) {
  private val kinds = GrowingIntArray()
  private val offsets = GrowingIntArray()
  private val data = GrowingDoubleArray()
  // ссылки на точки по имени: место в [data] (x, y) и имя; редки — список объектов
  private var refs: ArrayList<PointRef>? = null
  private val list = GrowingDoubleArray()
  // Chain с местом под отметки: номера элементов до [resolve]
  private val chains = ArrayList<Int>()
  private val point = DoubleArray(3)

  /** Число элементов. */
  val size: Int get() = kinds.size

  /** Переходных кривых `Spiral`. */
  var spirals = 0
    private set

  /** Ссылок, для которых нет точки с таким именем (после [resolve]). */
  var unresolved = 0
    private set

  fun kind(i: Int): Int = kinds[i]

  /**
   * Читает `CoordGeom` (вызов на его START, возврат на END); точки — ещё и в габариты [scene].
   * Возвращает число прочитанных элементов.
   */
  fun read(xml: XmlTokenizer, scene: LandXmlSceneBuilder): Int {
    val before = size
    val depth = xml.depth
    while (xml.nextChild(depth)) {
      when (xml.nameId) {
        LINE -> readSimple(xml, scene, KIND_LINE)
        CURVE -> readCurve(xml, scene)
        SPIRAL -> readSpiral(xml, scene)
        IRREGULAR_LINE -> readIrregular(xml, scene)
        CHAIN -> readChain(xml)
        else -> xml.skipElement()
      }
      scene.tick()
    }
    return size - before
  }

  // Line: Start, End
  private fun readSimple(xml: XmlTokenizer, scene: LandXmlSceneBuilder, kind: Int) {
    val at = begin(kind, LINE_SIZE)
    val depth = xml.depth
    while (xml.nextChild(depth)) {
      when (xml.nameId) {
        START -> readPoint(xml, scene, at)
        END -> readPoint(xml, scene, at + 2)
        else -> xml.skipElement()
      }
    }
  }

  private fun readCurve(xml: XmlTokenizer, scene: LandXmlSceneBuilder) {
    val chord = xml.attrEquals(CRV_TYPE, CHORD_TYPE)
    val rot = if (xml.attrEquals(ROT, CW)) -1.0 else 1.0
    val radius = xml.attrDouble(RADIUS)
    val length = xml.attrDouble(LENGTH)
    val delta = xml.attrDouble(DELTA)
    // большая дуга (> 180°) — нужна, только если центр придётся искать по радиусу
    val major = if (!length.isNaN() && radius.isFinite() && radius > 0) length > PI * radius else delta > 180.0
    val at = begin(if (chord) KIND_CHORD else KIND_ARC, if (chord) LINE_SIZE else ARC_SIZE)
    if (!chord) {
      data[at + ARC_ROT] = rot
      data[at + ARC_RADIUS] = radius
      data[at + ARC_MAJOR] = if (major) 1.0 else 0.0
    }
    val depth = xml.depth
    while (xml.nextChild(depth)) {
      when (xml.nameId) {
        START -> readPoint(xml, scene, at)
        END -> readPoint(xml, scene, at + 2)
        CENTER -> if (!chord) readPoint(xml, scene, at + ARC_CX, extent = false) else xml.skipElement()
        PI_POINT -> if (!chord) readPoint(xml, scene, at + ARC_PIX, extent = false) else xml.skipElement()
        else -> xml.skipElement()
      }
    }
  }

  // Spiral: атрибуты, затем Start, PI, End в любом порядке; расчёт — в [prepare]
  private fun readSpiral(xml: XmlTokenizer, scene: LandXmlSceneBuilder) {
    spirals++
    var type = Spiral.UNKNOWN
    for (t in Spiral.TYPE_BYTES.indices) {
      if (xml.attrEquals(SPI_TYPE_ATTR, Spiral.TYPE_BYTES[t])) {
        type = t
        break
      }
    }
    val at = begin(KIND_SPIRAL, SPI_SIZE)
    data[at + SPI_ROT] = if (xml.attrEquals(ROT, CW)) -1.0 else 1.0
    data[at + SPI_RADIUS_START] = xml.attrDouble(RADIUS_START)
    data[at + SPI_RADIUS_END] = xml.attrDouble(RADIUS_END)
    data[at + SPI_LENGTH] = xml.attrDouble(LENGTH)
    data[at + SPI_TYPE] = type.toDouble()
    data[at + SPI_DIR] = xml.attrDouble(DIR_START)
    val depth = xml.depth
    while (xml.nextChild(depth)) {
      when (xml.nameId) {
        START -> readPoint(xml, scene, at)
        END -> readPoint(xml, scene, at + 2)
        PI_POINT -> readPoint(xml, scene, at + SPI_PIX, extent = false)
        else -> xml.skipElement()
      }
    }
  }

  // IrregularLine: Start, End и PntList2D|3D — список уже включает начало и конец
  private fun readIrregular(xml: XmlTokenizer, scene: LandXmlSceneBuilder) {
    val ends = DoubleArray(4) { Double.NaN }
    list.clear()
    var elevations = false
    val depth = xml.depth
    while (xml.nextChild(depth)) {
      when (xml.nameId) {
        START -> readEnd(xml, ends, 0)
        END -> readEnd(xml, ends, 2)
        PNT_LIST_2D -> LandXmlCoord.readList(xml, 2, list)
        PNT_LIST_3D -> {
          LandXmlCoord.readList(xml, 3, list)
          elevations = true
        }
        else -> xml.skipElement()
      }
    }
    val n = list.size / 3
    if (n >= 2) {
      // с отметками: после пар x, y — n отметок, поэтому плановый код читает такую ломаную как обычную
      val at = begin(if (elevations) KIND_POLYLINE_3D else KIND_POLYLINE, 1 + (if (elevations) 3 else 2) * n)
      data[at] = n.toDouble()
      for (k in 0 until n) {
        val x = list[k * 3]
        val y = list[k * 3 + 1]
        data[at + 1 + 2 * k] = x
        data[at + 2 + 2 * k] = y
        if (elevations) data[at + 1 + 2 * n + k] = list[k * 3 + 2]
        scene.include(x, y, 0.0)
      }
    } else {
      // списка нет — прямая Start–End
      val at = begin(KIND_POLYLINE, 5)
      data[at] = 2.0
      for (k in 0 until 4) data[at + 1 + k] = ends[k]
      if (!ends[0].isNaN()) scene.include(ends[0], ends[1], 0.0)
      if (!ends[2].isNaN()) scene.include(ends[2], ends[3], 0.0)
    }
  }

  // конец IrregularLine — во временный массив (точка из текста; ссылки у IrregularLine не поддерживаются)
  private fun readEnd(xml: XmlTokenizer, out: DoubleArray, at: Int) {
    if (LandXmlCoord.readPoint(xml, point) >= 2) {
      out[at] = point[0]
      out[at + 1] = point[1]
    }
  }

  // Chain: имена CgPoint через пробел → ломаная, координаты — в [resolve]
  private fun readChain(xml: XmlTokenizer) {
    xml.readText()
    val names = xml.textString().split(' ', '\t', '\n', '\r').filter { it.isNotEmpty() }
    val n = names.size
    // с отметками (как у [readIrregular]): после пар x, y — n отметок
    val at = begin(KIND_POLYLINE, 1 + (if (chainElevations) 3 else 2) * n)
    data[at] = n.toDouble()
    for (k in names.indices) addRef(at + 1 + 2 * k, names[k], if (chainElevations) at + 1 + 2 * n + k else -1)
    if (chainElevations) chains.add(kinds.size - 1)
  }

  // точка элемента: текст «север восток [отметка]» или ссылка pntRef
  private fun readPoint(xml: XmlTokenizer, scene: LandXmlSceneBuilder, at: Int, extent: Boolean = true) {
    val ref = xml.attrString(PNT_REF)
    if (ref != null) {
      addRef(at, ref.trim())
      return
    }
    if (LandXmlCoord.readPoint(xml, point) < 2) return
    data[at] = point[0]
    data[at + 1] = point[1]
    if (extent) scene.include(point[0], point[1], 0.0)
  }

  private fun addRef(at: Int, name: String, zAt: Int = -1) {
    val list = refs ?: ArrayList<PointRef>().also { refs = it }
    list.add(PointRef(at, name, zAt))
  }

  // новый элемент: [count] чисел, пока NaN; возвращает начало его данных
  private fun begin(kind: Int, count: Int): Int {
    kinds.add(kind)
    val at = data.size
    offsets.add(at)
    repeat(count) { data.add(Double.NaN) }
    return at
  }

  /** Ссылки на точки по имени — координатами из [names]; ненайденные считаются в [unresolved]. */
  fun resolve(names: LandXmlPointNames, scene: LandXmlSceneBuilder) {
    val list = refs ?: return
    for (ref in list) {
      if (names.find(ref.name, point)) {
        data[ref.at] = point[0]
        data[ref.at + 1] = point[1]
        if (ref.zAt >= 0) data[ref.zAt] = point[2]
        scene.include(point[0], point[1], 0.0)
      } else {
        unresolved++
      }
    }
    refs = null
    // Chain с отметками: нулевые у всех точек — отметок в файле нет, остаётся план
    for (i in chains) {
      val at = offsets[i]
      val n = data[at].toInt()
      for (k in 0 until n) {
        val z = data[at + 1 + 2 * n + k]
        if (!z.isNaN() && z != 0.0) {
          kinds[i] = KIND_POLYLINE_3D
          break
        }
      }
    }
    chains.clear()
  }

  /**
   * После [resolve]: центры дуг без `Center` — по радиусу и направлению или по `PI`; дуга, у которой центр не найти,
   * рисуется хордой. Переходные кривые — подгонка к `Start`/`End` ([fitSpiral]); без длины или радиусов — хорда.
   * Предупреждения — в [log].
   */
  fun prepare(log: (String) -> Unit = {}) {
    for (i in 0 until size) {
      val at = offsets[i]
      when (kinds[i]) {
        KIND_ARC -> {
          if (!data[at + ARC_CX].isNaN() && !data[at + ARC_CY].isNaN()) continue
          if (!findCenter(at)) kinds[i] = KIND_CHORD
        }
        KIND_SPIRAL -> if (!fitSpiral(at, log)) kinds[i] = KIND_CHORD
      }
    }
  }

  // типы спиралей, о замене которых клотоидой уже предупредили (бит на тип, последний — неизвестный)
  private var warnedTypes = 0

  /**
   * Переходная кривая: кривизны со знаком, начальное направление и поправка конца. Направление — на `PI`
   * (касательная в начале), иначе `dirStart` (градусы от востока против часовой, как пишет Civil 3D), иначе по хорде.
   * Расчётный конец, не попавший в `End`, тянется к нему линейно по длине; расхождение больше 1 % длины — в [log].
   */
  private fun fitSpiral(at: Int, log: (String) -> Unit): Boolean {
    val length = data[at + SPI_LENGTH]
    val rs = abs(data[at + SPI_RADIUS_START])
    val re = abs(data[at + SPI_RADIUS_END])
    if ((0 until 4).any { data[at + it].isNaN() } || !(length > 0) || length.isInfinite() ||
      !(rs > 0) || !(re > 0)
    ) {
      return false
    }
    val type = data[at + SPI_TYPE].toInt()
    if (Spiral.isFallback(type)) {
      val bit = 1 shl (if (type < 0) Spiral.TYPE_NAMES.size else type)
      if (warnedTypes and bit == 0) {
        warnedTypes = warnedTypes or bit
        log(if (type < 0) "@log_spiral_untyped" else UiText.key("log_spiral_type", Spiral.TYPE_NAMES[type]))
      }
    }
    val rot = data[at + SPI_ROT]
    val k0 = rot / rs
    val k1 = rot / re
    val steps = Spiral.steps(k0, k1, length)
    data[at + SPI_K0] = k0
    data[at + SPI_K1] = k1
    data[at + SPI_STEPS] = steps.toDouble()
    val sx = data[at]
    val sy = data[at + 1]
    val chordX = data[at + 2] - sx
    val chordY = data[at + 3] - sy
    // конец при направлении 0: поворот на угол направления даёт любой другой
    point[0] = 0.0
    point[1] = 0.0
    Spiral.integrate(type, k0, k1, length, 0.0, 0.0, length, steps, point)
    val localX = point[0]
    val localY = point[1]
    val px = data[at + SPI_PIX]
    val py = data[at + SPI_PIY]
    val dir = data[at + SPI_DIR]
    val byChord = atan2(chordY, chordX) - atan2(localY, localX)
    var theta0 = when {
      !px.isNaN() && !py.isNaN() && hypot(px - sx, py - sy) > 1e-9 * length -> atan2(py - sy, px - sx)
      dir.isFinite() -> dir * PI / 180
      else -> byChord
    }
    var miss = endMiss(theta0, localX, localY, chordX, chordY)
    if (miss > MAX_MISS * length) {
      // направление из файла не сходится с концами (другие единицы, ошибка экспорта) — по хорде, если так ближе
      val chordMiss = endMiss(byChord, localX, localY, chordX, chordY)
      if (chordMiss < miss) {
        theta0 = byChord
        miss = chordMiss
      }
    }
    if (miss > MAX_MISS * length) {
      log(UiText.key("log_spiral_miss", AlignmentsSection.format(length), AlignmentsSection.format(miss)))
    }
    val c = cos(theta0)
    val s = sin(theta0)
    data[at + SPI_THETA0] = theta0
    data[at + SPI_DX] = chordX - (localX * c - localY * s)
    data[at + SPI_DY] = chordY - (localX * s + localY * c)
    return true
  }

  private fun endMiss(theta0: Double, localX: Double, localY: Double, chordX: Double, chordY: Double): Double {
    val c = cos(theta0)
    val s = sin(theta0)
    return hypot(chordX - (localX * c - localY * s), chordY - (localX * s + localY * c))
  }

  /**
   * Точка переходной кривой на расстоянии [d] от начала в [out] (x, y, направление): интеграл от начала
   * с шагом не крупнее, чем у подгонки (на всей длине — та же сетка, конец ровно на `End`), плюс доля поправки конца.
   */
  private fun spiralAt(at: Int, d: Double, out: DoubleArray) {
    val length = data[at + SPI_LENGTH]
    val type = data[at + SPI_TYPE].toInt()
    val k0 = data[at + SPI_K0]
    val k1 = data[at + SPI_K1]
    val theta0 = data[at + SPI_THETA0]
    val n = ceil(data[at + SPI_STEPS] * d / length).toInt().coerceAtLeast(2)
    val steps = n + (n and 1)
    val t = d / length
    out[0] = data[at] + data[at + SPI_DX] * t
    out[1] = data[at + 1] + data[at + SPI_DY] * t
    Spiral.integrate(type, k0, k1, length, theta0, 0.0, d, steps, out)
    out[2] = normalize(theta0 + Spiral.turn(type, k0, k1, length, d))
  }

  private fun findCenter(at: Int): Boolean {
    val sx = data[at]
    val sy = data[at + 1]
    val ex = data[at + 2]
    val ey = data[at + 3]
    val dx = ex - sx
    val dy = ey - sy
    val c = hypot(dx, dy)
    if (c.isNaN() || c == 0.0) return false
    val r = data[at + ARC_RADIUS]
    if (r.isFinite() && r > 0) {
      // центр на серединном перпендикуляре; у малой дуги против часовой — слева от хорды
      val h = sqrt((r * r - c * c / 4).coerceAtLeast(0.0))
      val side = data[at + ARC_ROT] * (if (data[at + ARC_MAJOR] == 1.0) -1.0 else 1.0)
      data[at + ARC_CX] = (sx + ex) / 2 - side * h * dy / c
      data[at + ARC_CY] = (sy + ey) / 2 + side * h * dx / c
      return true
    }
    // по PI: пересечение перпендикуляров к касательным в Start и End
    val px = data[at + ARC_PIX]
    val py = data[at + ARC_PIY]
    if (px.isNaN() || py.isNaN()) return false
    val nx = -(py - sy)
    val ny = px - sx
    val tx = px - ex
    val ty = py - ey
    val denominator = nx * tx + ny * ty
    if (abs(denominator) < 1e-12 * (nx * nx + ny * ny + tx * tx + ty * ty)) return false
    val t = -((sx - ex) * tx + (sy - ey) * ty) / denominator
    data[at + ARC_CX] = sx + t * nx
    data[at + ARC_CY] = sy + t * ny
    return true
  }

  /** Элемент [i] можно рисовать и мерить: все нужные точки известны (у ломаной — хотя бы две). */
  fun isValid(i: Int): Boolean {
    val at = offsets[i]
    return when (kinds[i]) {
      KIND_POLYLINE, KIND_POLYLINE_3D -> {
        var known = 0
        val n = data[at].toInt()
        for (k in 0 until n) if (!data[at + 1 + 2 * k].isNaN()) known++
        known >= 2
      }
      KIND_ARC -> (0 until 4).none { data[at + it].isNaN() } && !data[at + ARC_CX].isNaN() && !data[at + ARC_CY].isNaN()
      else -> (0 until 4).none { data[at + it].isNaN() }
    }
  }

  /** Угол дуги [i] со знаком (+ против часовой) по `Start`/`Center`/`End`/`rot`. */
  fun sweep(i: Int): Double {
    val at = offsets[i]
    val cx = data[at + ARC_CX]
    val cy = data[at + ARC_CY]
    val a0 = atan2(data[at + 1] - cy, data[at] - cx)
    val a1 = atan2(data[at + 3] - cy, data[at + 2] - cx)
    var d = a1 - a0
    if (data[at + ARC_ROT] > 0) {
      while (d <= 0) d += 2 * PI
    } else {
      while (d >= 0) d -= 2 * PI
    }
    return d
  }

  /** Радиус дуги [i] по геометрии (Start — Center). */
  fun radius(i: Int): Double {
    val at = offsets[i]
    return hypot(data[at] - data[at + ARC_CX], data[at + 1] - data[at + ARC_CY])
  }

  /** Длина элемента [i] по геометрии; у неполного элемента — 0. */
  fun length(i: Int): Double {
    if (!isValid(i)) return 0.0
    val at = offsets[i]
    return when (kinds[i]) {
      KIND_ARC -> radius(i) * abs(sweep(i))
      KIND_SPIRAL -> data[at + SPI_LENGTH]
      KIND_POLYLINE, KIND_POLYLINE_3D -> {
        var sum = 0.0
        var px = Double.NaN
        var py = Double.NaN
        val n = data[at].toInt()
        for (k in 0 until n) {
          val x = data[at + 1 + 2 * k]
          val y = data[at + 2 + 2 * k]
          if (x.isNaN() || y.isNaN()) continue
          if (!px.isNaN()) sum += hypot(x - px, y - py)
          px = x
          py = y
        }
        sum
      }
      else -> hypot(data[at + 2] - data[at], data[at + 3] - data[at + 1])
    }
  }

  /**
   * Точка на элементе [i] на расстоянии [distance] от его начала (обрезается до длины [length]) в [out]:
   * x, y, направление (радианы от оси x — востока — против часовой).
   */
  fun pointAt(i: Int, distance: Double, length: Double, out: DoubleArray) {
    val at = offsets[i]
    val d = distance.coerceIn(0.0, length)
    when (kinds[i]) {
      KIND_ARC -> {
        val cx = data[at + ARC_CX]
        val cy = data[at + ARC_CY]
        val r = radius(i)
        val sweep = sweep(i)
        val a = atan2(data[at + 1] - cy, data[at] - cx) + (if (length > 0) sweep * d / length else 0.0)
        out[0] = cx + r * cos(a)
        out[1] = cy + r * sin(a)
        out[2] = normalize(a + (if (sweep > 0) PI / 2 else -PI / 2))
      }
      KIND_SPIRAL -> spiralAt(at, d, out)
      KIND_POLYLINE, KIND_POLYLINE_3D -> {
        val n = data[at].toInt()
        var left = d
        var px = Double.NaN
        var py = Double.NaN
        for (k in 0 until n) {
          val x = data[at + 1 + 2 * k]
          val y = data[at + 2 + 2 * k]
          if (x.isNaN() || y.isNaN()) continue
          if (!px.isNaN()) {
            val step = hypot(x - px, y - py)
            if (step > 0) {
              out[2] = atan2(y - py, x - px)
              if (left <= step) {
                out[0] = px + (x - px) * left / step
                out[1] = py + (y - py) * left / step
                return
              }
              left -= step
            }
          }
          px = x
          py = y
        }
        out[0] = px
        out[1] = py
      }
      else -> {
        val sx = data[at]
        val sy = data[at + 1]
        val dx = data[at + 2] - sx
        val dy = data[at + 3] - sy
        val t = if (length > 0) d / length else 0.0
        out[0] = sx + dx * t
        out[1] = sy + dy * t
        out[2] = atan2(dy, dx)
      }
    }
  }

  /**
   * Рисует элемент [i] отрезками в [lines] (z = 0, план; с [elevations] у [KIND_POLYLINE_3D] — свои отметки);
   * дуга — по допуску от [sceneSize], как дуги DXF.
   * Вершины дуги — поворотом на постоянный угол, последняя — ровно `End`. Возвращает число отрезков.
   */
  fun draw(i: Int, lines: LandXmlSceneBuilder.Lines, sceneSize: Double, objectId: Int, elevations: Boolean = false): Int {
    if (!isValid(i)) return 0
    val at = offsets[i]
    when (kinds[i]) {
      KIND_ARC -> {
        val cx = data[at + ARC_CX]
        val cy = data[at + ARC_CY]
        val ex = data[at + 2]
        val ey = data[at + 3]
        val sweep = sweep(i)
        val steps = arcSteps(sweep, radius(i), sceneSize)
        val c = cos(sweep / steps)
        val s = sin(sweep / steps)
        var vx = data[at] - cx
        var vy = data[at + 1] - cy
        var px = data[at]
        var py = data[at + 1]
        for (k in 1 until steps) {
          val nx = vx * c - vy * s
          vy = vx * s + vy * c
          vx = nx
          val x = cx + vx
          val y = cy + vy
          lines.add(px, py, 0.0, x, y, 0.0, objectId)
          px = x
          py = y
        }
        lines.add(px, py, 0.0, ex, ey, 0.0, objectId)
        return steps
      }
      KIND_SPIRAL -> return drawSpiral(at, lines, sceneSize, objectId)
      KIND_POLYLINE, KIND_POLYLINE_3D -> {
        val n = data[at].toInt()
        val withZ = elevations && kinds[i] == KIND_POLYLINE_3D
        var count = 0
        var px = Double.NaN
        var py = Double.NaN
        var pz = 0.0
        for (k in 0 until n) {
          val x = data[at + 1 + 2 * k]
          val y = data[at + 2 + 2 * k]
          if (x.isNaN() || y.isNaN()) continue
          val z = if (withZ) data[at + 1 + 2 * n + k] else 0.0
          if (!px.isNaN()) {
            lines.add(px, py, pz, x, y, z, objectId)
            count++
          }
          px = x
          py = y
          pz = z
        }
        return count
      }
      else -> {
        lines.add(data[at], data[at + 1], 0.0, data[at + 2], data[at + 3], 0.0, objectId)
        return 1
      }
    }
  }

  /**
   * Переходная кривая отрезками: шаг — как у дуги с наибольшей кривизной кривой (допуск от [sceneSize]),
   * между вершинами — интеграл Симпсона от предыдущей; последняя вершина — ровно `End`.
   */
  private fun drawSpiral(at: Int, lines: LandXmlSceneBuilder.Lines, sceneSize: Double, objectId: Int): Int {
    val length = data[at + SPI_LENGTH]
    val type = data[at + SPI_TYPE].toInt()
    val k0 = data[at + SPI_K0]
    val k1 = data[at + SPI_K1]
    val theta0 = data[at + SPI_THETA0]
    val k = maxOf(abs(k0), abs(k1))
    val count = arcSteps(length * k, if (k > 0) 1 / k else Double.POSITIVE_INFINITY, sceneSize)
    val sub = ceil(data[at + SPI_STEPS] / count).toInt().coerceAtLeast(1) * 2
    val dx = data[at + SPI_DX] / count
    val dy = data[at + SPI_DY] / count
    // ход по самой кривой без поправки — в point, вершина — с поправкой
    point[0] = data[at]
    point[1] = data[at + 1]
    var px = point[0]
    var py = point[1]
    for (step in 1 until count) {
      Spiral.integrate(type, k0, k1, length, theta0, length * (step - 1) / count, length * step / count, sub, point)
      val x = point[0] + dx * step
      val y = point[1] + dy * step
      lines.add(px, py, 0.0, x, y, 0.0, objectId)
      px = x
      py = y
    }
    lines.add(px, py, 0.0, data[at + 2], data[at + 3], 0.0, objectId)
    return count
  }

  /** Начало элемента [i] в [out]: x, y (и z, если в [out] есть место; без отметок — 0); false — неизвестно. */
  fun start(i: Int, out: DoubleArray): Boolean {
    val at = offsets[i]
    val first = if (isPolyline(i)) {
      val n = data[at].toInt()
      (0 until n).firstOrNull { !data[at + 1 + 2 * it].isNaN() }?.let { at + 1 + 2 * it } ?: return false
    } else {
      at
    }
    if (data[first].isNaN() || data[first + 1].isNaN()) return false
    out[0] = data[first]
    out[1] = data[first + 1]
    if (out.size > 2) out[2] = elevation(i, first)
    return true
  }

  /** Конец элемента [i] в [out]: x, y (и z, если в [out] есть место; без отметок — 0); false — неизвестно. */
  fun end(i: Int, out: DoubleArray): Boolean {
    val at = offsets[i]
    val last = if (isPolyline(i)) {
      val n = data[at].toInt()
      (n - 1 downTo 0).firstOrNull { !data[at + 1 + 2 * it].isNaN() }?.let { at + 1 + 2 * it } ?: return false
    } else {
      at + 2
    }
    if (data[last].isNaN() || data[last + 1].isNaN()) return false
    out[0] = data[last]
    out[1] = data[last + 1]
    if (out.size > 2) out[2] = elevation(i, last)
    return true
  }

  // отметка вершины ломаной с отметками, у которой x лежит в data[[xAt]]; у прочих — 0
  private fun elevation(i: Int, xAt: Int): Double {
    if (kinds[i] != KIND_POLYLINE_3D) return 0.0
    val at = offsets[i]
    val n = data[at].toInt()
    return data[at + 1 + 2 * n + (xAt - at - 1) / 2]
  }

  /** У элемента [i] есть отметки ([KIND_POLYLINE_3D]). */
  fun hasElevations(i: Int): Boolean = kinds[i] == KIND_POLYLINE_3D

  private fun isPolyline(i: Int) = kinds[i] == KIND_POLYLINE || kinds[i] == KIND_POLYLINE_3D

  /**
   * Расширяет габариты [box] (minX, minY, maxX, maxY) на элемент [i]: вершины, у дуги — ещё точки через каждые
   * 1/16 угла. Для места подписи, а не для отрисовки.
   */
  fun extend(i: Int, box: DoubleArray) {
    if (!isValid(i)) return
    val at = offsets[i]
    fun add(x: Double, y: Double) {
      if (x.isNaN() || y.isNaN()) return
      if (x < box[0]) box[0] = x
      if (y < box[1]) box[1] = y
      if (x > box[2]) box[2] = x
      if (y > box[3]) box[3] = y
    }
    when (kinds[i]) {
      KIND_POLYLINE, KIND_POLYLINE_3D -> {
        val n = data[at].toInt()
        for (k in 0 until n) add(data[at + 1 + 2 * k], data[at + 2 + 2 * k])
      }
      KIND_ARC -> {
        val length = length(i)
        val p = DoubleArray(3)
        for (k in 0..16) {
          pointAt(i, length * k / 16, length, p)
          add(p[0], p[1])
        }
      }
      else -> {
        add(data[at], data[at + 1])
        add(data[at + 2], data[at + 3])
      }
    }
  }

  private class PointRef(val at: Int, val name: String, val zAt: Int)

  companion object {
    /** Имена элементов `CoordGeom` — в начале [XmlNames] раздела, который его читает. */
    val NAMES = arrayOf(
      "CoordGeom", "Line", "IrregularLine", "Curve", "Spiral", "Chain",
      "Start", "End", "Center", "PI", "PntList2D", "PntList3D",
    )
    const val COORD_GEOM = 0
    const val LINE = 1
    const val IRREGULAR_LINE = 2
    const val CURVE = 3
    const val SPIRAL = 4
    const val CHAIN = 5
    const val START = 6
    const val END = 7
    const val CENTER = 8
    const val PI_POINT = 9
    const val PNT_LIST_2D = 10
    const val PNT_LIST_3D = 11

    // виды элементов
    const val KIND_LINE = 0
    const val KIND_ARC = 1
    /** `Curve crvType="chord"` и дуга без центра — хорда Start–End. */
    const val KIND_CHORD = 2
    /** Переходная кривая `Spiral` ([Spiral]); без длины или радиусов — хорда Start–End. */
    const val KIND_SPIRAL = 3
    /** `IrregularLine` и `Chain`: число точек, затем x, y. */
    const val KIND_POLYLINE = 4
    /** `IrregularLine` с `PntList3D`: как [KIND_POLYLINE], затем отметки вершин. */
    const val KIND_POLYLINE_3D = 5

    // раскладка чисел: прямая — sx sy ex ey; дуга — то же, затем центр, rot (+1 ccw, −1 cw), радиус из файла,
    // признак большой дуги, PI
    private const val LINE_SIZE = 4
    private const val ARC_CX = 4
    private const val ARC_CY = 5
    private const val ARC_ROT = 6
    private const val ARC_RADIUS = 7
    private const val ARC_MAJOR = 8
    private const val ARC_PIX = 9
    private const val ARC_PIY = 10
    private const val ARC_SIZE = 11
    // спираль: sx sy ex ey, rot, радиусы, длина, номер типа, dirStart, PI; после [prepare] — кривизны со знаком,
    // начальное направление, шагов Симпсона, поправка конца
    private const val SPI_ROT = 4
    private const val SPI_RADIUS_START = 5
    private const val SPI_RADIUS_END = 6
    private const val SPI_LENGTH = 7
    private const val SPI_TYPE = 8
    private const val SPI_DIR = 9
    private const val SPI_PIX = 10
    private const val SPI_PIY = 11
    private const val SPI_K0 = 12
    private const val SPI_K1 = 13
    private const val SPI_THETA0 = 14
    private const val SPI_STEPS = 15
    private const val SPI_DX = 16
    private const val SPI_DY = 17
    private const val SPI_SIZE = 18
    // допустимое расхождение расчётного конца спирали с End — доля длины
    private const val MAX_MISS = 0.01

    private val PNT_REF = "pntRef".encodeToByteArray()
    private val CRV_TYPE = "crvType".encodeToByteArray()
    private val CHORD_TYPE = "chord".encodeToByteArray()
    private val ROT = "rot".encodeToByteArray()
    private val CW = "cw".encodeToByteArray()
    private val RADIUS = "radius".encodeToByteArray()
    private val LENGTH = "length".encodeToByteArray()
    private val DELTA = "delta".encodeToByteArray()
    private val SPI_TYPE_ATTR = "spiType".encodeToByteArray()
    private val RADIUS_START = "radiusStart".encodeToByteArray()
    private val RADIUS_END = "radiusEnd".encodeToByteArray()
    private val DIR_START = "dirStart".encodeToByteArray()

    // шагов одной дуги не больше: бесконечная кривизна (радиус ~0) не должна давать миллиарды шагов
    const val MAX_ARC_STEPS = 100_000

    /** Число шагов дуги по допуску от размера сцены ([ArcSteps]), не больше [MAX_ARC_STEPS]. */
    fun arcSteps(sweep: Double, radius: Double, sceneSize: Double): Int =
      ArcSteps.steps(sweep, radius, sceneSize, MAX_ARC_STEPS)

    /** Размер сцены для допуска дуг — диагональ габаритов. */
    fun sceneSize(scene: LandXmlSceneBuilder): Double {
      if (scene.isEmpty) return 0.0
      return ArcSteps.sceneSize(scene.minX, scene.minY, scene.minZ, scene.maxX, scene.maxY, scene.maxZ)
    }

    /** Угол в (−π, π]. */
    fun normalize(angle: Double): Double {
      var a = angle
      while (a > PI) a -= 2 * PI
      while (a <= -PI) a += 2 * PI
      return a
    }
  }
}
