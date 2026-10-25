package dev.iskandern.kotin

import dev.iskandern.kotin.model.SceneObject
import dev.iskandern.kotin.scene.RenderLabel
import dev.iskandern.kotin.scene.RenderScene
import dev.iskandern.kotin.scene.RenderSurface
import dev.iskandern.kotin.scene.buildRenderScene
import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ObjectPickerTest {

  private val fov = 45.0
  private val width = 800f
  private val height = 600f

  // вид строго сверху на центр сцены: вправо — +X, вверх по экрану — север (−Z сцены)
  private val topDown = CameraPose(0f, 0f, 0f, 20f, 0f, (PI / 2).toFloat())

  private fun doc(vararg entities: Array<String>) = readGeometry(
    "0", "SECTION", "2", "BLOCKS",
    "0", "BLOCK", "8", "0", "2", "B", "70", "0", "10", "0", "20", "0", "30", "0",
    "0", "LINE", "8", "0", "10", "0", "20", "0", "11", "1", "21", "0",
    "0", "LINE", "8", "0", "10", "0", "20", "1", "11", "1", "21", "1",
    "0", "ENDBLK", "8", "0",
    "0", "ENDSEC",
    "0", "SECTION", "2", "ENTITIES", *entities.flatten().toTypedArray(), "0", "ENDSEC", "0", "EOF",
  )

  private fun line(x1: Int, y1: Int, x2: Int, y2: Int, layer: String = "0") =
    arrayOf("0", "LINE", "8", layer, "10", "$x1", "20", "$y1", "11", "$x2", "21", "$y2")

  private fun insert(x: Int, y: Int) = arrayOf("0", "INSERT", "8", "0", "2", "B", "10", "$x", "20", "$y", "30", "0")

  private fun face(layer: String = "TIN") = arrayOf(
    "0", "3DFACE", "8", layer,
    "10", "0", "20", "0", "30", "0", "11", "10", "21", "0", "31", "0", "12", "10", "22", "10", "32", "0", "13", "0", "23", "10", "33", "0",
  )

  private fun scene(vararg entities: Array<String>) = buildRenderScene(doc(*entities))

  /** Луч через точку сцены ([x], [y], [z]) при позе [pose], сдвинутой на ([dx], [dy]) пикселей. */
  private fun rayAt(pose: CameraPose, x: Float, y: Float, z: Float, dx: Float = 0f, dy: Float = 0f): PickRay {
    val (px, py) = assertNotNull(pose.project(fov, x, y, z, width, height))
    return pose.pickRay(fov, px + dx, py + dy, width, height)
  }

  private fun kindOf(scene: RenderScene, hit: PickHit?) = hit?.let { scene.objects[it.objectId].kind }

  /** Середина первого отрезка пакета [batch] сцены. */
  private fun midpoint(scene: RenderScene, batch: Int = 0, segment: Int = 0): FloatArray {
    val lines = scene.lines[batch]
    val a = lines.indices[segment * 2] * 3
    val b = lines.indices[segment * 2 + 1] * 3
    return FloatArray(3) { (lines.positions[a + it] + lines.positions[b + it]) / 2 }
  }

  @Test
  fun `центральный луч проходит через точку интереса, проекция обратна лучу`() {
    val pose = CameraPose(3f, 4f, -5f, 50f, 0.7f, 0.5f)
    val ray = pose.pickRay(fov, width / 2, height / 2, width, height)
    val through = doubleArrayOf(ray.ox + ray.dx * 50, ray.oy + ray.dy * 50, ray.oz + ray.dz * 50)
    assertEquals(3.0, through[0], 1e-3)
    assertEquals(4.0, through[1], 1e-3)
    assertEquals(-5.0, through[2], 1e-3)

    // точка на луче через (200, 100) проецируется обратно в (200, 100)
    val corner = pose.pickRay(fov, 200f, 100f, width, height)
    val point = doubleArrayOf(corner.ox + corner.dx * 70, corner.oy + corner.dy * 70, corner.oz + corner.dz * 70)
    val (px, py) = assertNotNull(pose.project(fov, point[0].toFloat(), point[1].toFloat(), point[2].toFloat(), width, height))
    assertEquals(200f, px, 0.05f)
    assertEquals(100f, py, 0.05f)
  }

  @Test
  fun `сверху вправо по экрану - на восток, вверх - на север`() {
    val ray = topDown.pickRay(fov, width / 2 + 100, height / 2 - 100, width, height)
    assertTrue(ray.dx > 0, "правее центра — восток (+X)")
    assertTrue(ray.dz < 0, "выше центра — север (−Z)")
    assertEquals(-1.0, ray.dy, 0.1)
  }

  @Test
  fun `луч по линии выбирает её объект, мимо - ничего`() {
    val scene = scene(line(0, 0, 10, 0), line(0, 5, 10, 5))
    val mid = midpoint(scene, 0, 0)
    val ids = scene.lines[0].objectIds!!

    val hit = pickObject(scene, ViewSettings(), rayAt(topDown, mid[0], mid[1], mid[2]))
    assertEquals("LINE", kindOf(scene, hit))
    assertEquals(ids[0], hit!!.objectId)

    // в пределах окрестности попадания (10 dp при плотности 1) линия ловится и не по центру
    assertEquals(ids[0], pickObject(scene, ViewSettings(), rayAt(topDown, mid[0], mid[1], mid[2], dy = 6f))?.objectId)
    // далеко от обеих линий — ничего
    assertNull(pickObject(scene, ViewSettings(), rayAt(topDown, mid[0], mid[1], mid[2], dy = 60f)))
  }

  @Test
  fun `из двух линий рядом выбирается та, что ближе к лучу`() {
    val scene = scene(line(0, 0, 10, 0), line(0, 1, 10, 1))
    val lower = midpoint(scene, 0, 0)
    val ids = scene.lines[0].objectIds!!
    // сверху 1 м = ~36 px; ближе к первой линии — выбираем первую, ближе ко второй — вторую
    val nearFirst = pickObject(scene, ViewSettings(), rayAt(topDown, lower[0], lower[1], lower[2], dy = -6f), density = 3f)
    val nearSecond = pickObject(scene, ViewSettings(), rayAt(topDown, lower[0], lower[1], lower[2], dy = -30f), density = 3f)
    assertEquals(ids[0], nearFirst?.objectId)
    assertEquals(ids[1], nearSecond?.objectId)
  }

  @Test
  fun `вставка блока выбирается целиком по любому её отрезку`() {
    val scene = scene(insert(0, 0), line(20, 0, 30, 0))
    val ids = scene.lines[0].objectIds!!
    // у блока два отрезка (0,0)-(1,0) и (0,1)-(1,1); вторая вершина — любой из них
    for (segment in 0 until 2) {
      val mid = midpoint(scene, 0, segment)
      val hit = pickObject(scene, ViewSettings(), rayAt(topDown, mid[0], mid[1], mid[2]))
      val info = scene.objects[assertNotNull(hit).objectId]
      assertEquals("INSERT", info.kind)
      assertEquals("B", info.name)
    }
    assertEquals(ids[0], ids[1])
  }

  @Test
  fun `скрытый слой и выключенный чертёж не выбираются`() {
    val scene = scene(line(0, 0, 10, 0, layer = "L1"), line(0, 5, 10, 5, layer = "L2"))
    val index = scene.lines.indexOfFirst { it.layer == "L1" }
    val mid = midpoint(scene, index, 0)
    val ray = rayAt(topDown, mid[0], mid[1], mid[2])

    assertNotNull(pickObject(scene, ViewSettings(), ray))
    assertNull(pickObject(scene, ViewSettings(hiddenLayers = setOf("L1")), ray))
    assertNull(pickObject(scene, ViewSettings(showLines = false), ray))
  }

  @Test
  fun `поверхность выбирается внутри грани, а линия поверх неё - по линии`() {
    val scene = scene(face(), line(2, 2, 8, 2))
    val mid = midpoint(scene, 0, 0)

    val onLine = pickObject(scene, ViewSettings(), rayAt(topDown, mid[0], mid[1], mid[2]))
    assertEquals("LINE", kindOf(scene, onLine))

    // в стороне от линии (80 px вверх, на север, при ~36 px/м — ещё внутри грани) — только грань
    val onSurface = pickObject(scene, ViewSettings(), rayAt(topDown, mid[0], mid[1], mid[2], dy = -80f))
    assertEquals("3DFACE", kindOf(scene, onSurface))

    assertNull(pickObject(scene, ViewSettings(showSurface = false, showWire = false), rayAt(topDown, mid[0], mid[1], mid[2], dy = -80f)))
  }

  @Test
  fun `подпись ловится по строке, а не только по точке вставки`() {
    val text = arrayOf("0", "TEXT", "8", "0", "10", "0", "20", "0", "30", "0", "40", "1", "1", "abcd")
    val scene = scene(text)
    val label = scene.labels.single()
    // сверху высота 1 м ≈ 36 px: строка из 4 знаков идёт вправо от точки вставки примерно на 86 px
    val anchor = floatArrayOf(label.x, label.y, label.z)
    assertEquals("TEXT", kindOf(scene, pickObject(scene, ViewSettings(), rayAt(topDown, anchor[0], anchor[1], anchor[2]))))
    assertEquals("TEXT", kindOf(scene, pickObject(scene, ViewSettings(), rayAt(topDown, anchor[0], anchor[1], anchor[2], dx = 60f, dy = -15f))))
    // левее точки вставки и далеко над строкой — мимо
    assertNull(pickObject(scene, ViewSettings(), rayAt(topDown, anchor[0], anchor[1], anchor[2], dx = -80f)))
    assertNull(pickObject(scene, ViewSettings(), rayAt(topDown, anchor[0], anchor[1], anchor[2], dx = 60f, dy = -120f)))
    assertNull(pickObject(scene, ViewSettings(showLabels = false), rayAt(topDown, anchor[0], anchor[1], anchor[2])))
  }

  @Test
  fun `точка выбирается в окрестности значка`() {
    val point = arrayOf("0", "POINT", "8", "0", "10", "0", "20", "0", "30", "0")
    val scene = scene(point, line(10, 0, 20, 0))
    val p = scene.points.single()
    val at = floatArrayOf(p.x, p.y, p.z)
    assertEquals("POINT", kindOf(scene, pickObject(scene, ViewSettings(), rayAt(topDown, at[0], at[1], at[2], dx = 8f))))
    // радиус значка — 20 dp: ловится и в 18 px, в 24 px — уже мимо
    assertEquals("POINT", kindOf(scene, pickObject(scene, ViewSettings(), rayAt(topDown, at[0], at[1], at[2], dy = 18f))))
    assertNull(pickObject(scene, ViewSettings(), rayAt(topDown, at[0], at[1], at[2], dy = 24f)))
    assertNull(pickObject(scene, ViewSettings(showPoints = false), rayAt(topDown, at[0], at[1], at[2])))
  }

  @Test
  fun `кандидаты под нажатием - по классу размера, точка первой, рельеф только без других`() {
    // точка на линии, линия на грани: под нажатием точка и линия, лучший — точка; рельеф под ними в список не идёт
    val point = arrayOf("0", "POINT", "8", "0", "10", "5", "20", "2", "30", "0")
    val scene = scene(face(), line(2, 2, 8, 2), point)
    val p = scene.points.single()
    val hits = pickCandidates(scene, ViewSettings(), rayAt(topDown, p.x, p.y, p.z, dx = 4f))
    assertEquals(listOf("POINT", "LINE"), hits.map { scene.objects[it.objectId].kind })
    assertEquals(listOf(RANK_POINT, RANK_LINE), hits.map { it.rank })
    assertEquals("POINT", kindOf(scene, pickObject(scene, ViewSettings(), rayAt(topDown, p.x, p.y, p.z, dx = 4f))))
    // место попадания точки — её значок, линии — точка на ней под лучом
    assertEquals(Triple(p.x, p.y, p.z), hits[0].let { Triple(it.x, it.y, it.z) })
    assertEquals(p.z, hits[1].z, 0.05f)
    // в стороне от точки и линии — только грань
    val far = pickCandidates(scene, ViewSettings(), rayAt(topDown, p.x, p.y, p.z, dy = -80f))
    assertEquals(listOf("3DFACE"), far.map { scene.objects[it.objectId].kind })
    assertTrue(pickCandidates(scene, ViewSettings(showSurface = false, showWire = false, showLines = false, showPoints = false), rayAt(topDown, p.x, p.y, p.z)).isEmpty())
  }

  @Test
  fun `вертикальный масштаб учитывается при пересечении с поверхностью`() {
    // треугольник на высоте 10 в координатах сцены: при масштабе 2 он на 20, луч сверху вниз из y = 100
    val surface = RenderSurface(
      layer = "S",
      positions = floatArrayOf(-5f, 10f, -5f, 5f, 10f, -5f, 0f, 10f, 5f),
      normals = FloatArray(9),
      uvs = FloatArray(6),
      indices = intArrayOf(0, 1, 2),
      edges = intArrayOf(),
      objectId = 0,
    )
    val scene = RenderScene(
      listOf(surface), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(),
      truncated = false, drawingFlattened = false, originX = 0.0, originY = 0.0, originZ = 0.0, halfX = 5f, halfY = 10f, halfZ = 5f,
      objects = listOf(SceneObject("TIN", "поверхность", "S")),
    )
    val down = PickRay(0.0, 100.0, 0.0, 0.0, -1.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, -1.0, 0.0, 1.0, 0.0, 0.001)

    val flat = pickObject(scene, ViewSettings(verticalScale = 1f), down)
    val scaled = pickObject(scene, ViewSettings(verticalScale = 2f), down)
    assertEquals(90.0, assertNotNull(flat).distance, 1e-6)
    assertEquals(80.0, assertNotNull(scaled).distance, 1e-6)
    assertTrue(abs(flat.distance - scaled.distance) > 1)
  }

  @Test
  fun `сборка выбранного объекта даёт все его отрезки и только их`() {
    val scene = scene(insert(0, 0), line(20, 0, 30, 0))
    val ids = scene.lines[0].objectIds!!
    val selection = collectObject(scene, ids[0])
    // два отрезка вставки по шесть чисел; чужая линия не попала
    assertEquals(12, selection.segments.size)
    assertTrue(selection.triangles.isEmpty())
    assertTrue(selection.anchors.isEmpty())

    val other = collectObject(scene, ids.last())
    assertEquals(6, other.segments.size)
  }

  @Test
  fun `сборка подписи даёт якорь, поверхность - отметку без копии`() {
    val text = arrayOf("0", "TEXT", "8", "0", "10", "0", "20", "0", "30", "0", "40", "1", "1", "abc")
    val scene = scene(face(), text)
    val surface = scene.objects.indexOfFirst { it.kind == "3DFACE" }
    val label = scene.objects.indexOfFirst { it.kind == "TEXT" }
    // поверхность целиком отмечается, но не копируется (см. отдельный тест)
    assertTrue(collectObject(scene, surface).triangles.isEmpty())
    assertEquals(3, collectObject(scene, label).anchors.size)
  }

  @Test
  fun `у заливки собирается контур - внешние рёбра без внутренних, у линии контура нет`() {
    // полилиния шириной 1 из двух участков — лента из треугольников
    val wide = arrayOf("0", "LWPOLYLINE", "8", "0", "90", "3", "43", "1", "10", "0", "20", "0", "10", "10", "20", "0", "10", "20", "20", "0")
    val scene = scene(wide, line(0, 10, 10, 10))
    val polyline = scene.objects.indexOfFirst { it.kind == "POLYLINE" }
    val selection = collectObject(scene, polyline)
    assertTrue(selection.triangles.isNotEmpty())
    val segments = selection.outline.size / 6
    // каждый отрезок контура — на краю ленты: по Z (поперёк, ширина 1) на ±0,5 от оси у обоих концов или торец
    assertTrue(segments >= 4, "отрезков контура: $segments")
    assertTrue(segments < selection.triangles.size / 9 * 3, "внутренние рёбра не попали в контур")
    val line = scene.objects.indexOfFirst { it.kind == "LINE" }
    assertTrue(collectObject(scene, line).outline.isEmpty())
  }

  @Test
  fun `сборка точки даёт саму точку, а не якорь`() {
    val scene = scene(arrayOf("0", "POINT", "8", "0", "10", "1", "20", "2", "30", "3"))
    val selection = collectObject(scene, scene.objects.indexOfFirst { it.kind == "POINT" })
    assertEquals(listOf(scene.points[0]), selection.points)
    assertTrue(selection.anchors.isEmpty())
  }

  @Test
  fun `в сцене без объектов выбирать нечего`() {
    val scene = RenderScene(
      emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(),
      truncated = false, drawingFlattened = false, originX = 0.0, originY = 0.0, originZ = 0.0, halfX = 1f, halfY = 1f, halfZ = 1f,
    )
    assertNull(pickObject(scene, ViewSettings(), topDown.pickRay(fov, 10f, 10f, width, height)))
  }

  @Test
  fun `скрытая поверхность не выбирается, при видимом каркасе выбираются его рёбра`() {
    val scene = scene(face())
    val surface = scene.surfaces.single()
    val center = floatArrayOf(0f, surface.positions[1], 0f)
    val wireOnly = ViewSettings(showSurface = false, showWire = true)

    // внутри грани, в стороне от рёбер, при выключенной поверхности — ничего (раньше выбиралась вся поверхность)
    assertNull(pickObject(scene, wireOnly, rayAt(topDown, center[0], center[1], center[2], dx = 100f)))
    assertEquals("3DFACE", kindOf(scene, pickObject(scene, ViewSettings(), rayAt(topDown, center[0], center[1], center[2], dx = 100f))))

    // по самому ребру каркаса — выбирается
    val a = surface.edges[0] * 3
    val b = surface.edges[1] * 3
    val edge = FloatArray(3) { (surface.positions[a + it] + surface.positions[b + it]) / 2 }
    assertEquals("3DFACE", kindOf(scene, pickObject(scene, wireOnly, rayAt(topDown, edge[0], edge[1], edge[2]))))

    // и каркас, и поверхность скрыты, или скрыт слой — нечего выбирать
    val hidden = ViewSettings(showSurface = false, showWire = false)
    assertNull(pickObject(scene, hidden, rayAt(topDown, edge[0], edge[1], edge[2])))
    assertNull(pickObject(scene, ViewSettings(hiddenLayers = setOf("TIN")), rayAt(topDown, center[0], center[1], center[2])))
  }

  // поверхность без объекта и подпись под ней; сверху глаз на высоте 20, поверхность на 10
  private fun roofScene(labelY: Float): RenderScene {
    val roof = RenderSurface(
      layer = "S",
      positions = floatArrayOf(-50f, 10f, -50f, 50f, 10f, -50f, 0f, 10f, 50f),
      normals = FloatArray(9), uvs = FloatArray(6), indices = intArrayOf(0, 1, 2), edges = intArrayOf(),
    )
    val label = RenderLabel("L", 0xFFFFFF, 0f, labelY, 0f, 1f, 1f, 0f, "abc", objectId = 0)
    return RenderScene(
      listOf(roof), emptyList(), emptyList(), listOf(label), emptyList(), emptyList(),
      truncated = false, drawingFlattened = false, originX = 0.0, originY = 0.0, originZ = 0.0, halfX = 50f, halfY = 10f, halfZ = 50f,
      objects = listOf(SceneObject("TEXT", "abc", "L")),
    )
  }

  @Test
  fun `горизонтальная на экране подпись выбирается по горизонтальному прямоугольнику`() {
    // строка на север: на виде сверху без флага идёт вверх по экрану, с флагом — вправо
    fun scene(horizontal: Boolean) = RenderScene(
      emptyList(), emptyList(), emptyList(),
      listOf(RenderLabel("L", 0xFFFFFF, 0f, 0f, 0f, 0.01f, 0f, -1f, "abcdefghijklmnopqrst", objectId = 0, screenHorizontal = horizontal)),
      emptyList(), emptyList(),
      truncated = false, drawingFlattened = false, originX = 0.0, originY = 0.0, originZ = 0.0, halfX = 1f, halfY = 1f, halfZ = 1f,
      objects = listOf(SceneObject("TEXT", "abc", "L")),
    )
    val right = rayAt(topDown, 0f, 0f, 0f, dx = 40f)
    val above = rayAt(topDown, 0f, 0f, 0f, dy = -40f)
    assertEquals("TEXT", kindOf(scene(true), pickObject(scene(true), ViewSettings(), right)))
    assertNull(pickObject(scene(true), ViewSettings(), above))
    assertEquals("TEXT", kindOf(scene(false), pickObject(scene(false), ViewSettings(), above)))
    assertNull(pickObject(scene(false), ViewSettings(), right))
  }

  @Test
  fun `строки столбика в одной точке выбираются по своему подъёму, сдвиг строки учитывается`() {
    // две подписи LandXML (кегль в dp, высота < 0) в одной точке: «23» поднята на 8 dp, «23_BA» опущена на 8 dp
    fun label(text: String, id: Int, lift: Float, shift: Float = 0f) = RenderLabel(
      "L", 0xFFFFFF, 0f, 0f, 0f, -9.5f, 0f, -1f, text, alignH = 1, alignV = 2, objectId = id, screenHorizontal = true,
      liftDp = lift, shiftDp = shift,
    )
    fun scene(vararg labels: RenderLabel) = RenderScene(
      emptyList(), emptyList(), emptyList(), labels.toList(), emptyList(), emptyList(),
      truncated = false, drawingFlattened = false, originX = 0.0, originY = 0.0, originZ = 0.0, halfX = 1f, halfY = 1f, halfZ = 1f,
      objects = listOf(SceneObject("Parcel", "23", "L"), SceneObject("Parcel", "23_BA", "L")),
    )
    val column = scene(label("23", 0, 8f), label("23_BA", 1, -8f))
    // вверх по экрану — верхняя строка, вниз — нижняя
    assertEquals(0, pickObject(column, ViewSettings(), rayAt(topDown, 0f, 0f, 0f, dy = -8f))?.objectId)
    assertEquals(1, pickObject(column, ViewSettings(), rayAt(topDown, 0f, 0f, 0f, dy = 8f))?.objectId)
    // строка, сдвинутая на 30 dp вправо от точки: в точке — мимо, правее — попадание
    val shifted = scene(label("23", 0, 0f, shift = 30f))
    assertNull(pickObject(shifted, ViewSettings(), rayAt(topDown, 0f, 0f, 0f)))
    assertEquals(0, pickObject(shifted, ViewSettings(), rayAt(topDown, 0f, 0f, 0f, dx = 30f))?.objectId)
  }

  @Test
  fun `столбик подписей на рельефе при наклонной камере выбирается по строкам, а не поверхность под ними`() {
    // грань 10 × 10 на нулевой отметке (в сцене — вокруг начала координат); в её середине столбик участков «15» и
    // «15_BA» (кегль в dp, строки на ±8 dp от точки), посаженный на рельеф
    val base = scene(face())
    fun label(text: String, id: Int, lift: Float) = RenderLabel(
      "L", 0xFFFFFF, 0f, 0f, 0f, -9.5f, 0f, -1f, text, alignH = 1, alignV = 2, objectId = id, screenHorizontal = true, liftDp = lift,
    )
    val scene = RenderScene(
      base.surfaces, emptyList(), emptyList(), listOf(label("15", 1, 8f), label("15_BA", 2, -8f)), emptyList(), emptyList(),
      truncated = false, drawingFlattened = false, originX = 0.0, originY = 0.0, originZ = 0.0, halfX = 5f, halfY = 1f, halfZ = 5f,
      objects = listOf(base.objects[0], SceneObject("Parcel", "15", "L"), SceneObject("Parcel", "15_BA", "L")),
    )
    // камера под 40° к горизонту: под нижней строкой рельеф на 1,3 % ближе к камере, чем плоскость подписи, —
    // больше запаса WIRE_BIAS, но строка видна (не глубже подтяжки плашки) и должна победить
    val oblique = CameraPose(0f, 0f, 0f, 20f, 0f, 0.7f)
    assertEquals(1, pickObject(scene, ViewSettings(), rayAt(oblique, 0f, 0f, 0f, dy = -8f))?.objectId)
    assertEquals(2, pickObject(scene, ViewSettings(), rayAt(oblique, 0f, 0f, 0f, dy = 8f))?.objectId)
    // в стороне от строк — поверхность
    assertEquals(0, pickObject(scene, ViewSettings(), rayAt(oblique, 0f, 0f, 0f, dx = 60f))?.objectId)
  }

  @Test
  fun `подпись под поверхностью скрыта и не выбирается, при выключенном скрытии - выбирается`() {
    val scene = roofScene(labelY = 3f)
    val ray = rayAt(topDown, 0f, 3f, 0f)

    // у поверхности нет объекта, но она всё равно загораживает подпись
    assertNull(pickObject(scene, ViewSettings(), ray))
    assertEquals("TEXT", kindOf(scene, pickObject(scene, ViewSettings(hideOccluded = false), ray)))
    // поверхность скрыта — загораживать нечему
    assertEquals("TEXT", kindOf(scene, pickObject(scene, ViewSettings(showSurface = false), ray)))
  }

  @Test
  fun `подпись у самой поверхности не считается скрытой`() {
    // на полметра под поверхностью, но шейдер подтягивает подпись к камере на её высоту — она видна
    val scene = roofScene(labelY = 9.5f)
    assertEquals("TEXT", kindOf(scene, pickObject(scene, ViewSettings(), rayAt(topDown, 0f, 9.5f, 0f))))
  }

  @Test
  fun `выбранный объект виден, пока показана хоть одна его часть и не скрыт слой`() {
    val text = arrayOf("0", "TEXT", "8", "T", "10", "0", "20", "0", "30", "0", "40", "1", "1", "abc")
    val scene = scene(insert(0, 0), text, face())
    fun selection(kind: String) = scene.objects.indexOfFirst { it.kind == kind }.let { id -> collectObject(scene, id) to scene.objects[id].layer }

    val (lines, linesLayer) = selection("INSERT")
    assertTrue(lines.isVisibleIn(ViewSettings(), linesLayer))
    assertTrue(!lines.isVisibleIn(ViewSettings(showLines = false), linesLayer))
    assertTrue(!lines.isVisibleIn(ViewSettings(hiddenLayers = setOf(linesLayer)), linesLayer))
    // подписи выключены — на линии это не влияет
    assertTrue(lines.isVisibleIn(ViewSettings(showLabels = false), linesLayer))

    val (label, labelLayer) = selection("TEXT")
    assertTrue(label.isVisibleIn(ViewSettings(), labelLayer))
    assertTrue(!label.isVisibleIn(ViewSettings(showLabels = false), labelLayer))

    val (surface, surfaceLayer) = selection("3DFACE")
    assertTrue(surface.isVisibleIn(ViewSettings(showSurface = false, showWire = true), surfaceLayer))
    assertTrue(!surface.isVisibleIn(ViewSettings(showSurface = false, showWire = false), surfaceLayer))
  }

  private fun onLayer(layer: String, entity: Array<String>) = entity.copyOf().also { it[3] = layer }

  @Test
  fun `слой объекта из карточки совпадает со слоем его примитивов - по нему подсвечивается слой`() {
    // подсветка слоя — параметры материалов пакетов, сравнивающие слой пакета со слоем объекта (SceneView)
    val scene = scene(onLayer("A", insert(0, 0)), line(20, 0, 30, 0, layer = "B"))
    val index = scene.lines.indexOfFirst { it.layer == "A" }
    val mid = midpoint(scene, index, 0)
    val hit = assertNotNull(pickObject(scene, ViewSettings(), rayAt(topDown, mid[0], mid[1], mid[2])))
    val info = scene.objects[hit.objectId]
    assertEquals("A", info.layer)
    assertEquals(scene.lines[index].layer, info.layer)
    // у пакета другого слоя слой другой: подсветка слоя его не заденет
    assertEquals(setOf("A", "B"), scene.lines.map { it.layer }.toSet())
  }

  @Test
  fun `объект на всю поверхность не копируется - подсвечивается параметром материала`() {
    val scene = scene(face())
    val surface = scene.objects.indexOfFirst { it.kind == "3DFACE" }
    val selection = collectObject(scene, surface)
    assertEquals(surface, selection.objectId)
    assertTrue(selection.triangles.isEmpty(), "дубликата треугольников быть не должно")
    assertTrue(selection.parts and PART_SURFACE != 0, "но поверхность отмечена как часть объекта")
    assertTrue(selection.isVisibleIn(ViewSettings(), scene.objects[surface].layer))
  }

  @Test
  fun `у поверхности с объектами по треугольникам копируются только треугольники объекта`() {
    // сетка из двух треугольников, объекты 0 и 1 — по одному на треугольник
    val mesh = RenderSurface(
      layer = "S",
      positions = floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 1f, 0f, 1f),
      normals = FloatArray(12), uvs = FloatArray(8), indices = intArrayOf(0, 1, 2, 1, 3, 2), edges = intArrayOf(),
      triangleObjects = intArrayOf(0, 1),
    )
    val scene = RenderScene(
      listOf(mesh), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(),
      truncated = false, drawingFlattened = false, originX = 0.0, originY = 0.0, originZ = 0.0, halfX = 1f, halfY = 1f, halfZ = 1f,
      objects = listOf(SceneObject("FACE", "a", "S"), SceneObject("FACE", "b", "S")),
    )
    assertEquals(9, collectObject(scene, 0).triangles.size)
    assertEquals(9, collectObject(scene, 1).triangles.size)
  }
}
