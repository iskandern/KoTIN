package dev.iskandern.kotin.dxf

import dev.iskandern.kotin.model.GrowingDoubleArray
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Кривая поверхность грани тела в своих параметрах (u, v); координаты — мировые до вставки блока.
 * Грань такой поверхности строит [ParamFace]: петли переводятся в (u, v), там режутся по шву и сетке, вершины
 * возвращаются на поверхность.
 */
interface AcisSurface {
  /** Период по u и по v (угол — 2π); 0 — не периодична. */
  val periodU: Double
  val periodV: Double

  /** Треугольник против часовой в (u, v) смотрит против нормали грани (с учётом sense грани). */
  val flip: Boolean

  /** (u, v) точки поверхности в [out]; в вырожденной точке (вершина конуса, полюс сферы) u = NaN. */
  fun param(x: Double, y: Double, z: Double, out: DoubleArray)

  /** Точка (x, y, z) параметров (u, v) в [out]. */
  fun point(u: Double, v: Double, out: DoubleArray)

  /** Значения v, где поверхность стягивается в точку при любом u (вершина конуса, полюса сферы). */
  fun poles(): DoubleArray

  /** Область (u0, v0, u1, v1) грани без петель — замкнутая поверхность целиком (сфера, тор); null — неограниченная. */
  fun domain(): DoubleArray?

  /**
   * Шаг сетки (du, dv) для грани с границами [bounds] (u0, v0, u1, v1); 0 — по этой оси не делить (образующая —
   * прямая). [perTurn] — шагов на оборот для окружности радиуса r (как у дуг). Затем — примерная длина на
   * поверхности единицы u и единицы v (su, sv): по ней выбираются треугольники, близкие к равносторонним.
   */
  fun grid(bounds: DoubleArray, perTurn: (Double) -> Int): DoubleArray
}

/**
 * `cone-surface`: основание — эллипс (центр, единичная ось, большая полуось, отношение осей), sin и cos полуугла.
 * u — угол на эллипсе основания, v — высота вдоль оси; на высоте v эллипс растянут в 1 + v·tg/|major| раз
 * (tg = sin/cos). Цилиндр — sin = 0. Нормаль поверхности наружу от оси, при cos < 0 — к оси.
 */
class AcisCone(
  private val center: DoubleArray, private val axis: DoubleArray, private val major: DoubleArray,
  ratio: Double, sin: Double, cos: Double, reversed: Boolean,
) : AcisSurface {
  private val radius = sqrt(major[0] * major[0] + major[1] * major[1] + major[2] * major[2])
  private val slope = sin / cos
  // малая полуось: ось × большая · ratio
  private val minor = doubleArrayOf(
    (axis[1] * major[2] - axis[2] * major[1]) * ratio,
    (axis[2] * major[0] - axis[0] * major[2]) * ratio,
    (axis[0] * major[1] - axis[1] * major[0]) * ratio,
  )
  private val minor2 = minor[0] * minor[0] + minor[1] * minor[1] + minor[2] * minor[2]
  private val widest = radius * maxOf(1.0, ratio)

  override val periodU = 2 * PI
  override val periodV = 0.0

  // ∂u × ∂v смотрит от оси; нормаль поверхности при cos < 0 — к оси, у грани reversed — обратно поверхности
  override val flip = (cos < 0) != reversed

  override fun param(x: Double, y: Double, z: Double, out: DoubleArray) {
    val dx = x - center[0]
    val dy = y - center[1]
    val dz = z - center[2]
    val h = dx * axis[0] + dy * axis[1] + dz * axis[2]
    val k = 1 + h * slope / radius
    val a = (dx * major[0] + dy * major[1] + dz * major[2]) / (radius * radius)
    val b = (dx * minor[0] + dy * minor[1] + dz * minor[2]) / minor2
    out[0] = if (hypot(a, b) <= 1e-9 * maxOf(1.0, abs(k))) Double.NaN else if (k < 0) atan2(-b, -a) else atan2(b, a)
    out[1] = h
  }

  override fun point(u: Double, v: Double, out: DoubleArray) {
    val k = 1 + v * slope / radius
    val c = cos(u) * k
    val s = sin(u) * k
    for (i in 0..2) out[i] = center[i] + axis[i] * v + major[i] * c + minor[i] * s
  }

  override fun poles(): DoubleArray = if (slope == 0.0) DoubleArray(0) else doubleArrayOf(-radius / slope)

  override fun domain(): DoubleArray? = null

  override fun grid(bounds: DoubleArray, perTurn: (Double) -> Int): DoubleArray {
    // самый широкий эллипс грани: шаг по углу — как у дуги этого радиуса
    val r = widest * maxOf(abs(1 + bounds[1] * slope / radius), abs(1 + bounds[3] * slope / radius))
    val du = 2 * PI / perTurn(r)
    // у конуса ячейки примерно квадратные: прогиб ячейки — порядка прогиба дуги; у цилиндра образующая прямая
    val dv = if (slope == 0.0) 0.0 else r * du / abs(slope)
    return doubleArrayOf(du, dv, r, sqrt(1 + slope * slope))
  }
}

/**
 * Предел шагов сетки на оборот у тора и сферы: треугольников там — квадрат шагов, а при 64 прогиб ячейки уже
 * около 0.1 % радиуса. Петли граней по-прежнему идут с шагом дуг.
 */
private const val DOUBLE_CURVED_STEPS = 64

