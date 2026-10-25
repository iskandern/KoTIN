package dev.iskandern.kotin

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import io.github.erkko68.filament.compose.scene.CameraState
import io.github.erkko68.filament.compose.scene.Position
import io.github.erkko68.filament.compose.scene.Projection
import io.github.erkko68.filament.compose.scene.rememberCameraState
import dev.iskandern.kotin.scene.RenderScene
import dev.iskandern.kotin.scene.RenderSurface
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Опора жестов карты на рельеф: после [SceneCameraController.anchorAt] сдвиг и зум держат под пальцем ту точку
 * поверхности, за которую взялись, а не точку плоскости через pivot.
 */
@OptIn(ExperimentalComposeUiApi::class)
class TerrainAnchorTest {

  private val w = 800f
  private val h = 600f
  private val fov = 45.0

  /** TIN n × n квадратов на [-half, half] в плане, высота — [height](x, z). */
  private fun tin(n: Int, half: Float, structure: Boolean = false, layer: String = "TIN", height: (Float, Float) -> Float): RenderSurface {
    val positions = FloatArray((n + 1) * (n + 1) * 3)
    for (j in 0..n) for (i in 0..n) {
      val x = -half + 2 * half * i / n
      val z = -half + 2 * half * j / n
      val v = (j * (n + 1) + i) * 3
      positions[v] = x; positions[v + 1] = height(x, z); positions[v + 2] = z
    }
    val indices = IntArray(n * n * 6)
    var k = 0
    for (j in 0 until n) for (i in 0 until n) {
      val a = j * (n + 1) + i
      val b = a + 1
      val c = a + n + 1
      val d = c + 1
      indices[k++] = a; indices[k++] = c; indices[k++] = b
      indices[k++] = b; indices[k++] = c; indices[k++] = d
    }
    return RenderSurface(layer, positions, FloatArray(positions.size), FloatArray(positions.size / 3 * 2), indices, IntArray(0), structure = structure)
  }

  private fun scene(vararg surfaces: RenderSurface) = RenderScene(
    surfaces.toList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(),
    truncated = false, drawingFlattened = false, originX = 0.0, originY = 0.0, originZ = 0.0,
    halfX = 500f, halfY = 50f, halfZ = 500f,
  )

  private fun cameraState(): CameraState {
    lateinit var state: CameraState
    val compose = ImageComposeScene(8, 8, Density(1f)) {
      state = rememberCameraState(
        initialEye = Position(0f, 100f, 100f),
        initialTarget = Position(0f, 0f, 0f),
        initialProjection = Projection.Perspective(fovDegrees = fov, near = 1.0, far = 10000.0),
      )
    }
    compose.render()
    compose.close()
    return state
  }

  /** Приближенная камера под углом 50° над pivot в нуле (середина габаритов по высоте), азимут 30°. */
  private fun controller(
    terrain: TerrainIndex?, settings: ViewSettings = ViewSettings(), pitchDegrees: Double = 50.0,
  ): SceneCameraController {
    val pose = CameraPose(0f, 0f, 0f, distance = 120f, yaw = (30 * PI / 180).toFloat(), pitch = (pitchDegrees * PI / 180).toFloat())
    return SceneCameraController(cameraState(), sceneRadius = 700f, fovDegrees = fov, initialPose = pose).also { c ->
      if (terrain != null) c.terrainDistance = { ray -> terrain.distance(ray, settings) }
    }
  }

  /** Точка рельефа (Y — с масштабом) под экранной точкой. */
  private fun surfacePoint(terrain: TerrainIndex, c: SceneCameraController, px: Float, py: Float, k: Float = 1f): FloatArray {
    val ray = c.pickRay(px, py, w, h)
    val t = terrain.hitAll(ray, k.toDouble(), booleanArrayOf(true, true, true))
    assertTrue(t > 0.0, "луч мимо рельефа")
    return floatArrayOf((ray.ox + ray.dx * t).toFloat(), (ray.oy + ray.dy * t).toFloat(), (ray.oz + ray.dz * t).toFloat())
  }

  private fun screenOf(c: SceneCameraController, p: FloatArray): Pair<Float, Float> =
    assertNotNull(c.pose.project(fov, p[0], p[1], p[2], w, h))

