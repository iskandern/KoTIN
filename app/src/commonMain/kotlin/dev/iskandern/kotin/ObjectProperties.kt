package dev.iskandern.kotin

import dev.iskandern.kotin.landxml.ParcelsSection
import dev.iskandern.kotin.landxml.PlanFeaturesSection
import dev.iskandern.kotin.landxml.SurfaceSourceData
import dev.iskandern.kotin.model.SceneAttribute
import dev.iskandern.kotin.model.SceneObject
import dev.iskandern.kotin.model.UiText
import dev.iskandern.kotin.scene.RenderScene
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.math.sqrt

/** Свойства поверхности объекта, посчитанные по её треугольникам в сцене. */
internal class SurfaceProperties(
  val points: Int,
  val faces: Int,
  /** Площадь проекции на план и по склону, м². */
  val area2D: Double,
  val area3D: Double,
  val elevMin: Double,
  val elevMax: Double,
)

/**
 * Свойства поверхности объекта [objectId] — по треугольникам сцены (рельеф, не трубы и колодцы); null — треугольников нет.
 * Считается при выборе, а не при загрузке: у больших поверхностей это проход по всем треугольникам.
 * Точки — разные вершины треугольников объекта (у DXF вершины склеены при сборке сетки).
 */
internal fun surfaceProperties(scene: RenderScene, objectId: Int): SurfaceProperties? {
  var faces = 0
  var points = 0
  var area2D = 0.0
  var area3D = 0.0
  var minY = Double.POSITIVE_INFINITY
  var maxY = Double.NEGATIVE_INFINITY
  for (surface in scene.surfaces) {
    if (surface.structure) continue
    if (surface.triangleObjects == null && surface.objectId != objectId) continue
    val p = surface.positions
    val ix = surface.indices
    val used = BooleanArray(surface.vertexCount)
    fun use(v: Int) {
      if (used[v]) return
      used[v] = true
      points++
      // мир Filament: y — высота
      val y = p[v * 3 + 1].toDouble()
      if (y < minY) minY = y
      if (y > maxY) maxY = y
    }
    for (tri in 0 until surface.triangleCount) {
      if (surface.objectOf(tri) != objectId) continue
      faces++
      val a = ix[tri * 3]
      val b = ix[tri * 3 + 1]
      val c = ix[tri * 3 + 2]
      use(a)
      use(b)
      use(c)
      // мир (x, высота, −север): план — x и z
      val ux = (p[b * 3] - p[a * 3]).toDouble()
      val uy = (p[b * 3 + 1] - p[a * 3 + 1]).toDouble()
      val uz = (p[b * 3 + 2] - p[a * 3 + 2]).toDouble()
      val vx = (p[c * 3] - p[a * 3]).toDouble()
      val vy = (p[c * 3 + 1] - p[a * 3 + 1]).toDouble()
      val vz = (p[c * 3 + 2] - p[a * 3 + 2]).toDouble()
      val nx = uy * vz - uz * vy
      val ny = uz * vx - ux * vz
      val nz = ux * vy - uy * vx
      area2D += abs(ny) / 2
      area3D += sqrt(nx * nx + ny * ny + nz * nz) / 2
    }
  }
  if (faces == 0) return null
  return SurfaceProperties(points, faces, area2D, area3D, minY + scene.originZ, maxY + scene.originZ)
}

/** Длина в плане отрезков объекта [objectId] в сцене, м; 0 — отрезков нет. */
internal fun linePlanLength(scene: RenderScene, objectId: Int): Double {
  var length = 0.0
  for (lines in scene.lines) {
    val ids = lines.objectIds ?: continue
    if (lines.pointThickness) continue
    val p = lines.positions
    val ix = lines.indices
    for (s in 0 until lines.lineCount) {
      if (ids[s] != objectId) continue
      val a = ix[s * 2] * 3
      val b = ix[s * 2 + 1] * 3
      val dx = (p[b] - p[a]).toDouble()
      val dz = (p[b + 2] - p[a + 2]).toDouble()
      length += sqrt(dx * dx + dz * dz)
    }
  }
  return length
}

/**
 * [info] с посчитанными свойствами для карточки выбранного объекта. У поверхности — точки, грани, площади 2D/3D
 * и отметки мин./макс. по сетке сцены; число точек и граней из файла (LandXML) остаётся как есть. Площади и отметки
 * из файла заменяются посчитанными, а если расходятся с ними — показываются ещё и как «… в файле».
 * У линий — длина в плане, если её ещё нет: у DXF накоплена при сборке ([SceneObject.planLength]), у линий LandXML
 * считается по отрезкам сцены.
 */
