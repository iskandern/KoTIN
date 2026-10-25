package dev.iskandern.kotin.landxml

import dev.iskandern.kotin.model.Journal
import dev.iskandern.kotin.model.SceneAttribute
import dev.iskandern.kotin.model.SceneMesh
import dev.iskandern.kotin.model.SceneObject
import dev.iskandern.kotin.model.UiText
import dev.iskandern.kotin.dxf.ScenePalette

/**
 * Раздел `Surfaces`: `Surface[name, desc, state]` → `Definition[surfType]` → `Pnts/P[id]` и один или несколько `Faces/F[i]`
 * → сетка на слое с именем поверхности и объект «Surface». `SourceData` (границы, структурные линии, горизонтали, точки
 * данных) читает [SurfaceSourceData] на слои «<поверхность>: …»; `Watersheds` пропускается. Поверхность без `Definition`
 * — не ошибка: сетки у неё нет, исходные данные рисуются.
 *
 * Сетка строится напрямую, без склейки вершин ([dev.iskandern.kotin.model.MeshBuilder]): в TIN вершины уже уникальны.
 * `P id` — номер, а не индекс (в корпусе с 2 и с пропусками): таблица «id → индекс» — плотный `IntArray`, если id плотные,
 * иначе хеш. `F` — три id (TIN) или четыре (`grid`: два треугольника по диагонали 1–3, диагональ скрыта в каркасе);
 * `i="1"` — невидимая грань, пропускается; `n` и `b` не читаются (у чужих экспортёров там бывают имена точек).
 * Ячейка grid: все id нули — «нет данных», пропускается; вершина с отметкой-заглушкой (−9999, ±1e30…) — тоже; порядок
 * вершин «крест-накрест» (самопересечение в плане) переставляется. Грань со ссылкой на неизвестный id и вырожденная (повтор вершины) пропускаются и считаются. Обход — против часовой
 * в плане (нормаль вверх): грань, записанная по часовой, разворачивается. В сетку идут только вершины видимых граней.
 *
 * Горячий цикл по `P` и `F` строк не создаёт: числа — из буфера токенизатора ([hotStrings] — для проверки).
 */