  /** Палец из ([x0], [y0]) в ([x1], [y1]) за [steps] событий. */
  private fun drag(c: SceneCameraController, x0: Float, y0: Float, x1: Float, y1: Float, steps: Int = 20) {
    var x = x0
    var y = y0
    for (i in 1..steps) {
      val nx = x0 + (x1 - x0) * i / steps
      val ny = y0 + (y1 - y0) * i / steps
      c.pan(nx, ny, nx - x, ny - y, w, h)
      x = nx; y = ny
    }
  }

  private fun offBy(c: SceneCameraController, p: FloatArray, x: Float, y: Float): Float {
    val (sx, sy) = screenOf(c, p)
    return hypot(sx - x, sy - y)
  }

  private fun checkPanKeepsSurface(base: Float, k: Float) {
    val surface = tin(40, 500f) { x, z -> base + 0.05f * x - 0.03f * z }
    val terrain = TerrainIndex(scene(surface)).also { it.build() }
    val settings = ViewSettings(verticalScale = k)

    val anchored = controller(terrain, settings)
    val p = surfacePoint(terrain, anchored, 500f, 380f, k)
    val eyeBefore = anchored.eye()
    anchored.anchorAt(500f, 380f, w, h)
    // перенос pivot картинку не двигает: глаз и точка под пальцем на месте
    val eyeAfter = anchored.eye()
    for (i in 0..2) assertEquals(eyeBefore[i], eyeAfter[i], 1e-3f)
    assertTrue(offBy(anchored, p, 500f, 380f) < 0.05f)
    assertEquals(p[1], anchored.pose.pivotY, 1e-3f)

    drag(anchored, 500f, 380f, 260f, 200f)
    assertTrue(offBy(anchored, p, 260f, 200f) < 0.1f, "с опорой точка ушла на ${offBy(anchored, p, 260f, 200f)} px")
    anchored.zoom(0.5f, 260f, 200f, w, h)
    assertTrue(offBy(anchored, p, 260f, 200f) < 0.1f, "зум сдвинул точку на ${offBy(anchored, p, 260f, 200f)} px")

    // без опоры — прежнее поведение: плоскость y = 0, поверхность отстаёт от пальца
    val plain = controller(null)
    drag(plain, 500f, 380f, 260f, 200f)
    assertTrue(offBy(plain, p, 260f, 200f) > 5f, "без опоры ожидался сдвиг, ${offBy(plain, p, 260f, 200f)} px")
  }

  private fun SceneCameraController.eye(): FloatArray {
    val e = pose
    return floatArrayOf(
      e.pivotX + cos(e.pitch) * sin(e.yaw) * e.distance,
      e.pivotY + sin(e.pitch) * e.distance,
      e.pivotZ + cos(e.pitch) * cos(e.yaw) * e.distance,
    )
  }

  @Test
  fun `поверхность ниже pivot остаётся под пальцем`() = checkPanKeepsSurface(base = -40f, k = 1f)

  @Test
  fun `поверхность выше pivot остаётся под пальцем`() = checkPanKeepsSurface(base = 35f, k = 1f)

  @Test
  fun `с вертикальным масштабом поверхность остаётся под пальцем`() = checkPanKeepsSurface(base = -8f, k = 5f)

  @Test
  fun `мимо рельефа — pivot прежний`() {
    // маленький TIN в стороне: под пальцем пусто
    val surface = tin(4, 20f) { _, _ -> -30f }
    val terrain = TerrainIndex(scene(surface)).also { it.build() }
    val c = controller(terrain)
    val before = c.pose
    c.anchorAt(50f, 50f, w, h)
    assertEquals(before, c.pose)
  }

  @Test
  fun `скрытая поверхность опорой не служит`() {
    val surface = tin(10, 500f) { _, _ -> -30f }
    val terrain = TerrainIndex(scene(surface)).also { it.build() }
    for (settings in listOf(ViewSettings(showSurface = false), ViewSettings(hiddenLayers = setOf("TIN")))) {
      val c = controller(terrain, settings)
      val before = c.pose
      c.anchorAt(400f, 300f, w, h)
      assertEquals(before, c.pose)
    }
  }

  @Test
  fun `трубы опорой не служат`() {
    val pipes = tin(10, 500f, structure = true) { _, _ -> -30f }
    val terrain = TerrainIndex(scene(pipes)).also { it.build() }
    val c = controller(terrain)
    val before = c.pose
    c.anchorAt(400f, 300f, w, h)
    assertEquals(before, c.pose)
    assertNull(terrain.height(c.pickRay(400f, 300f, w, h), ViewSettings()))
  }