internal fun withProperties(scene: RenderScene, objectId: Int, info: SceneObject): SceneObject {
  val computed = ArrayList<SceneAttribute>(8)
  val surface = surfaceProperties(scene, objectId)
  val fromFile = info.attributes.associateBy { it.name }
  var replaced = emptySet<String>()
  val inFile = ArrayList<SceneAttribute>(4)
  if (surface != null) {
    computed.add(fromFile[POINTS] ?: SceneAttribute(POINTS, surface.points.toString()))
    computed.add(fromFile[FACES] ?: SceneAttribute(FACES, surface.faces.toString()))
    computed.add(SceneAttribute(AREA_2D, UiText.key("value_m2", formatGrouped(surface.area2D, 2))))
    computed.add(SceneAttribute(AREA_3D, UiText.key("value_m2", formatGrouped(surface.area3D, 2))))
    computed.add(SceneAttribute(ELEV_MIN, formatGrouped(surface.elevMin, 2)))
    computed.add(SceneAttribute(ELEV_MAX, formatGrouped(surface.elevMax, 2)))
    replaced = setOf(POINTS, FACES, AREA_2D, AREA_3D, ELEV_MIN, ELEV_MAX)
    fileValue(fromFile[AREA_2D], surface.area2D, AREA_TOLERANCE * surface.area2D)?.let {
      inFile.add(SceneAttribute("@attr_file_area_2d", UiText.key("value_m2", formatGrouped(it, 2))))
    }
    fileValue(fromFile[AREA_3D], surface.area3D, AREA_TOLERANCE * surface.area3D)?.let {
      inFile.add(SceneAttribute("@attr_file_area_3d", UiText.key("value_m2", formatGrouped(it, 2))))
    }
    fileValue(fromFile[ELEV_MIN], surface.elevMin, ELEV_TOLERANCE)?.let {
      inFile.add(SceneAttribute("@attr_file_elev_min", formatGrouped(it, 2)))
    }
    fileValue(fromFile[ELEV_MAX], surface.elevMax, ELEV_TOLERANCE)?.let {
      inFile.add(SceneAttribute("@attr_file_elev_max", formatGrouped(it, 2)))
    }
  } else if (fromFile.keys.none { it in LENGTHS }) {
    val length = if (!info.planLength.isNaN()) info.planLength
    else if (info.kind in LANDXML_LINES) linePlanLength(scene, objectId) else 0.0
    if (length > 0) computed.add(SceneAttribute(PLAN_LENGTH, formatGrouped(length, 2)))
  }
  if (computed.isEmpty()) return info
  val attributes = computed + info.attributes.filter { it.name !in replaced } + inFile
  return SceneObject(info.kind, info.name, info.layer, info.color, attributes).also { it.planLength = info.planLength }
}

// значение из файла, если оно заметно расходится с посчитанным; иначе null
private fun fileValue(attribute: SceneAttribute?, computed: Double, tolerance: Double): Double? {
  val value = attribute?.value?.toDoubleOrNull() ?: return null
  return value.takeIf { abs(it - computed) > tolerance }
}

/**
 * Число с [decimals] знаками после точки и неразрывными пробелами между тысячами: «12 345.60».
 * Знаки дробной части не обрезаются — в колонке свойств числа одной ширины.
 */
internal fun formatGrouped(value: Double, decimals: Int): String {
  if (!value.isFinite()) return value.toString()
  var scale = 1L
  repeat(decimals) { scale *= 10 }
  val scaled = (abs(value) * scale).roundToLong()
  val whole = (scaled / scale).toString()
  val sb = StringBuilder()
  if (value < 0 && scaled != 0L) sb.append('-')
  for (i in whole.indices) {
    if (i > 0 && (whole.length - i) % 3 == 0) sb.append(THOUSANDS)
    sb.append(whole[i])
  }
  if (decimals > 0) sb.append('.').append((scaled % scale).toString().padStart(decimals, '0'))
  return sb.toString()
}

private const val THOUSANDS = ' '
private const val POINTS = "@attr_points"
private const val FACES = "@attr_faces"
private const val AREA_2D = "@attr_area_2d"
private const val AREA_3D = "@attr_area_3d"
private const val ELEV_MIN = "@attr_elev_min"
private const val ELEV_MAX = "@attr_elev_max"
private const val PLAN_LENGTH = "@attr_plan_length_m"
private val LENGTHS = setOf("@attr_length", "@attr_length_m", PLAN_LENGTH)

// площадь из файла расходится с посчитанной больше чем на 0,1 % — или отметка больше чем на 5 мм
private const val AREA_TOLERANCE = 1e-3
private const val ELEV_TOLERANCE = 0.005

/** Линейные объекты LandXML: у них длина в плане считается по отрезкам сцены. */
private val LANDXML_LINES = setOf(
  SurfaceSourceData.BOUNDARY_KIND, SurfaceSourceData.BREAKLINE_KIND, SurfaceSourceData.CONTOUR_KIND,
  SurfaceSourceData.CHAIN_KIND, PlanFeaturesSection.KIND, ParcelsSection.KIND,
)