/**
 * `torus-surface`: центр, единичная ось, большой радиус [major] (до окружности центров трубки), малый [minor] (знак —
 * нормаль: минус — к окружности центров), направление u = 0 ([origin], ⟂ оси). u — угол вокруг оси, v — угол
 * по трубке от экватора снаружи к оси: точка — центр + (R + r·cos v)·(cos u·x + sin u·y) + r·sin v·ось.
 */
class AcisTorus(
  private val center: DoubleArray, private val axis: DoubleArray, private val major: Double, minor: Double,
  origin: DoubleArray, reversed: Boolean,
) : AcisSurface {
  private val r = abs(minor)
  private val x = origin
  // y = ось × x
  private val y = doubleArrayOf(
    axis[1] * x[2] - axis[2] * x[1],
    axis[2] * x[0] - axis[0] * x[2],
    axis[0] * x[1] - axis[1] * x[0],
  )

  override val periodU = 2 * PI
  override val periodV = 2 * PI

  // ∂u × ∂v смотрит от окружности центров трубки
  override val flip = (minor < 0) != reversed

  override fun param(x: Double, y: Double, z: Double, out: DoubleArray) {
    val dx = x - center[0]
    val dy = y - center[1]
    val dz = z - center[2]
    val a = dx * this.x[0] + dy * this.x[1] + dz * this.x[2]
    val b = dx * this.y[0] + dy * this.y[1] + dz * this.y[2]
    val h = dx * axis[0] + dy * axis[1] + dz * axis[2]
    val rho = hypot(a, b)
    // на оси (тор без отверстия) угол u не определён
    out[0] = if (rho <= 1e-9 * (major + r)) Double.NaN else atan2(b, a)
    out[1] = atan2(h, rho - major)
  }

  override fun point(u: Double, v: Double, out: DoubleArray) {
    val k = major + r * cos(v)
    val c = cos(u) * k
    val s = sin(u) * k
    val h = r * sin(v)
    for (i in 0..2) out[i] = center[i] + x[i] * c + y[i] * s + axis[i] * h
  }

  override fun poles(): DoubleArray = DoubleArray(0)

  override fun domain(): DoubleArray = doubleArrayOf(-PI, -PI, PI, PI)

  override fun grid(bounds: DoubleArray, perTurn: (Double) -> Int): DoubleArray {
    // шаг по u — как у дуги самой широкой окружности (экватор снаружи), по v — как у окружности трубки
    val du = 2 * PI / minOf(perTurn(major + r), DOUBLE_CURVED_STEPS)
    val dv = 2 * PI / minOf(perTurn(r), DOUBLE_CURVED_STEPS)
    return doubleArrayOf(du, dv, major + r, r)
  }
}

/**
 * `sphere-surface`: центр, радиус (минус — нормаль к центру), направление u = 0 ([origin]) и полюсная ось [pole]
 * (единичные, взаимно ⟂). u — долгота, v — широта от −π/2 до π/2; полюса — v = ±π/2.
 */
class AcisSphere(
  private val center: DoubleArray, radius: Double, origin: DoubleArray, private val pole: DoubleArray, reversed: Boolean,
) : AcisSurface {
  private val r = abs(radius)
  private val x = origin
  // y = полюс × x
  private val y = doubleArrayOf(
    pole[1] * x[2] - pole[2] * x[1],
    pole[2] * x[0] - pole[0] * x[2],
    pole[0] * x[1] - pole[1] * x[0],
  )

  override val periodU = 2 * PI
  override val periodV = 0.0

  // ∂u × ∂v смотрит от центра
  override val flip = (radius < 0) != reversed

  override fun param(x: Double, y: Double, z: Double, out: DoubleArray) {
    val dx = x - center[0]
    val dy = y - center[1]
    val dz = z - center[2]
    val a = dx * this.x[0] + dy * this.x[1] + dz * this.x[2]
    val b = dx * this.y[0] + dy * this.y[1] + dz * this.y[2]
    val h = dx * pole[0] + dy * pole[1] + dz * pole[2]
    val rho = hypot(a, b)
    out[0] = if (rho <= 1e-9 * r) Double.NaN else atan2(b, a)
    out[1] = atan2(h, rho)
  }

  override fun point(u: Double, v: Double, out: DoubleArray) {
    // в полюсе — ровно точка полюса: треугольники с двумя вершинами там отбрасываются как вырожденные
    val k = if (abs(v) >= PI / 2) 0.0 else r * cos(v)
    val h = if (v >= PI / 2) r else if (v <= -PI / 2) -r else r * sin(v)
    val c = cos(u) * k
    val s = sin(u) * k
    for (i in 0..2) out[i] = center[i] + x[i] * c + y[i] * s + pole[i] * h
  }

  override fun poles(): DoubleArray = doubleArrayOf(-PI / 2, PI / 2)

  override fun domain(): DoubleArray = doubleArrayOf(-PI, -PI / 2, PI, PI / 2)

  override fun grid(bounds: DoubleArray, perTurn: (Double) -> Int): DoubleArray {
    val d = 2 * PI / minOf(perTurn(r), DOUBLE_CURVED_STEPS)
    return doubleArrayOf(d, d, r, r)
  }
}

