package dev.iskandern.kotin.model

/**
 * Треугольная сетка одного слоя в примитивных массивах.
 * Координаты — как в файле (x, y, z подряд), индексы треугольников 0-based, вершины в порядке первого появления.
 * [edgeMask] — видимые в каркасе рёбра треугольника (бит 1 — a-b, 2 — b-c, 4 — c-a); null — видны все
 * (у TIN маски нет: она заводится только для четырёхугольных граней и невидимых рёбер 3DFACE).
 */
class SceneMesh(
  val layer: String,
  val coords: DoubleArray,
  val triangles: IntArray,
  val minX: Double, val minY: Double, val minZ: Double,
  val maxX: Double, val maxY: Double, val maxZ: Double,
  val edgeMask: ByteArray? = null,
  /** Номер объекта на каждый треугольник ([NO_OBJECT] — из [objectId]); null — у всех [objectId]. */
  val triangleObjects: IntArray? = null,
  /** Объект всей сетки, если у треугольника своего нет (например, поверхность из 3DFACE слоя). */
  val objectId: Int = NO_OBJECT,
  /** Рукотворная сетка (колодцы, трубы LandXML), а не рельеф: показывается галочкой «Трубы», а не «Поверхность». */
  val structure: Boolean = false,
  /** Обводить внешнюю границу (рёбра одного треугольника) тёмной линией: TIN без своей границы в файле. */
  val outline: Boolean = false,
) {
  val vertexCount: Int get() = coords.size / 3
  val triangleCount: Int get() = triangles.size / 3

  /** Та же сетка с обводкой внешней границы ([outline]). */
  fun outlined(): SceneMesh =
    SceneMesh(layer, coords, triangles, minX, minY, minZ, maxX, maxY, maxZ, edgeMask, triangleObjects, objectId, structure, outline = true)

  /** Объект треугольника [triangle]. */
  fun objectOf(triangle: Int): Int {
    val own = triangleObjects?.get(triangle) ?: NO_OBJECT
    return if (own != NO_OBJECT) own else objectId
  }
}

/**
 * Собирает сетку, склеивая одинаковые вершины. Равенство — по битам координат, как у data class с Double
 * (Double.compare), поэтому результат совпадает с прежней склейкой через HashMap<PointEntity, Int>.
 * Хеш-таблица с открытой адресацией на IntArray: без объектов на вершину и без упаковки чисел.
 */
class MeshBuilder(private val layer: String) {

  private var coords = DoubleArray(INITIAL_VERTICES * 3)
  private var vertexCount = 0
  private var triangles = IntArray(INITIAL_VERTICES * 3)
  private var triangleIndexCount = 0
  private var edgeMask: ByteArray? = null
  private val objectRuns = ObjectRuns()

  private var table = IntArray(INITIAL_VERTICES * 2).also { it.fill(EMPTY) }
  // вершины с меньшим номером не склеиваются с новыми (см. [beginGroup])
  private var groupStart = 0

  var minX = Double.POSITIVE_INFINITY
    private set
  var minY = Double.POSITIVE_INFINITY
    private set
  var minZ = Double.POSITIVE_INFINITY
    private set
  var maxX = Double.NEGATIVE_INFINITY
    private set
  var maxY = Double.NEGATIVE_INFINITY
    private set
  var maxZ = Double.NEGATIVE_INFINITY
    private set

  fun addTriangle(
    x1: Double, y1: Double, z1: Double,
    x2: Double, y2: Double, z2: Double,
    x3: Double, y3: Double, z3: Double,
  ) {
    val a = vertex(x1, y1, z1)
    val b = vertex(x2, y2, z2)
    val c = vertex(x3, y3, z3)

    if (triangleIndexCount + 3 > triangles.size) triangles = triangles.copyOf(triangles.size * 2)
    triangles[triangleIndexCount++] = a
    triangles[triangleIndexCount++] = b
    triangles[triangleIndexCount++] = c
  }

  /**
   * Грань 3DFACE: [c] — 4 угла подряд (x, y, z), [invisible] — код 70 (бит 1 — ребро 1-2, 2 — 2-3, 4 — 3-4, 8 — 4-1).
   * 4-й угол совпадает с 3-м или 1-м — один треугольник, иначе два: 1-2-3 и 1-3-4, диагональ 1-3 в каркас не идёт.
   */
  fun addFace(c: DoubleArray, invisible: Int) {
    val fourthIsThird = c[9] == c[6] && c[10] == c[7] && c[11] == c[8]
    if (fourthIsThird || c[9] == c[0] && c[10] == c[1] && c[11] == c[2]) {
      // замыкающее ребро 3-1 — это 4-1 или 3-4
      val closing = if (fourthIsThird) 8 else 4
      val edges = (if (invisible and 1 == 0) 1 else 0) or (if (invisible and 2 == 0) 2 else 0) or
        (if (invisible and closing == 0) 4 else 0)
      addTriangle(c[0], c[1], c[2], c[3], c[4], c[5], c[6], c[7], c[8], edges)
      return
    }
    addTriangle(
      c[0], c[1], c[2], c[3], c[4], c[5], c[6], c[7], c[8],
      (if (invisible and 1 == 0) 1 else 0) or (if (invisible and 2 == 0) 2 else 0),
    )
    addTriangle(
      c[0], c[1], c[2], c[6], c[7], c[8], c[9], c[10], c[11],
      (if (invisible and 4 == 0) 2 else 0) or (if (invisible and 8 == 0) 4 else 0),
    )
  }