  /**
   * Орбита: два пальца ставятся вокруг ([cx0], [cy0]) (опора — под серединой), затем середина уходит в ([cx1], [cy1]),
   * пальцы разводятся в [spread] раз — как в жесте: сдвиг середины и зум к ней на каждом событии.
   */
  private fun orbitPinch(c: SceneCameraController, cx0: Float, cy0: Float, cx1: Float, cy1: Float, spread: Float, steps: Int = 20) {
    c.anchorAt(cx0, cy0, w, h)
    var x = cx0
    var y = cy0
    var span = 100f
    for (i in 1..steps) {
      val nx = cx0 + (cx1 - cx0) * i / steps
      val ny = cy0 + (cy1 - cy0) * i / steps
      val nspan = 100f * Math.pow(spread.toDouble(), i.toDouble() / steps).toFloat()
      c.pan(nx, ny, nx - x, ny - y, w, h)
      c.zoom(span / nspan, nx, ny, w, h)
      x = nx; y = ny; span = nspan
    }
  }

  private fun checkOrbitKeepsSurface(pitchDegrees: Double, base: Float) {
    val surface = tin(40, 500f) { x, z -> base + 0.05f * x - 0.03f * z }
    val terrain = TerrainIndex(scene(surface)).also { it.build() }

    val c = controller(terrain, pitchDegrees = pitchDegrees).also { it.mode = CameraMode.ORBIT }
    val p = surfacePoint(terrain, c, 460f, 360f)
    val eyeBefore = c.eye()
    c.anchorAt(460f, 360f, w, h)
    val eyeAfter = c.eye()
    for (i in 0..2) assertEquals(eyeBefore[i], eyeAfter[i], 1e-3f)
    assertTrue(offBy(c, p, 460f, 360f) < 0.05f)

    orbitPinch(c, 460f, 360f, 300f, 250f, spread = 2f)
    assertTrue(offBy(c, p, 300f, 250f) < 0.1f, "pitch $pitchDegrees: точка ушла на ${offBy(c, p, 300f, 250f)} px")
    // колесо под курсором — к той же точке
    c.anchorAt(300f, 250f, w, h)
    c.zoom(0.6f, 300f, 250f, w, h)
    assertTrue(offBy(c, p, 300f, 250f) < 0.1f, "pitch $pitchDegrees: колесо сдвинуло точку на ${offBy(c, p, 300f, 250f)} px")
    // мышь (вторичная схема в орбите — сдвиг): опора под курсором, сдвиг
    val q = surfacePoint(terrain, c, 420f, 330f)
    c.anchorAt(420f, 330f, w, h)
    drag(c, 420f, 330f, 380f, 280f)
    assertTrue(offBy(c, q, 380f, 280f) < 0.1f, "pitch $pitchDegrees: сдвиг мышью — ${offBy(c, q, 380f, 280f)} px")

    // без опоры — прежнее поведение, поверхность уходит из-под пальцев
    val plain = controller(null, pitchDegrees = pitchDegrees).also { it.mode = CameraMode.ORBIT }
    val p0 = surfacePoint(terrain, plain, 460f, 360f)
    orbitPinch(plain, 460f, 360f, 300f, 250f, spread = 2f)
    assertTrue(offBy(plain, p0, 300f, 250f) > 3f, "pitch $pitchDegrees: без опоры ожидался сдвиг, ${offBy(plain, p0, 300f, 250f)} px")
  }

  @Test
  fun `орбита под 30° — рельеф остаётся под пальцами`() = checkOrbitKeepsSurface(30.0, base = -40f)

  @Test
  fun `орбита под 80° — рельеф остаётся под пальцами`() = checkOrbitKeepsSurface(80.0, base = -40f)

  @Test
  fun `орбита снизу — рельеф остаётся под пальцами`() = checkOrbitKeepsSurface(-25.0, base = 40f)