/**
 * Триангуляция грани кривой поверхности. Петли (точки x, y, z на рёбрах) переводятся в (u, v) и разворачиваются по
 * периоду. Петля, обходящая поверхность по кругу (окружность торца цилиндра), разрезается по шву: две такие петли
 * образуют полосу, одна — полосу до полюса ([AcisSurface.poles]). Сетку по контурам строит [Mesher] с шагом
 * [AcisSurface.grid], вершины возвращаются на поверхность. В каркас идут только отрезки петель.
 * Общий код для всех кривых поверхностей: новой поверхности достаточно реализовать [AcisSurface].
 */
internal object ParamFace {

  // контур в (u, v): точки парами и признак «отрезок i → i+1 — ребро тела»
  private class Ring(val uv: DoubleArray, val edge: BooleanArray) {
    val size get() = edge.size
    var windU = 0
    var windV = 0
  }

  /**
   * [loops] — петли x, y, z без повтора первой точки. Треугольники отдаются в [emit] (9 координат, биты видимых рёбер
   * как у `SceneMesh.edgeMask`), обход — по нормали грани. Возвращает число треугольников; 0 — грань не построена.
   */
  fun triangulate(loops: List<DoubleArray>, surface: AcisSurface, perTurn: (Double) -> Int, emit: (DoubleArray, Int) -> Unit): Int {
    val rings = ArrayList<Ring>()
    for (loop in loops) rings.add(toParam(loop, surface) ?: continue)
    val contours = contours(rings, surface) ?: return 0
    if (contours.isEmpty()) return 0
    return Mesher(contours, surface, perTurn, emit).run()
  }

  // петля → контур в (u, v), развёрнутый по периоду; петля-точка (вершина конуса) — null
  private fun toParam(p: DoubleArray, surface: AcisSurface): Ring? {
    val n = p.size / 3
    if (n < 2) return null
    val uv = DoubleArray(2)
    val raw = DoubleArray(n * 2)
    var regular = 0
    for (i in 0 until n) {
      surface.param(p[i * 3], p[i * 3 + 1], p[i * 3 + 2], uv)
      raw[i * 2] = uv[0]
      raw[i * 2 + 1] = uv[1]
      if (!uv[0].isNaN()) regular++
    }
    if (regular == 0) return null
    // точка в полюсе — отрезок вдоль полюса от u предыдущей точки до u следующей
    val out = GrowingDoubleArray()
    val edges = ArrayList<Boolean>(n + 2)
    for (i in 0 until n) {
      if (!raw[i * 2].isNaN()) {
        out.add(raw[i * 2]); out.add(raw[i * 2 + 1]); edges.add(true)
        continue
      }
      var a = (i + n - 1) % n
      while (raw[a * 2].isNaN()) a = (a + n - 1) % n
      var b = (i + 1) % n
      while (raw[b * 2].isNaN()) b = (b + 1) % n
      out.add(raw[a * 2]); out.add(raw[i * 2 + 1]); edges.add(false)
      out.add(raw[b * 2]); out.add(raw[i * 2 + 1]); edges.add(true)
    }
    val ring = Ring(out.toArray(), edges.toBooleanArray())
    ring.windU = unwrap(ring, 0, surface.periodU)
    ring.windV = unwrap(ring, 1, surface.periodV)
    return ring
  }

  // разворачивает ось [axis] без скачков больше полупериода; возвращает число оборотов петли
  private fun unwrap(ring: Ring, axis: Int, period: Double): Int {
    if (period <= 0.0) return 0
    val uv = ring.uv
    val n = ring.size
    for (i in 1 until n) {
      var d = uv[i * 2 + axis] - uv[(i - 1) * 2 + axis]
      d -= period * round(d / period)
      uv[i * 2 + axis] = uv[(i - 1) * 2 + axis] + d
    }
    var d = uv[axis] - uv[(n - 1) * 2 + axis]
    d -= period * round(d / period)
    return round((uv[(n - 1) * 2 + axis] + d - uv[axis]) / period).toInt()
  }

  private fun bounds(ring: Ring, axis: Int): DoubleArray {
    var min = Double.MAX_VALUE
    var max = -Double.MAX_VALUE
    for (i in 0 until ring.size) {
      val c = ring.uv[i * 2 + axis]
      if (c < min) min = c
      if (c > max) max = c
    }
    return doubleArrayOf(min, max)
  }

  private fun shift(ring: Ring, axis: Int, by: Double) {
    if (by == 0.0) return
    for (i in 0 until ring.size) ring.uv[i * 2 + axis] += by
  }