internal class SurfacesSection(
  private val log: (String) -> Unit = Journal::log,
) : LandXmlSection("Surfaces", NAMES) {
  /** Строк, созданных токенизатором в циклах по `P` и `F`; должно быть 0. */
  var hotStrings = 0L
    private set

  private val layerNames = HashSet<String>()

  /** Исходные данные поверхностей (`SourceData`). */
  val sourceData = SurfaceSourceData(log)
  private var surfaces = 0
  // сетка текущей Surface до конца элемента
  private var pending: SceneMesh? = null

  // точки текущей Definition: координаты (x, y, z подряд) кусками по CHUNK точек и id; габариты — в том же проходе.
  // Куски растут без копирования, сетка собирается одним массивом в addMesh, куски отпускаются по мере копирования:
  // пик — итоговый массив и куски, а не удвоенный массив и его обрезанная копия (размер файла приложение не знает)
  private var coords = arrayOfNulls<DoubleArray>(CHUNKS)
  private val point = DoubleArray(3)
  private var ids = IntArray(0)
  private var count = 0
  private var maxId = 0
  private var minX = 0.0
  private var minY = 0.0
  private var minZ = 0.0
  private var maxX = 0.0
  private var maxY = 0.0
  private var maxZ = 0.0

  // «id → индекс»: плотная таблица или хеш с открытой адресацией
  private var dense: IntArray? = null
  private var hashKeys = IntArray(0)
  private var hashValues = IntArray(0)

  // треугольники кусками по CHUNK (тройки индексов) и маска рёбер (заводится с первой четырёхугольной грани)
  private var triangles = arrayOfNulls<IntArray>(CHUNKS)
  private var triangleCount = 0
  private var edgeMask: ByteArray? = null
  private val face = IntArray(4)

  // счётчики текущей Definition
  private var points = 0
  private var faces = 0
  private var invisible = 0
  private var badFaces = 0
  private var degenerate = 0
  private var flipped = 0
  private var badPoints = 0
  private var duplicates = 0
  private var empty = 0
  private var noData = 0
  private var reordered = 0

  override fun read(xml: XmlTokenizer, scene: LandXmlSceneBuilder) {
    val depth = xml.depth
    while (xml.nextChild(depth)) {
      if (xml.nameId == SURFACE) readSurface(xml, scene) else xml.skipElement()
    }
  }

  private fun readSurface(xml: XmlTokenizer, scene: LandXmlSceneBuilder) {
    val name = xml.attrString(NAME).orEmpty()
    val desc = xml.attrString(DESC).orEmpty()
    val state = xml.attrString(STATE).orEmpty()
    // слой поверхности — и основа имён слоёв её исходных данных
    val layerName = uniqueLayer(name.ifBlank { DEFAULT_NAME })
    val depth = xml.depth
    var hasDefinition = false
    // SourceData бывает и до, и после Definition: сетка добавляется в конце Surface, когда известно, есть ли граница
    sourceData.outerBoundaries = 0
    pending = null
    while (xml.nextChild(depth)) {
      when (xml.nameId) {
        DEFINITION -> {
          hasDefinition = true
          readDefinition(xml, scene, name, layerName, desc, state)
        }
        SurfaceSourceData.SOURCE_DATA -> sourceData.read(xml, scene, layerName)
        else -> xml.skipElement()
      }
    }
    if (!hasDefinition) log(UiText.key("log_surface_no_definition", name))
    pending?.let { scene.addMesh(if (sourceData.outerBoundaries == 0) it.outlined() else it, surface = name) }
    pending = null
  }

  private fun readDefinition(
    xml: XmlTokenizer, scene: LandXmlSceneBuilder, name: String, layerName: String, desc: String, state: String,
  ) {
    val type = xml.attrString(SURF_TYPE)?.takeIf { it.isNotBlank() } ?: "TIN"
    val area2D = attrNumber(xml, AREA_2D)
    val area3D = attrNumber(xml, AREA_3D)
    val elevMin = attrNumber(xml, ELEV_MIN)
    val elevMax = attrNumber(xml, ELEV_MAX)
    reset()
    var pntsSeen = false
    var facesSeen = false
    val depth = xml.depth
    while (xml.nextChild(depth)) {
      when (xml.nameId) {
        PNTS -> {
          if (facesSeen) throw XmlFormatException(UiText.key("xml_pnts_after_faces", name), xml.bytesRead)
          pntsSeen = true
          readPoints(xml, scene)
        }
        FACES -> {
          if (!pntsSeen) throw XmlFormatException(UiText.key("xml_faces_before_pnts", name), xml.bytesRead)
          if (!facesSeen) buildIndex()
          facesSeen = true
          readFaces(xml, scene)
        }
        else -> xml.skipElement()
      }
    }
    val visible = faces - invisible - badFaces - empty - noData
    log(UiText.key("log_surface", name, type, points, faces, invisible, triangleCount))
    if (badFaces + degenerate + badPoints + duplicates > 0) {
      log(UiText.key("log_surface_bad", name, badFaces, degenerate, badPoints, duplicates))
    }
    if (empty + noData + reordered > 0) {
      log(UiText.key("log_grid_cells", name, empty, noData, reordered))
    }
    if (flipped > 0) log(UiText.key("log_surface_flipped", name, flipped))
    if (triangleCount > 0) addMesh(scene, name, layerName, desc, state, type, visible, area2D, area3D, elevMin, elevMax)
    release()
  }

  private fun readPoints(xml: XmlTokenizer, scene: LandXmlSceneBuilder) {
    val strings = xml.stringsCreated
    val depth = xml.depth
    while (xml.nextChild(depth)) {
      if (xml.nameId == P) readPoint(xml, scene) else xml.skipElement()
      scene.tick()
    }
    hotStrings += xml.stringsCreated - strings
  }

  private fun readPoint(xml: XmlTokenizer, scene: LandXmlSceneBuilder) {
    points++
    val id = if (xml.findAttr(ID)) LandXmlNumbers.parseInt(xml.valueBuffer, xml.valueStart, xml.valueEnd) else LandXmlNumbers.NOT_INT
    if (id <= 0) {
      // нет id или не положительное целое (NOT_INT тоже отрицательный)
      badPoints++
      return
    }
    if (LandXmlCoord.readPoint(xml, point) == 0) {
      badPoints++
      return
    }
    if (count >= ids.size) grow(xml, scene)
    if (count and CHUNK_MASK == 0) coords = addChunk(coords, count, DoubleArray(CHUNK * 3))
    val block = coords[count ushr CHUNK_SHIFT]!!
    val at = (count and CHUNK_MASK) * 3
    val x = point[0]
    val y = point[1]
    val z = point[2]
    block[at] = x
    block[at + 1] = y
    block[at + 2] = z
    if (count == 0) {
      minX = x; maxX = x; minY = y; maxY = y; minZ = z; maxZ = z
    } else {
      if (x < minX) minX = x else if (x > maxX) maxX = x
      if (y < minY) minY = y else if (y > maxY) maxY = y
      if (z < minZ) minZ = z else if (z > maxZ) maxZ = z
    }
    val value = id.toInt()
    ids[count++] = value
    if (value > maxId) maxId = value
  }

  // ёмкость id: первый раз — по размеру файла (точек не больше, чем строк P в нём), дальше — вдвое
  private fun grow(xml: XmlTokenizer, scene: LandXmlSceneBuilder) {
    var capacity = if (count == 0) estimateCapacity(xml, scene) else count * 2
    if (capacity <= count) capacity = count + 1
    ids = ids.copyOf(capacity)
  }

  // кусок [chunk] с номера [index] (кратного CHUNK); таблица кусков растёт вдвое
  private fun <T> addChunk(chunks: Array<T?>, index: Int, chunk: T): Array<T?> {
    val k = index ushr CHUNK_SHIFT
    val result = if (k < chunks.size) chunks else chunks.copyOf(chunks.size * 2)
    result[k] = chunk
    return result
  }

  // строка P — три числа длиной как последнее прочитанное и разметка вокруг; кроме точек в файле ещё вдвое больше
  // граней, поэтому делим ещё на 2: недооценка стоит одного копирования, переоценка — памяти
  private fun estimateCapacity(xml: XmlTokenizer, scene: LandXmlSceneBuilder): Int {
    val size = scene.sizeBytes ?: return MIN_CAPACITY
    val line = (xml.numberEnd - xml.numberStart) * 3 + P_MARKUP
    return (size / (line * 2)).coerceIn(MIN_CAPACITY.toLong(), MAX_CAPACITY.toLong()).toInt()
  }

  private fun buildIndex() {
    if (maxId <= count * DENSE_FACTOR + MIN_CAPACITY) {
      val table = IntArray(maxId + 1)
      table.fill(-1)
      for (i in 0 until count) {
        val id = ids[i]
        if (table[id] < 0) table[id] = i else duplicates++
      }
      dense = table
      return
    }
    var size = 16
    while (size < count * 2) size *= 2
    val keys = IntArray(size)
    val values = IntArray(size)
    val mask = size - 1
    for (i in 0 until count) {
      val id = ids[i]
      var slot = mix(id) and mask
      while (keys[slot] != 0 && keys[slot] != id) slot = (slot + 1) and mask
      if (keys[slot] == id) {
        duplicates++
        continue
      }
      keys[slot] = id
      values[slot] = i
    }
    hashKeys = keys
    hashValues = values
  }

  // индекс точки с [id] или -1
  private fun indexOf(id: Long): Int {
    if (id <= 0 || id > maxId) return -1
    val key = id.toInt()
    val table = dense
    if (table != null) return table[key]
    val keys = hashKeys
    val mask = keys.size - 1
    var slot = mix(key) and mask
    while (true) {
      val k = keys[slot]
      if (k == key) return hashValues[slot]
      if (k == 0) return -1
      slot = (slot + 1) and mask
    }
  }

  private fun readFaces(xml: XmlTokenizer, scene: LandXmlSceneBuilder) {
    val strings = xml.stringsCreated
    val depth = xml.depth
    while (xml.nextChild(depth)) {
      if (xml.nameId == F) readFace(xml) else xml.skipElement()
      scene.tick()
    }
    hotStrings += xml.stringsCreated - strings
  }

  private fun readFace(xml: XmlTokenizer) {
    faces++
    // i — один байт; n и b не разбираются
    val hidden = xml.findAttr(I) && xml.valueEnd - xml.valueStart == 1 && xml.valueBuffer[xml.valueStart] == ONE
    var n = 0
    var valid = true
    var zeros = 0
    while (xml.nextNumber()) {
      if (n == 4) {
        valid = false
        zeros = -1
        break
      }
      val id = LandXmlNumbers.parseInt(xml.buffer, xml.numberStart, xml.numberEnd)
      if (id == 0L) zeros++
      val index = indexOf(id)
      if (index < 0) valid = false
      face[n++] = index
    }
    // ячейка grid «нет данных»: все id нулевые — не ошибка
    if (zeros == n && n >= 3) {
      empty++
      return
    }
    if (!valid || n < 3) {
      badFaces++
      return
    }
    if (hidden) {
      invisible++
      return
    }
    val a = face[0]
    val b = face[1]
    val c = face[2]
    if (n == 3) {
      if (a == b || b == c || a == c) degenerate++ else addTriangle(a, b, c, ALL_EDGES)
      return
    }
    val d = face[3]
    if (a == b || b == c || c == d || d == a || a == c || b == d) {
      degenerate++
      return
    }
    // заглушка вместо отметки («нет данных») — ячейка пропускается, иначе из неё торчит шип
    if (isNoData(elevation(a)) || isNoData(elevation(b)) || isNoData(elevation(c)) || isNoData(elevation(d))) {
      noData++
      return
    }
    // порядок вершин ячейки в схеме не задан: обход «Z» (1-2-4-3) или «8» даёт самопересечение — переставляем
    var second = b
    var third = c
    var fourth = d
    if (crosses(b, c, d, a)) {
      third = d
      fourth = c
      reordered++
    } else if (crosses(a, b, c, d)) {
      second = c
      third = b
      reordered++
    }
    // ячейка grid: 1-2-3 и 1-3-4, диагональ 1–3 скрыта (бит 4 у первого, бит 1 у второго)
    addTriangle(a, second, third, EDGE_AB or EDGE_BC)
    addTriangle(a, third, fourth, EDGE_BC or EDGE_CA)
  }

  private fun elevation(i: Int) = coords[i ushr CHUNK_SHIFT]!![(i and CHUNK_MASK) * 3 + 2]

  // отрезки p–q и r–s пересекаются в плане строго внутри обоих
  private fun crosses(p: Int, q: Int, r: Int, s: Int): Boolean {
    val d1 = side(p, q, r)
    val d2 = side(p, q, s)
    val d3 = side(r, s, p)
    val d4 = side(r, s, q)
    return (d1 > 0 && d2 < 0 || d1 < 0 && d2 > 0) && (d3 > 0 && d4 < 0 || d3 < 0 && d4 > 0)
  }

  // знак поворота o → p → q в плане
  private fun side(o: Int, p: Int, q: Int): Double {
    val po = coords[o ushr CHUNK_SHIFT]!!
    val pp = coords[p ushr CHUNK_SHIFT]!!
    val pq = coords[q ushr CHUNK_SHIFT]!!
    val io = (o and CHUNK_MASK) * 3
    val ip = (p and CHUNK_MASK) * 3
    val iq = (q and CHUNK_MASK) * 3
    return (pp[ip] - po[io]) * (pq[iq + 1] - po[io + 1]) - (pp[ip + 1] - po[io + 1]) * (pq[iq] - po[io])
  }

  // треугольник с видимыми рёбрами [edges]; по часовой в плане — разворот b ↔ c, рёбра переставляются вместе с ним
  private fun addTriangle(a: Int, b: Int, c: Int, edges: Int) {
    val pa = coords[a ushr CHUNK_SHIFT]!!
    val pb = coords[b ushr CHUNK_SHIFT]!!
    val pc = coords[c ushr CHUNK_SHIFT]!!
    val ia = (a and CHUNK_MASK) * 3
    val ib = (b and CHUNK_MASK) * 3
    val ic = (c and CHUNK_MASK) * 3
    val ax = pa[ia]
    val ay = pa[ia + 1]
    val ux = pb[ib] - ax
    val uy = pb[ib + 1] - ay
    val vx = pc[ic] - ax
    val vy = pc[ic + 1] - ay
    val cross = ux * vy - uy * vx
    var second = b
    var third = c
    var mask = edges
    // щель (вершины в долях миллиметра, площадь — шум округления) не разворачивается
    if (cross < -FLAT * (ux * ux + uy * uy + vx * vx + vy * vy)) {
      flipped++
      second = c
      third = b
      // a-c (бывшее c-a), c-b (b-c), b-a (a-b)
      mask = (if (edges and EDGE_CA != 0) EDGE_AB else 0) or (edges and EDGE_BC) or (if (edges and EDGE_AB != 0) EDGE_CA else 0)
    }
    if (triangleCount and CHUNK_MASK == 0) triangles = addChunk(triangles, triangleCount, IntArray(CHUNK * 3))
    val block = triangles[triangleCount ushr CHUNK_SHIFT]!!
    val at = (triangleCount and CHUNK_MASK) * 3
    block[at] = a
    block[at + 1] = second
    block[at + 2] = third
    var masks = edgeMask
    if (mask != ALL_EDGES && masks == null) {
      masks = ByteArray(maxOf(triangleCount * 2, MIN_CAPACITY)).also { it.fill(ALL_EDGES.toByte()) }
      edgeMask = masks
    }
    if (masks != null) {
      if (triangleCount >= masks.size) {
        val old = masks.size
        masks = masks.copyOf(old * 2).also { it.fill(ALL_EDGES.toByte(), old) }
        edgeMask = masks
      }
      masks[triangleCount] = mask.toByte()
    }
    triangleCount++
  }

  // сетка из вершин видимых граней: неиспользованные (только в невидимых гранях, повторы id) выбрасываются
  private fun addMesh(
    scene: LandXmlSceneBuilder, name: String, layerName: String, desc: String, state: String, type: String, visible: Int,
    area2D: Double, area3D: Double, elevMin: Double, elevMax: Double,
  ) {
    // таблица id больше не нужна; ids — тоже: в них отметки «вершина в деле», затем новые номера по порядку файла
    dense = null
    hashKeys = IntArray(0)
    hashValues = IntArray(0)
    val remap = ids
    remap.fill(-1, 0, count)
    val indexCount = triangleCount * 3
    for (t in 0 until triangleCount step CHUNK) {
      val block = triangles[t ushr CHUNK_SHIFT]!!
      for (j in 0 until minOf(CHUNK, triangleCount - t) * 3) remap[block[j]] = 0
    }
    var used = 0
    for (v in 0 until count) if (remap[v] == 0) remap[v] = used++

    // куски — в итоговые массивы; прочитанный кусок отпускается сразу
    val meshCoords = DoubleArray(used * 3)
    val all = used == count
    var first = true
    for (v in 0 until count) {
      if (v > 0 && v and CHUNK_MASK == 0) coords[(v ushr CHUNK_SHIFT) - 1] = null
      val to = remap[v]
      if (to < 0) continue
      val block = coords[v ushr CHUNK_SHIFT]!!
      val from = (v and CHUNK_MASK) * 3
      val x = block[from]
      val y = block[from + 1]
      val z = block[from + 2]
      meshCoords[to * 3] = x
      meshCoords[to * 3 + 1] = y
      meshCoords[to * 3 + 2] = z
      // все точки в деле — габариты из прохода чтения
      if (all) continue
      if (first) {
        first = false
        minX = x; maxX = x; minY = y; maxY = y; minZ = z; maxZ = z
      } else {
        if (x < minX) minX = x else if (x > maxX) maxX = x
        if (y < minY) minY = y else if (y > maxY) maxY = y
        if (z < minZ) minZ = z else if (z > maxZ) maxZ = z
      }
    }
    coords = arrayOfNulls(CHUNKS)
    val meshTriangles = IntArray(indexCount)
    var k = 0
    var chunk = 0
    while (k < indexCount) {
      val block = triangles[chunk]!!
      triangles[chunk++] = null
      val n = minOf(block.size, indexCount - k)
      if (all) {
        block.copyInto(meshTriangles, k, 0, n)
      } else {
        for (j in 0 until n) meshTriangles[k + j] = remap[block[j]]
      }
      k += n
    }

    val color = ScenePalette.surface(surfaces++)
    val layer = scene.layer(layerName, color, LandXmlGroup.SURFACES)
    val attributes = ArrayList<SceneAttribute>(10)
    if (desc.isNotBlank()) attributes.add(SceneAttribute("@attr_description", desc))
    attributes.add(SceneAttribute("@attr_type", type))
    if (state.isNotBlank()) attributes.add(SceneAttribute("@attr_state", state))
    if (!area2D.isNaN()) attributes.add(SceneAttribute("@attr_area_2d", AlignmentsSection.format(area2D)))
    if (!area3D.isNaN()) attributes.add(SceneAttribute("@attr_area_3d", AlignmentsSection.format(area3D)))
    if (!elevMin.isNaN()) attributes.add(SceneAttribute("@attr_elev_min", AlignmentsSection.format(elevMin)))
    if (!elevMax.isNaN()) attributes.add(SceneAttribute("@attr_elev_max", AlignmentsSection.format(elevMax)))
    attributes.add(SceneAttribute("@attr_points", points.toString()))
    attributes.add(SceneAttribute("@attr_faces", faces.toString()))
    if (invisible > 0) attributes.add(SceneAttribute("@attr_invisible_faces", invisible.toString()))
    if (visible != faces) attributes.add(SceneAttribute("@attr_visible_faces", visible.toString()))
    val objectId = scene.addObject(SceneObject(KIND, name, layer, color, attributes))
    pending = SceneMesh(
      layer, meshCoords,
      meshTriangles,
      minX, minY, minZ, maxX, maxY, maxZ,
      edgeMask = edgeMask?.let { if (it.size == triangleCount) it else it.copyOf(triangleCount) },
      objectId = objectId,
    )
  }

  private fun uniqueLayer(name: String): String {
    var result = name
    var k = 2
    while (!layerNames.add(result)) result = "$name (${k++})"
    return result
  }

  private fun attrNumber(xml: XmlTokenizer, name: ByteArray): Double =
    if (xml.findAttr(name)) LandXmlNumbers.parseDouble(xml.valueBuffer, xml.valueStart, xml.valueEnd) else Double.NaN

  private fun reset() {
    count = 0
    maxId = 0
    triangleCount = 0
    edgeMask = null
    dense = null
    points = 0
    faces = 0
    invisible = 0
    badFaces = 0
    degenerate = 0
    flipped = 0
    badPoints = 0
    duplicates = 0
    empty = 0
    noData = 0
    reordered = 0
  }

  // массивы отданы сетке или больше не нужны — следующая поверхность заводит свои
  private fun release() {
    coords = arrayOfNulls(CHUNKS)
    ids = IntArray(0)
    triangles = arrayOfNulls(CHUNKS)
    edgeMask = null
    dense = null
    hashKeys = IntArray(0)
    hashValues = IntArray(0)
  }

  companion object {
    const val KIND = "Surface"
    const val DEFAULT_NAME = "@layer_surface"

    // сначала имена исходных данных: их номера — места в SurfaceSourceData.NAMES
    private val NAMES = XmlNames(*SurfaceSourceData.NAMES, "Surfaces", "Surface", "Definition", "Pnts", "P", "Faces", "F")
    private val SURFACE = SurfaceSourceData.NAMES.size + 1
    private val DEFINITION = SurfaceSourceData.NAMES.size + 2
    private val PNTS = SurfaceSourceData.NAMES.size + 3
    private val P = SurfaceSourceData.NAMES.size + 4
    private val FACES = SurfaceSourceData.NAMES.size + 5
    private val F = SurfaceSourceData.NAMES.size + 6

    private val NAME = "name".encodeToByteArray()
    private val DESC = "desc".encodeToByteArray()
    private val STATE = "state".encodeToByteArray()
    private val SURF_TYPE = "surfType".encodeToByteArray()
    private val AREA_2D = "area2DSurf".encodeToByteArray()
    private val AREA_3D = "area3DSurf".encodeToByteArray()
    private val ELEV_MIN = "elevMin".encodeToByteArray()
    private val ELEV_MAX = "elevMax".encodeToByteArray()
    private val ID = "id".encodeToByteArray()
    private val I = "i".encodeToByteArray()
    private const val ONE = '1'.code.toByte()

    // биты рёбер как у SceneMesh.edgeMask
    private const val EDGE_AB = 1
    private const val EDGE_BC = 2
    private const val EDGE_CA = 4
    private const val ALL_EDGES = 7
    // грань «по часовой», если удвоенная площадь в плане меньше −FLAT · (сумма квадратов сторон)
    private const val FLAT = 1e-9

    // отметки-заглушки «нет данных» у grid: -9999, -99999, -999999, 99999 и по модулю от 1e30 (float.max и т. п.)
    private fun isNoData(z: Double) = z == -9999.0 || z == -99999.0 || z == -999999.0 || z == 99999.0 || z >= 1e30 || z <= -1e30

    // плотная таблица id, пока maxId не больше стольких точек на одну
    private const val DENSE_FACTOR = 4
    private const val MIN_CAPACITY = 1024
    // кусок — 16384 точки (384 КБ координат) или треугольника (192 КБ индексов); таблица кусков — с 16 мест
    private const val CHUNK_SHIFT = 14
    private const val CHUNK = 1 shl CHUNK_SHIFT
    private const val CHUNK_MASK = CHUNK - 1
    private const val CHUNKS = 16
    private const val MAX_CAPACITY = 50_000_000
    // «<P id="…">» и «</P>» с отступом
    private const val P_MARKUP = 24

    private fun mix(id: Int): Int {
      val h = id * -0x61c88647
      return h xor (h ushr 16)
    }
  }
}