  @Test
  fun `орбита мимо рельефа — прежнее поведение`() {
    val terrain = TerrainIndex(scene(tin(4, 20f) { _, _ -> -30f })).also { it.build() }
    val anchored = controller(terrain, pitchDegrees = 30.0).also { it.mode = CameraMode.ORBIT }
    val plain = controller(null, pitchDegrees = 30.0).also { it.mode = CameraMode.ORBIT }
    val before = anchored.pose
    anchored.anchorAt(50f, 50f, w, h)
    assertEquals(before, anchored.pose)
    orbitPinch(anchored, 50f, 50f, 200f, 150f, spread = 1.5f)
    orbitPinch(plain, 50f, 50f, 200f, 150f, spread = 1.5f)
    assertEquals(plain.pose, anchored.pose)
  }

  @Test
  fun `сетка находит то же, что перебор`() {
    val rough = tin(60, 500f) { x, z -> 20f * sin(x / 37f) * cos(z / 53f) + 0.02f * x }
    val second = tin(8, 120f, layer = "B") { _, z -> 40f + 0.1f * z }
    val terrain = TerrainIndex(scene(rough, second))
    terrain.build()
    val random = Random(7)
    val visible = booleanArrayOf(true, true)
    var hits = 0
    repeat(3000) {
      // глаз над сценой и сбоку от неё, лучи во все стороны вниз, иногда отвесно
      val ox = random.nextDouble(-900.0, 900.0)
      val oz = random.nextDouble(-900.0, 900.0)
      val oy = random.nextDouble(60.0, 600.0)
      var dx = random.nextDouble(-1.0, 1.0)
      var dz = random.nextDouble(-1.0, 1.0)
      if (it % 10 == 0) { dx = 0.0; dz = 0.0 }
      val dy = -random.nextDouble(0.05, 1.0)
      val len = kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
      val ray = PickRay(ox, oy, oz, dx / len, dy / len, dz / len, 1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0, 0.001)
      for (k in listOf(1.0, 5.0)) {
        val brute = terrain.hitAll(ray, k, visible)
        val grid = assertNotNull(terrain.hitGrid(ray, k, visible))
        if (brute > 0.0) {
          hits++
          assertEquals(brute, grid, 1e-6 * brute, "луч $it, масштаб $k")
        } else {
          assertTrue(grid <= 0.0, "луч $it, масштаб $k: сетка нашла $grid, перебор — нет")
        }
      }
    }
    assertTrue(hits > 1000, "мало попаданий: $hits")
  }

  @Test
  fun `до сетки большой рельеф опорой не служит, маленький перебирается`() {
    val small = TerrainIndex(scene(tin(10, 500f) { _, _ -> -30f }))
    val c = controller(null)
    val ray = c.pickRay(400f, 300f, w, h)
    assertEquals(-30f, assertNotNull(small.height(ray, ViewSettings())), 1e-3f)
    val big = TerrainIndex(scene(tin(200, 500f) { _, _ -> -30f }))
    assertNull(big.height(ray, ViewSettings()))
    big.build()
    assertEquals(-30f, assertNotNull(big.height(ray, ViewSettings())), 1e-3f)
  }

  @Test
  fun `расстояние у рельефа не прыгает назад к пределу`() {
    // рельеф почти у глаза: после переноса pivot ближе MIN_DISTANCE, зум не должен отодвинуть камеру
    val terrain = TerrainIndex(scene(tin(10, 500f) { _, _ -> 91.5f })).also { it.build() }
    val c = controller(terrain)
    c.anchorAt(400f, 300f, w, h)
    val d = c.pose.distance
    assertTrue(d < 700f * 0.002f, "$d")
    c.zoom(0.9f, 400f, 300f, w, h)
    assertTrue(c.pose.distance <= d + 1e-4f, "${c.pose.distance} > $d")
    c.zoom(2f, 400f, 300f, w, h)
    assertTrue(c.pose.distance > d)
  }

  @Test
  fun `почти горизонтальный взгляд — pivot не переносится`() {
    val terrain = TerrainIndex(scene(tin(10, 500f) { _, _ -> -30f })).also { it.build() }
    val pose = CameraPose(0f, 0f, 0f, 120f, 0f, 0.005f)
    val c = SceneCameraController(cameraState(), 700f, fov, initialPose = pose)
    c.terrainDistance = { ray -> terrain.distance(ray, ViewSettings()) }
    c.anchorAt(400f, 590f, w, h)
    assertEquals(pose, c.pose)
    assertTrue(abs(c.pose.pivotY) < 1e-6f)
  }
}