  /** Контуры для триангуляции: полоса из петель-обходов и остальные петли в одном окне периода; null — не умеем. */
  private fun contours(rings: List<Ring>, surface: AcisSurface): List<Ring>? {
    if (rings.any { abs(it.windU) > 1 || abs(it.windV) > 1 || (it.windU != 0 && it.windV != 0) }) return null
    val windingU = rings.filter { it.windU != 0 }
    val windingV = rings.filter { it.windV != 0 }
    if (windingU.isNotEmpty() && windingV.isNotEmpty()) return null
    val axis = if (windingV.isNotEmpty()) 1 else 0
    val winding = if (axis == 1) windingV else windingU
    val plain = rings.filter { it.windU == 0 && it.windV == 0 }

    if (winding.isEmpty()) {
      if (rings.isEmpty()) {
        val d = surface.domain() ?: return null
        return listOf(Ring(doubleArrayOf(d[0], d[1], d[2], d[1], d[2], d[3], d[0], d[3]), BooleanArray(4)))
      }
      // отверстия — в то же окно периода, что и первая петля
      val first = plain[0]
      for (ring in plain.drop(1)) for (a in 0..1) {
        val period = if (a == 0) surface.periodU else surface.periodV
        if (period <= 0.0) continue
        val base = bounds(first, a)[0]
        val b = bounds(ring, a)
        shift(ring, a, -period * floor(((b[0] + b[1]) / 2 - base) / period))
      }
      return plain
    }
    if (winding.size > 2) return null
    val period = if (axis == 0) surface.periodU else surface.periodV
    // шов — посередине самого широкого просвета между обычными петлями (без них — в первой точке петли-обхода)
    val seam = if (plain.isEmpty()) winding[0].uv[axis] else gapMiddle(plain.map { bounds(it, axis) }, period) ?: return null
    val strips = winding.map { cut(it, axis, seam, period, if (axis == 0) it.windU else it.windV) ?: return null }
    val end = seam + period
    val other = 1 - axis
    val uv = GrowingDoubleArray()
    val edges = ArrayList<Boolean>()
    fun add(ring: Ring, reversed: Boolean) {
      val n = ring.size
      for (k in 0 until n) {
        val i = if (reversed) n - 1 - k else k
        uv.add(ring.uv[i * 2]); uv.add(ring.uv[i * 2 + 1])
        // у развёрнутой полосы отрезок k → k+1 — это отрезок i-1 → i исходной
        edges.add(if (k == n - 1) false else if (reversed) ring.edge[i - 1] else ring.edge[i])
      }
    }
    add(strips[0], reversed = false)
    if (strips.size == 2) {
      add(strips[1], reversed = true)
    } else {
      // одна петля-обход: замыкаем через полюс с той стороны, где лежит грань
      if (axis != 0) return null
      val poles = surface.poles()
      val mean = bounds(strips[0], 1).average()
      val pole = when (poles.size) {
        0 -> return null
        1 -> poles[0]
        else -> {
          // грань слева от петли; обход по росту u после разреза совпадает с исходным, если windU > 0
          val above = (winding[0].windU > 0) != surface.flip
          poles.filter { if (above) it > mean else it < mean }.let { if (it.size == 1) it[0] else return null }
        }
      }
      val p = DoubleArray(2)
      p[axis] = end; p[other] = pole
      uv.add(p[0]); uv.add(p[1]); edges.add(false)
      p[axis] = seam
      uv.add(p[0]); uv.add(p[1]); edges.add(false)
    }
    // обычные петли — в окно шва
    for (ring in plain) {
      val b = bounds(ring, axis)
      shift(ring, axis, -period * floor((b[0] - seam) / period))
    }
    return listOf(Ring(uv.toArray(), edges.toBooleanArray())) + plain
  }

  // середина самого широкого промежутка, не накрытого отрезками [spans] (min, max) на окружности длины [period]
  private fun gapMiddle(spans: List<DoubleArray>, period: Double): Double? {
    val sorted = spans.map { s ->
      val start = (s[0] % period + period) % period
      doubleArrayOf(start, start + (s[1] - s[0]))
    }.sortedBy { it[0] }
    var best = 0.0
    var middle: Double? = null
    var end = sorted.maxOf { it[1] } - period
    for (s in sorted) {
      if (s[0] - end > best) { best = s[0] - end; middle = (s[0] + end) / 2 }
      end = maxOf(end, s[1])
    }
    return middle
  }

  /**
   * Петля-обход → ломаная от [seam] до seam + период по оси [axis] (по росту координаты): разрез в первом пересечении
   * шва, точки разреза — точно на шве. [wind] — направление обхода петли.
   */
  private fun cut(ring: Ring, axis: Int, seam: Double, period: Double, wind: Int): Ring? {
    val n = ring.size
    val uv = ring.uv
    val other = 1 - axis
    // ищем отрезок i → i+1 (замыкающий — со сдвигом на оборот), который пересекает seam + k·период
    for (i in 0 until n) {
      val j = (i + 1) % n
      val a = uv[i * 2 + axis]
      val b = uv[j * 2 + axis] + (if (j == 0) wind * period else 0.0)
      val k = ceil((minOf(a, b) - seam) / period)
      val line = seam + k * period
      if (line > maxOf(a, b) || a == b) continue
      // точка разреза и обход от неё: i+1 … i, затем снова точка разреза на обороте дальше
      val t = (line - a) / (b - a)
      val cutOther = uv[i * 2 + other] + t * (uv[j * 2 + other] - uv[i * 2 + other])
      val out = DoubleArray((n + 2) * 2)
      val edges = BooleanArray(n + 2)
      var shiftBy = seam - line
      out[axis] = seam; out[other] = cutOther
      edges[0] = ring.edge[i]
      for (s in 1..n) {
        val src = (i + s) % n
        if (src == 0 && s > 0) shiftBy += wind * period
        out[s * 2 + axis] = uv[src * 2 + axis] + shiftBy
        out[s * 2 + other] = uv[src * 2 + other]
        edges[s] = ring.edge[src]
      }
      out[(n + 1) * 2 + axis] = seam + wind * period
      out[(n + 1) * 2 + other] = cutOther
      edges[n + 1] = false
      val strip = Ring(out, edges)
      if (wind > 0) return strip
      // обход против роста: разворачиваем, чтобы шёл от seam до seam + период
      val m = n + 2
      val rev = DoubleArray(m * 2)
      val revEdges = BooleanArray(m)
      for (s in 0 until m) {
        val src = m - 1 - s
        rev[s * 2 + axis] = out[src * 2 + axis] + period
        rev[s * 2 + other] = out[src * 2 + other]
        revEdges[s] = if (s == m - 1) false else edges[src - 1]
      }
      rev[axis] = seam
      rev[(m - 1) * 2 + axis] = seam + period
      return Ring(rev, revEdges)
    }
    return null
  }