  /** Треугольник с видимыми рёбрами [edges] (биты как у [SceneMesh.edgeMask]). */
  fun addTriangle(
    x1: Double, y1: Double, z1: Double,
    x2: Double, y2: Double, z2: Double,
    x3: Double, y3: Double, z3: Double,
    edges: Int,
  ) {
    val index = triangleIndexCount / 3
    addTriangle(x1, y1, z1, x2, y2, z2, x3, y3, z3)
    var mask = edgeMask
    if (mask == null) {
      if (edges == ALL_EDGES) return
      // маска заводится с первой грани со скрытым ребром, у прежних видны все рёбра
      mask = ByteArray(triangles.size / 3).also { it.fill(ALL_EDGES.toByte()) }
      edgeMask = mask
    } else if (index >= mask.size) {
      // треугольники после старого конца маски добавлены без неё — у них видны все рёбра
      val oldSize = mask.size
      mask = mask.copyOf(triangles.size / 3).also { it.fill(ALL_EDGES.toByte(), oldSize) }
      edgeMask = mask
    }
    mask[index] = edges.toByte()
  }

  /**
   * Следующие треугольники до [endGroup] не делят вершины с прежними: нормали вершин у грани тела свои,
   * без усреднения с соседними гранями под углом (иначе куб выглядит сглаженным).
   */
  fun beginGroup() {
    groupStart = vertexCount
  }

  fun endGroup() {
    groupStart = 0
  }

  /** Следующие треугольники принадлежат объекту [id]. */
  fun markObject(id: Int) = objectRuns.mark(triangleIndexCount / 3, id)

  /** [objectId] — объект треугольников, у которых своего нет. */
  fun build(objectId: Int = NO_OBJECT, outline: Boolean = false): SceneMesh = SceneMesh(
    layer = layer,
    coords = coords.copyOf(vertexCount * 3),
    triangles = triangles.copyOf(triangleIndexCount),
    minX = minX, minY = minY, minZ = minZ,
    maxX = maxX, maxY = maxY, maxZ = maxZ,
    edgeMask = edgeMask?.let { mask ->
      mask.copyOf(triangleIndexCount / 3).also { if (mask.size < it.size) it.fill(ALL_EDGES.toByte(), mask.size) }
    },
    triangleObjects = objectRuns.expand(triangleIndexCount / 3),
    objectId = objectId,
    outline = outline,
  )

  private fun vertex(x: Double, y: Double, z: Double): Int {
    val bx = x.toBits()
    val by = y.toBits()
    val bz = z.toBits()
    val mask = table.size - 1
    var slot = hash(bx, by, bz) and mask

    while (true) {
      val index = table[slot]
      if (index == EMPTY) return insert(slot, x, y, z)
      val offset = index * 3
      if (index >= groupStart && coords[offset].toBits() == bx && coords[offset + 1].toBits() == by && coords[offset + 2].toBits() == bz) {
        return index
      }
      slot = (slot + 1) and mask
    }
  }

  private fun insert(slot: Int, x: Double, y: Double, z: Double): Int {
    val index = vertexCount
    if ((index + 1) * 3 > coords.size) coords = coords.copyOf(coords.size * 2)
    coords[index * 3] = x
    coords[index * 3 + 1] = y
    coords[index * 3 + 2] = z
    vertexCount++
    table[slot] = index

    if (x < minX) minX = x
    if (y < minY) minY = y
    if (z < minZ) minZ = z
    if (x > maxX) maxX = x
    if (y > maxY) maxY = y
    if (z > maxZ) maxZ = z

    // держим заполнение таблицы не выше 50%
    if (vertexCount * 2 > table.size) rehash()
    return index
  }

  private fun rehash() {
    table = IntArray(table.size * 2).also { it.fill(EMPTY) }
    val mask = table.size - 1
    for (index in 0 until vertexCount) {
      val offset = index * 3
      var slot = hash(coords[offset].toBits(), coords[offset + 1].toBits(), coords[offset + 2].toBits()) and mask
      while (table[slot] != EMPTY) slot = (slot + 1) and mask
      table[slot] = index
    }
  }

  private fun hash(bx: Long, by: Long, bz: Long): Int {
    var h = bx * -0x61c8864680b583ebL
    h = (h xor by) * -0x61c8864680b583ebL
    h = (h xor bz) * -0x61c8864680b583ebL
    return (h xor (h ushr 32)).toInt()
  }

  companion object {
    /** Видны все три ребра треугольника. */
    const val ALL_EDGES = 7
    private const val EMPTY = -1
    private const val INITIAL_VERTICES = 1024
  }
}