  /**
   * Сетка грани в (u, v): контуры с точками не реже шага сетки, «молния» или ear clipping ([HatchTriangulator]), перевороты рёбер
   * до треугольников Делоне в метрике поверхности, внутренние узлы сетки, где поверхность кривая по обеим осям, и
   * деление рёбер длиннее полутора ячеек. Вершины всех треугольников общие — без T-образных стыков и щелей.
   */
  private class Mesher(
    private val contours: List<Ring>, private val surface: AcisSurface, perTurn: (Double) -> Int,
    private val emit: (DoubleArray, Int) -> Unit,
  ) {
    private val u0: Double
    private val v0: Double
    private val u1: Double
    private val v1: Double
    private val columns: Int
    private val rows: Int
    // ячейка сетки по u и v; 0 — по этой оси не делим
    private val cu: Double
    private val cv: Double
    // метрика для треугольников Делоне: множители u и v
    private val su: Double
    private val sv: Double
    private val seamU: Boolean
    private val seamV: Boolean

    // вершины (u, v), треугольники (номера вершин против часовой), владелец направленного ребра a → b
    private var uv = DoubleArray(256)
    private var points = 0
    private var tri = IntArray(384)
    private var triangles = 0
    private val ids = HashMap<Pair<Double, Double>, Int>()
    private val owner = EdgeMap()
    // рёбра тела (без направления) — идут в каркас
    private val body = EdgeMap()
    private val stack = LongStack()

    init {
      var minU = Double.MAX_VALUE; var minV = Double.MAX_VALUE
      var maxU = -Double.MAX_VALUE; var maxV = -Double.MAX_VALUE
      for (ring in contours) for (i in 0 until ring.size) {
        val u = ring.uv[i * 2]
        val v = ring.uv[i * 2 + 1]
        if (u < minU) minU = u; if (u > maxU) maxU = u
        if (v < minV) minV = v; if (v > maxV) maxV = v
      }
      u0 = minU; v0 = minV; u1 = maxU; v1 = maxV
      val step = surface.grid(doubleArrayOf(u0, v0, u1, v1), perTurn)
      columns = cells(u1 - u0, step[0])
      rows = cells(v1 - v0, step[1])
      cu = if (columns > 1) (u1 - u0) / columns else 0.0
      cv = if (rows > 1) (v1 - v0) / rows else 0.0
      // сетка по обеим осям — метрика ячеек (узлы соединяются с соседними), иначе — длины на поверхности
      su = if (cu > 0 && cv > 0) 1 / cu else step[2]
      sv = if (cu > 0 && cv > 0) 1 / cv else step[3]
      seamU = surface.periodU > 0 && abs(u1 - u0 - surface.periodU) <= 1e-9 * surface.periodU
      seamV = surface.periodV > 0 && abs(v1 - v0 - surface.periodV) <= 1e-9 * surface.periodV
    }

    private fun cells(width: Double, step: Double): Int =
      if (step <= 0.0 || step.isNaN() || width <= 0.0) 1 else ceil(width / step - 1e-9).toInt().coerceIn(1, MAX_CELLS)

    fun run(): Int {
      if (!(u1 > u0) || !(v1 > v0) || su.isNaN() || sv.isNaN()) return 0
      val rings = contours.map { densify(it) }
      val ringIds = rings.map { ring -> IntArray(ring.size) { vertex(ring.uv[it * 2], ring.uv[it * 2 + 1]) } }
      for ((r, ring) in rings.withIndex()) {
        val id = ringIds[r]
        for (i in 0 until ring.size) if (ring.edge[i]) body[pair(id[i], id[(i + 1) % ring.size])] = 1
      }
      // полоса цилиндра или конуса — один контур, монотонный по u: «молния» сразу даёт узкие треугольники;
      // иначе ear clipping (его веера длинных треугольников выправляют перевороты)
      if (rings.size != 1 || !zipper(ringIds[0])) {
        val t = HatchTriangulator.triangulate(rings.map { it.uv })
        for (k in 0 until t.size / 6) {
          val a = ids[t[k * 6] to t[k * 6 + 1]] ?: continue
          val b = ids[t[k * 6 + 2] to t[k * 6 + 3]] ?: continue
          val c = ids[t[k * 6 + 4] to t[k * 6 + 5]] ?: continue
          if (a != b && b != c && a != c) addTriangle(a, b, c)
        }
        if (triangles == 0) return 0
        for (id in ringIds) restore(id)
      }
      for (k in 0 until triangles) for (e in 0..2) stack.add(key(tri[k * 3 + e], tri[k * 3 + (e + 1) % 3]))
      flip()
      if (columns > 1 && rows > 1) addGrid()
      refine()
      return output()
    }

    /**
     * Контур, монотонный по u (две цепочки от самой левой точки до самой правой), — треугольники «молнией»: шаг
     * по той цепочке, чья следующая точка левее. Не монотонный или вывернутый треугольник — false, сетка пуста.
     */
    private fun zipper(id: IntArray): Boolean {
      val n = id.size
      if (n < 3) return false
      var lo = 0
      var hi = 0
      var area = 0.0
      for (i in 0 until n) {
        val u = uv[id[i] * 2]
        val v = uv[id[i] * 2 + 1]
        if (u < uv[id[lo] * 2] || (u == uv[id[lo] * 2] && v < uv[id[lo] * 2 + 1])) lo = i
        if (u > uv[id[hi] * 2] || (u == uv[id[hi] * 2] && v > uv[id[hi] * 2 + 1])) hi = i
        val j = id[(i + 1) % n]
        area += uv[id[i] * 2] * uv[j * 2 + 1] - uv[j * 2] * uv[id[i] * 2 + 1]
      }
      if (lo == hi || area == 0.0) return false
      // цепочки от lo до hi вперёд и назад по контуру, без повторов точек; нижняя — та, что идёт против часовой
      fun chain(step: Int): IntArray? {
        val out = ArrayList<Int>()
        var i = lo
        while (true) {
          if (out.isEmpty() || out.last() != id[i]) {
            if (out.isNotEmpty() && uv[id[i] * 2] < uv[out.last() * 2]) return null
            out.add(id[i])
          }
          if (i == hi) return out.toIntArray()
          i = (i + step + n) % n
        }
      }
      val forward = chain(1) ?: return false
      val backward = chain(-1) ?: return false
      val low = if (area > 0) forward else backward
      val high = if (area > 0) backward else forward
      var i = 0
      var j = 0
      while (i < low.size - 1 || j < high.size - 1) {
        val alongLow = when {
          j == high.size - 1 -> true
          i == low.size - 1 -> false
          else -> {
            val ul = uv[low[i + 1] * 2]
            val uh = uv[high[j + 1] * 2]
            // равные u — короче диагональ
            ul < uh || (ul == uh && dist(low[i + 1], high[j]) <= dist(high[j + 1], low[i]))
          }
        }
        val a = low[i]
        val b = if (alongLow) low[i + 1] else high[j + 1]
        val c = high[j]
        if (alongLow) i++ else j++
        if (a == c || b == c || a == b) continue
        if (orient(a, b, c) <= 0) {
          triangles = 0
          owner.clear()
          return false
        }
        addTriangle(a, b, c)
      }
      return triangles > 0
    }

    private fun dist(a: Int, b: Int): Double {
      val dx = x(a) - x(b)
      val dy = y(a) - y(b)
      return dx * dx + dy * dy
    }

    // отрезки контура длиннее ячейки делятся поровну: граница прямая в (u, v), точки ложатся на поверхность
    private fun densify(ring: Ring): Ring {
      val n = ring.size
      val out = GrowingDoubleArray()
      val edges = ArrayList<Boolean>(n)
      for (i in 0 until n) {
        val j = (i + 1) % n
        val au = ring.uv[i * 2]; val av = ring.uv[i * 2 + 1]
        val du = ring.uv[j * 2] - au; val dv = ring.uv[j * 2 + 1] - av
        var parts = 1
        if (cu > 0) parts = maxOf(parts, ceil(abs(du) / cu - 1e-9).toInt())
        if (cv > 0) parts = maxOf(parts, ceil(abs(dv) / cv - 1e-9).toInt())
        parts = parts.coerceAtMost(MAX_CELLS)
        for (s in 0 until parts) {
          out.add(au + du * s / parts); out.add(av + dv * s / parts)
          edges.add(ring.edge[i])
        }
      }
      return Ring(out.toArray(), edges.toBooleanArray())
    }

    private fun vertex(u: Double, v: Double): Int = ids.getOrPut(u to v) {
      if (points * 2 + 2 > uv.size) uv = uv.copyOf(uv.size * 2)
      uv[points * 2] = u
      uv[points * 2 + 1] = v
      points++
    }

    private fun key(a: Int, b: Int) = a.toLong() shl 32 or (b.toLong() and 0xFFFFFFFFL)

    private fun pair(a: Int, b: Int) = key(minOf(a, b), maxOf(a, b))

    private fun x(i: Int) = uv[i * 2] * su
    private fun y(i: Int) = uv[i * 2 + 1] * sv

    private fun orient(a: Int, b: Int, c: Int) = (x(b) - x(a)) * (y(c) - y(a)) - (y(b) - y(a)) * (x(c) - x(a))

    private fun addTriangle(a: Int, b: Int, c: Int): Int {
      if (triangles * 3 + 3 > tri.size) tri = tri.copyOf(tri.size * 2)
      setTriangle(triangles, a, b, c)
      return triangles++
    }

    private fun setTriangle(k: Int, a: Int, b: Int, c: Int) {
      tri[k * 3] = a; tri[k * 3 + 1] = b; tri[k * 3 + 2] = c
      owner[key(a, b)] = k; owner[key(b, c)] = k; owner[key(c, a)] = k
    }

    // третья вершина треугольника k напротив ребра a – b
    private fun third(k: Int, a: Int, b: Int): Int {
      for (e in 0..2) if (tri[k * 3 + e] != a && tri[k * 3 + e] != b) return tri[k * 3 + e]
      return -1
    }

    /** Точку p на ребре a – b: треугольники по обе стороны делятся пополам. */
    private fun splitEdge(a: Int, b: Int, p: Int): Boolean {
      val t1 = owner[key(a, b)]
      val t2 = owner[key(b, a)]
      if (t1 < 0 && t2 < 0) return false
      owner.remove(key(a, b)); owner.remove(key(b, a))
      if (t1 >= 0) {
        val c = third(t1, a, b)
        setTriangle(t1, a, p, c)
        addTriangle(p, b, c)
        stack.add(key(b, c)); stack.add(key(c, a))
      }
      if (t2 >= 0) {
        val d = third(t2, a, b)
        setTriangle(t2, b, p, d)
        addTriangle(p, a, d)
        stack.add(key(a, d)); stack.add(key(d, b))
      }
      if (body[pair(a, b)] >= 0) {
        body.remove(pair(a, b))
        body[pair(a, p)] = 1
        body[pair(p, b)] = 1
      }
      return true
    }

    // точки контура, которые ear clipping снял как лежащие на одной прямой, возвращаются на свои рёбра
    private fun restore(id: IntArray) {
      val n = id.size
      val used = BooleanArray(n) { isUsed(id[it]) }
      val start = (0 until n).firstOrNull { used[it] } ?: return
      var a = id[start]
      var run = ArrayList<Int>()
      for (s in 1..n) {
        val i = (start + s) % n
        if (!used[i]) {
          run.add(id[i])
          continue
        }
        var from = a
        for (p in run) {
          if (!splitEdge(from, id[i], p)) break
          from = p
        }
        run = ArrayList()
        a = id[i]
      }
    }

    private var usedIds: BooleanArray? = null

    private fun isUsed(id: Int): Boolean {
      val u = usedIds ?: BooleanArray(points).also { b -> for (k in 0 until triangles * 3) b[tri[k]] = true; usedIds = b }
      return id < u.size && u[id]
    }

    /** Перевороты рёбер из стека, пока у соседних треугольников вершина попадает в описанную окружность. */
    private fun flip() {
      // переворотов — O(n²) в худшем случае (веер ear clipping), каждый кладёт в стек 4 ребра
      var budget = minOf(4L * triangles * triangles + 10000, 50_000_000L)
      while (stack.isNotEmpty() && budget-- > 0) {
        val e = stack.pop()
        val a = (e ushr 32).toInt()
        val b = e.toInt()
        val t1 = owner[e]
        val t2 = owner[key(b, a)]
        if (t1 < 0 || t2 < 0) continue
        val c = third(t1, a, b)
        val d = third(t2, a, b)
        if (c < 0 || d < 0 || c == d) continue
        // d внутри окружности (a, b, c); обход a, b, c — против часовой
        val adx = x(a) - x(d); val ady = y(a) - y(d)
        val bdx = x(b) - x(d); val bdy = y(b) - y(d)
        val cdx = x(c) - x(d); val cdy = y(c) - y(d)
        val det = (adx * adx + ady * ady) * (bdx * cdy - cdx * bdy) - (bdx * bdx + bdy * bdy) * (adx * cdy - cdx * ady) +
          (cdx * cdx + cdy * cdy) * (adx * bdy - bdx * ady)
        val size = adx * adx + ady * ady + bdx * bdx + bdy * bdy + cdx * cdx + cdy * cdy
        if (det <= 1e-12 * size * size) continue
        if (orient(a, d, c) <= 0 || orient(d, b, c) <= 0) continue
        // (a, b, c) + (b, a, d) → (a, d, c) + (d, b, c)
        owner.remove(e); owner.remove(key(b, a))
        setTriangle(t1, a, d, c)
        setTriangle(t2, d, b, c)
        stack.add(key(a, d)); stack.add(key(d, b)); stack.add(key(b, c)); stack.add(key(c, a))
      }
      stack.clear()
    }

    // внутренние узлы сетки: поверхность кривая по обеим осям (конус, сфера, тор)
    private fun addGrid() {
      var last = 0
      for (j in 1 until rows) for (i in 1 until columns) {
        val u = u0 + (u1 - u0) * i / columns
        val v = v0 + (v1 - v0) * j / rows
        val p = vertex(u, v)
        val k = locate(p, last)
        if (k < 0) continue
        last = k
        val a = tri[k * 3]; val b = tri[k * 3 + 1]; val c = tri[k * 3 + 2]
        // узел у самого ребра или вершины даёт вырожденные треугольники — пропускаем
        val area = orient(a, b, c)
        if (orient(a, b, p) <= 1e-6 * area || orient(b, c, p) <= 1e-6 * area || orient(c, a, p) <= 1e-6 * area) continue
        setTriangle(k, a, b, p)
        addTriangle(b, c, p)
        addTriangle(c, a, p)
        stack.add(key(a, b)); stack.add(key(b, c)); stack.add(key(c, a))
        flip()
      }
    }

    // треугольник, содержащий p: шаги к соседу через ребро, за которым лежит p; не дошли — перебор
    private fun locate(p: Int, from: Int): Int {
      var k = from.coerceIn(0, triangles - 1)
      var steps = 0
      walk@ while (steps++ < triangles) {
        for (e in 0..2) {
          val a = tri[k * 3 + e]
          val b = tri[k * 3 + (e + 1) % 3]
          if (orient(a, b, p) < 0) {
            k = owner[key(b, a)]
            if (k < 0) break@walk
            continue@walk
          }
        }
        return k
      }
      for (t in 0 until triangles) {
        if (orient(tri[t * 3], tri[t * 3 + 1], p) >= 0 && orient(tri[t * 3 + 1], tri[t * 3 + 2], p) >= 0 &&
          orient(tri[t * 3 + 2], tri[t * 3], p) >= 0) return t
      }
      return -1
    }

    // рёбра длиннее полутора ячеек делятся пополам (самое длинное в треугольнике — первым), после — перевороты
    private fun refine() {
      if (cu <= 0 && cv <= 0) return
      val limit = points + 8 * columns * rows + 4096
      var k = 0
      while (k < triangles && points < limit) {
        var worst = 1.5
        var we = -1
        for (e in 0..2) {
          val a = tri[k * 3 + e]
          val b = tri[k * 3 + (e + 1) % 3]
          val du = if (cu > 0) abs(uv[a * 2] - uv[b * 2]) / cu else 0.0
          val dv = if (cv > 0) abs(uv[a * 2 + 1] - uv[b * 2 + 1]) / cv else 0.0
          val m = maxOf(du, dv)
          if (m > worst) { worst = m; we = e }
        }
        if (we < 0) {
          k++
          continue
        }
        val a = tri[k * 3 + we]
        val b = tri[k * 3 + (we + 1) % 3]
        splitEdge(a, b, vertex((uv[a * 2] + uv[b * 2]) / 2, (uv[a * 2 + 1] + uv[b * 2 + 1]) / 2))
        flip()
      }
    }

    private val xyz = DoubleArray(9)
    private val p = DoubleArray(3)

    private fun output(): Int {
      var count = 0
      for (k in 0 until triangles) {
        val a = tri[k * 3]
        val b = tri[k * 3 + 1]
        val c = tri[k * 3 + 2]
        put(0, a); put(1, b); put(2, c)
        if (same(0, 1) || same(1, 2) || same(0, 2)) continue
        val ab = body[pair(a, b)] >= 0
        val bc = body[pair(b, c)] >= 0
        val ca = body[pair(c, a)] >= 0
        var edges = (if (ab) 1 else 0) or (if (bc) 2 else 0) or (if (ca) 4 else 0)
        if (surface.flip) {
          // a, c, b: стороны ac, cb, ba
          for (i in 0..2) { val tmp = xyz[3 + i]; xyz[3 + i] = xyz[6 + i]; xyz[6 + i] = tmp }
          edges = (if (ca) 1 else 0) or (if (bc) 2 else 0) or (if (ab) 4 else 0)
        }
        emit(xyz, edges)
        count++
      }
      return count
    }

    private fun put(slot: Int, i: Int) {
      var u = uv[i * 2]
      var v = uv[i * 2 + 1]
      // шов: край окна шириной в период — та же точка, что начало окна (вершины склеятся, нормали без излома)
      if (u == u1 && seamU) u = u0
      if (v == v1 && seamV) v = v0
      surface.point(u, v, p)
      xyz[slot * 3] = p[0]; xyz[slot * 3 + 1] = p[1]; xyz[slot * 3 + 2] = p[2]
    }

    private fun same(a: Int, b: Int) = xyz[a * 3] == xyz[b * 3] && xyz[a * 3 + 1] == xyz[b * 3 + 1] && xyz[a * 3 + 2] == xyz[b * 3 + 2]
  }

  /** Номер треугольника по направленному ребру (ключ a shl 32 or b): открытая адресация без упаковки чисел. */
  private class EdgeMap {
    private var keys = LongArray(256) { NONE }
    private var values = IntArray(256)
    private var size = 0

    private fun slot(k: Long, mask: Int) = ((k * -7046029254386353131L) ushr 40).toInt() and mask

    /** Треугольник ребра [k]; нет — −1. */
    operator fun get(k: Long): Int {
      val mask = keys.size - 1
      var i = slot(k, mask)
      while (true) {
        val key = keys[i]
        if (key == k) return values[i]
        if (key == NONE) return -1
        i = (i + 1) and mask
      }
    }

    operator fun set(k: Long, v: Int) {
      if ((size + 1) * 2 > keys.size) grow()
      val mask = keys.size - 1
      var i = slot(k, mask)
      while (keys[i] != NONE && keys[i] != k) i = (i + 1) and mask
      if (keys[i] == NONE) size++
      keys[i] = k
      values[i] = v
    }

    // удаление со сдвигом следующих ключей цепочки назад — без «надгробий»
    fun remove(k: Long) {
      val mask = keys.size - 1
      var i = slot(k, mask)
      while (keys[i] != k) {
        if (keys[i] == NONE) return
        i = (i + 1) and mask
      }
      var j = i
      while (true) {
        j = (j + 1) and mask
        val key = keys[j]
        if (key == NONE) break
        val h = slot(key, mask)
        // ключ j можно перенести в i, если его место h не лежит в (i, j] по кругу
        if (if (i <= j) h <= i || h > j else h <= i && h > j) {
          keys[i] = key
          values[i] = values[j]
          i = j
        }
      }
      keys[i] = NONE
      size--
    }

    fun clear() {
      keys.fill(NONE)
      size = 0
    }

    private fun grow() {
      val oldKeys = keys
      val oldValues = values
      keys = LongArray(oldKeys.size * 2) { NONE }
      values = IntArray(oldKeys.size * 2)
      size = 0
      for (i in oldKeys.indices) if (oldKeys[i] != NONE) set(oldKeys[i], oldValues[i])
    }

    private companion object {
      const val NONE = Long.MIN_VALUE
    }
  }

  private class LongStack {
    private var values = LongArray(256)
    private var size = 0

    fun add(v: Long) {
      if (size == values.size) values = values.copyOf(size * 2)
      values[size++] = v
    }

    fun pop(): Long = values[--size]

    fun isNotEmpty() = size > 0

    fun clear() {
      size = 0
    }
  }

  // предел делений сетки по оси
  private const val MAX_CELLS = 512
}
