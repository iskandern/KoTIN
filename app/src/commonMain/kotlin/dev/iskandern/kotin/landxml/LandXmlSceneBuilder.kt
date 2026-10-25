package dev.iskandern.kotin.landxml

import dev.iskandern.kotin.dxf.DxfFont
import dev.iskandern.kotin.dxf.FACE_DEFAULT
import dev.iskandern.kotin.dxf.FACE_MONO
import dev.iskandern.kotin.dxf.FACE_MONO_BOLD
import dev.iskandern.kotin.model.CoordinateSystemInfo
import dev.iskandern.kotin.model.GrowingDoubleArray
import dev.iskandern.kotin.model.GrowingIntArray
import dev.iskandern.kotin.model.LayerInfo
import dev.iskandern.kotin.model.LineBatch
import dev.iskandern.kotin.model.MAX_SCENE_SEGMENTS
import dev.iskandern.kotin.model.NO_OBJECT
import dev.iskandern.kotin.model.NO_TICK
import dev.iskandern.kotin.model.ObjectRuns
import dev.iskandern.kotin.model.PointMark
import dev.iskandern.kotin.model.SceneGeometry
import dev.iskandern.kotin.model.SceneLabel
import dev.iskandern.kotin.model.SceneMesh
import dev.iskandern.kotin.model.SceneObject
import dev.iskandern.kotin.model.UiText
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToLong

/**
 * Накопитель сцены LandXML: разделы складывают сюда сетки, отрезки, подписи, точки, слои и объекты.
 *
 * Раскладка подписей одного места. Подписи просмотрщика — постоянного размера на экране, поэтому две подписи
 * в одной точке налезают друг на друга при любом масштабе, и разводятся они тоже экранными сдвигами: столбиком
 * вверх от точки (строки ниже точки проваливаются под рельеф при наклонной камере). Подписи точек ([addPointLabel])
 * у совпадающих точек — блоками друг над другом; заголовки ([addTitle]) с центрами ближе своего радиуса — столбиком
 * в общей точке. Подписи разных, но близких точек разводит только масштаб: «Прореживать подписи по масштабу»
 * прячет менее важные, пока они не поместятся (placeLabels).
 * Всё, что зависит от всей сцены (ссылки на точки и колодцы, разбиение кривых по допуску от размера сцены,
 * посадка на поверхность), разделы откладывают в [defer]; [finish] выполняет отложенное по стадиям и отдаёт
 * [SceneGeometry]. Отрезки — в примитивных массивах, объектов на отрезок нет; лимит — [maxSegments], как у DXF.
 */
internal class LandXmlSceneBuilder(
  private val maxSegments: Int = MAX_SCENE_SEGMENTS,
  /** Вызывается из [tick] раз в [TICK_EVERY] элементов — для прогресса и отмены. */
  private val onTick: () -> Unit = {},
  private val log: (String) -> Unit = {},
  /** Размер файла, если известен: разделы оценивают по нему ёмкость массивов. */
  val sizeBytes: Long? = null,
  /** Запись пикетов в подписях — по языку интерфейса ([StationLabels]). */
  val stationNotation: StationNotation = StationNotation(),
) {
  private val meshes = ArrayList<SceneMesh>()
  private val lines = LinkedHashMap<LineKey, Lines>()
  private val labels = ArrayList<PendingLabel>()
  private val titles = ArrayList<PendingTitle>()
  // строки подписей точек, уже занятые над точкой (ключ — точка с точностью до см): следующая точка там же — выше
  private val pointLines = HashMap<Long, Int>()
  private val points = ArrayList<PointMark>()
  private val layers = LinkedHashMap<String, LayerInfo>()
  private val objects = ArrayList<SceneObject>()
  private val deferred = ArrayList<Deferred>()
  private val surfaces = ArrayList<DrapeSurface>()
  private val flatPoints = GrowingIntArray()
  private var ticks = 0

  /** Посадка на поверхность в [finish]: отрезков и точек, севших на поверхность. */
  var drapedSegments = 0
    private set
  var drapedPoints = 0
    private set

  /** Построенные трассы по порядку файла — заполняет раздел `Alignments` в [STAGE_CURVES]; читают `Roadways` и др. в [STAGE_DRAPE]. */
  val alignments = ArrayList<BuiltAlignment>()

  /**
   * Размер сечения (диаметры, размеры форм колодцев и труб) в линейных единицах файла: `diameterUnit` / `linearUnit`
   * из `Units`. NaN — `Units` нет или единица неизвестна: разделы применяют запасную эвристику.
   */
  var sectionScale = Double.NaN

  /** Атрибуты элементов `CoordinateSystem` по порядку файла — из корня и из `SurveyHeader` (ePlan). */
  val coordinateSystems = ArrayList<List<Pair<String, String>>>()

  /** `linearUnit` из `Units`, как записан, — для показа рядом с системой координат. */
  var fileUnits: String? = null

  /** Точки по имени — для ссылок `pntRef`, `Chain`; искать в [finish] (стадия [STAGE_REFERENCES]). */
  val pointNames = LandXmlPointNames()

  /** Отрезков всего; на лимите новые отбрасываются и сцена помечается [truncated]. */
  var segmentCount = 0
    private set
  var truncated = false
    private set

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

  /** Сцена пока пуста: габаритов нет. */
  val isEmpty: Boolean get() = minX > maxX

  /** Размер сцены в плане (большая сторона габаритов); 0 — пусто. Для допусков в отложенных шагах. */
  val size: Double get() = if (isEmpty) 0.0 else max(maxX - minX, maxY - minY)

  /** Раздел прочитал очередной элемент: раз в [TICK_EVERY] — прогресс. */
  fun tick() {
    if (++ticks >= TICK_EVERY) {
      ticks = 0
      onTick()
    }
  }

  /** Слой [name] вида [color] (из [LandXmlPalette]) в группе [group] ([LandXmlGroup]); повторный вызов возвращает тот же слой. */
  fun layer(name: String, color: Int, group: String): String {
    if (name !in layers) layers[name] = LayerInfo(name, color = 7, flags = 0, trueColor = color, group = group)
    return name
  }

  /** Переставляет слой [name] сразу за слой [after] (порядок слоёв в меню); нет одного из них — ничего не делает. */
  fun moveLayerAfter(name: String, after: String) {
    if (name !in layers || after !in layers) return
    val moving = layers.remove(name) ?: return
    val rest = layers.entries.map { it.key to it.value }
    layers.clear()
    for ((key, info) in rest) {
      layers[key] = info
      if (key == after) layers[name] = moving
    }
  }

  /** Объект для выбора; возвращает его номер. */
  fun addObject(obj: SceneObject): Int {
    objects.add(obj)
    return objects.size - 1
  }

  /** Сетка; [surface] — имя поверхности, на которую сажается 2D-геометрия ([finish]), null — не сажать. */
  fun addMesh(mesh: SceneMesh, surface: String? = null) {
    meshes.add(mesh)
    if (mesh.vertexCount == 0) return
    if (surface != null && mesh.triangleCount > 0) surfaces.add(DrapeSurface(surface, mesh))
    include(mesh.minX, mesh.minY, mesh.minZ)
    include(mesh.maxX, mesh.maxY, mesh.maxZ)
  }

  /**
   * Пакет отрезков слоя и цвета: раздел берёт его один раз на объект, а не на отрезок. Плоские ([flat]) в [finish]
   * сажаются на поверхность [surfaceRef] (null — на любую из файла). [lineWeight] — толщина, как у DXF (1/100 мм);
   * [dashed] — пунктир ([LineBatch.dashed]).
   */
  fun lines(
    layer: String, color: Int, flat: Boolean, surfaceRef: String? = null, lineWeight: Int = 0, dashed: Boolean = false,
  ): Lines = lines.getOrPut(LineKey(layer, color, flat, if (flat) surfaceRef else null, lineWeight, dashed)) { Lines() }

  /** Точка; [flat] — без отметки: в [finish] сажается на поверхность. */
  fun addPoint(point: PointMark, flat: Boolean = false) {
    if (flat) flatPoints.add(points.size)
    points.add(point)
    include(point.x, point.y, point.z)
  }

  /**
   * Подпись; [height] < 0 — в dp на экране (по умолчанию — обычный текст [LabelScale.TEXT_DP]), > 0 — в метрах;
   * [flat] — без отметки, сажается на поверхность. [face], выравнивание, [screenHorizontal], [liftDp] и [tickRgb] — как
   * у [SceneLabel].
   */
  fun addLabel(
    layer: String, color: Int, x: Double, y: Double, z: Double, text: String,
    rotationDeg: Double = 0.0, height: Double = LabelScale.height(LabelScale.TEXT_DP), objectId: Int = NO_OBJECT, flat: Boolean = false,
    face: Int = FACE_MONO, alignH: Int = 0, alignV: Int = 0, screenHorizontal: Boolean = true,
    liftDp: Double = 0.0, shiftDp: Double = 0.0, tickRgb: Int = NO_TICK,
  ) {
    labels.add(
      PendingLabel(
        layer, color, x, y, z, text, rotationDeg, height, objectId, flat, face, alignH, alignV, screenHorizontal, liftDp, shiftDp, tickRgb,
      ),
    )
  }

  /**
   * Подпись точки по эталону: номер [name] над отметкой [z], моноширинным [LabelScale.POINT_DP] правее значка; [hasElevation] false
   * (отметки нет) — только номер. [flat] — как у [addLabel].
   */
  fun addPointLabel(layer: String, x: Double, y: Double, z: Double, name: String, hasElevation: Boolean, objectId: Int, flat: Boolean) {
    val height = LabelScale.height(LabelScale.POINT_DP)
    val lines = (if (hasElevation) 1 else 0) + (if (name.isNotEmpty()) 1 else 0)
    if (lines == 0) return
    // совпадающие точки (дубли в файле): блок следующей — над блоком предыдущей, с пропуском полстроки
    val key = pointKey(x, y)
    val used = pointLines[key] ?: 0
    pointLines[key] = used + lines + 1
    val lift = POINT_LIFT_DP + used * POINT_LINE_DP
    if (hasElevation) {
      addLabel(layer, LandXmlPalette.LABEL, x, y, z, elevationText(z), height = height, objectId = objectId, flat = flat,
        face = FACE_MONO, alignV = 1, liftDp = lift, shiftDp = POINT_SHIFT_DP)
    }
    if (name.isNotEmpty()) {
      addLabel(layer, LandXmlPalette.LABEL, x, y, z, name, height = height, objectId = objectId, flat = flat,
        face = FACE_MONO, alignV = 1, liftDp = lift + if (hasElevation) POINT_LINE_DP else 0.0, shiftDp = POINT_SHIFT_DP)
    }
  }

  private fun pointKey(x: Double, y: Double): Long = (floor(x * 100).toLong() shl 32) xor (floor(y * 100).toLong() and 0xFFFFFFFFL)

  /**
   * Подпись знака на точке (`Monument` на `CgPoint`): имя [name] слева от значка, по его середине — справа уже стоят
   * номер и отметка самой точки, и две подписи в одном месте сливались в кашу.
   */
  fun addMarkLabel(layer: String, x: Double, y: Double, z: Double, name: String, objectId: Int, flat: Boolean) {
    if (name.isEmpty()) return
    addLabel(layer, LandXmlPalette.LABEL, x, y, z, name, height = LabelScale.height(LabelScale.POINT_DP), objectId = objectId, flat = flat,
      face = FACE_MONO, alignH = 2, alignV = 2, shiftDp = -POINT_SHIFT_DP)
  }

  /**
   * Заголовок места: название объекта с условным центром (участок и т. п.), плоский (сажается на поверхность),
   * полужирным [LabelScale.TITLE_DP] по центру. [reach] — радиус в метрах, в котором другие заголовки считаются
   * подписями того же места: заголовки ближе меньшего из двух радиусов при сборке ([stackTitles]) укладываются
   * столбиком вверх от общей точки, строки по порядку названий с экранным шагом (нижняя — на точке: строка ниже точки
   * проваливалась бы под рельеф). Иначе подписи соседних центров (участок «13» и его часть «13_BA» в 5 м друг от
   * друга) налезают друг на друга при любом масштабе: кегль постоянный на экране.
   */
  fun addTitle(layer: String, color: Int, x: Double, y: Double, text: String, objectId: Int, reach: Double) {
    titles.add(PendingTitle(layer, color, x, y, text, objectId, reach))
  }

  // заголовки — столбиками по близости центров; порядок добавления сохраняется (столбик — на месте первого)
  private fun stackTitles() {
    if (titles.isEmpty()) return
    var cell = 0.0
    for (t in titles) if (t.reach > cell) cell = t.reach
    if (cell <= 0.0) cell = 1.0
    fun key(cx: Long, cy: Long) = (cx shl 32) xor (cy and 0xFFFFFFFFL)
    val grid = HashMap<Long, ArrayList<PendingTitle>>()
    val columns = ArrayList<ArrayList<PendingTitle>>()
    for (t in titles) {
      var joined: ArrayList<PendingTitle>? = null
      val cx = floor(t.x / cell).toLong()
      val cy = floor(t.y / cell).toLong()
      search@ for (dx in -1L..1L) for (dy in -1L..1L) {
        val leads = grid[key(cx + dx, cy + dy)] ?: continue
        for (lead in leads) {
          val reach = minOf(t.reach, lead.reach)
          val ddx = t.x - lead.x
          val ddy = t.y - lead.y
          if (ddx * ddx + ddy * ddy <= reach * reach) {
            joined = lead.column
            break@search
          }
        }
      }
      if (joined != null) {
        joined.add(t)
        t.column = joined
      } else {
        val column = arrayListOf(t)
        t.column = column
        columns.add(column)
        grid.getOrPut(key(cx, cy)) { ArrayList(2) }.add(t)
      }
    }
    val height = LabelScale.height(LabelScale.TITLE_DP)
    for (column in columns) {
      column.sortBy { it.text }
      var x = 0.0
      var y = 0.0
      for (t in column) {
        x += t.x
        y += t.y
      }
      x /= column.size
      y /= column.size
      for ((i, t) in column.withIndex()) {
        addLabel(
          t.layer, t.color, x, y, 0.0, t.text, height = height, objectId = t.objectId, flat = true,
          face = FACE_MONO_BOLD, alignH = 1, alignV = 2, liftDp = (column.size - 1 - i) * TITLE_LINE_DP,
        )
      }
    }
    titles.clear()
  }

  fun include(x: Double, y: Double, z: Double) {
    if (x < minX) minX = x
    if (x > maxX) maxX = x
    if (y < minY) minY = y
    if (y > maxY) maxY = y
    if (z < minZ) minZ = z
    if (z > maxZ) maxZ = z
  }

  /** Шаг на конец чтения: стадии — [STAGE_REFERENCES], [STAGE_CURVES], [STAGE_DRAPE]; в стадии — по порядку добавления. */
  fun defer(stage: Int, step: () -> Unit) {
    deferred.add(Deferred(stage, step))
  }

  /** Отложенные шаги, высота подписей — и готовая сцена. */
  fun finish(): SceneGeometry {
    // шаг может отложить ещё один — на свою или более позднюю стадию
    for (stage in STAGE_REFERENCES..STAGE_DRAPE) {
      var i = 0
      while (i < deferred.size) {
        val next = deferred[i++]
        if (next.stage == stage) next.step()
      }
    }
    stackTitles()
    drape()
    return SceneGeometry(
      meshes = meshes,
      lines = lines.mapNotNull { (key, batch) -> batch.toBatch(key) },
      fills = emptyList(),
      labels = labels.map {
        SceneLabel(it.layer, it.color, it.x, it.y, it.z, it.height,
          it.rotationDeg, it.text, it.alignH, it.alignV, font = if (it.face == FACE_DEFAULT) DxfFont.DEFAULT else DxfFont(it.face, 1.0),
          objectId = it.objectId, screenHorizontal = it.screenHorizontal, liftDp = it.liftDp, shiftDp = it.shiftDp,
          tickRgb = it.tickRgb)
      },
      points = points,
      // по группам в порядке списка, внутри группы — по созданию (сортировка устойчивая)
      layers = layers.values.sortedBy { LandXmlGroup.ORDER.indexOf(it.group) },
      truncated = truncated,
      objects = objects,
      // толщина осей трасс и границ поверхностей — наша (ALIGNMENT_WEIGHT, BOUNDARY_WEIGHT), не из файла
      lineWeightsOffered = false,
      flattenAuto = false,
      coordinateSystems = coordinateSystems.map { CoordinateSystemInfo(it, fileUnits) },
    )
  }

  /**
   * Посадка 2D-геометрии (плоские отрезки, точки и подписи без отметок) на поверхности файла — после всех разделов.
   * Поверхность — из `surfaceRef` пакета, иначе любая: из нескольких — наибольшая по площади, где лежит точка.
   * Отрезки дробятся по рёбрам треугольников и уходят в неплоский пакет того же слоя и цвета; что вне поверхностей —
   * остаётся плоским (сцена положит на уровень). Подъёма над рельефом нет: линии и так рисуются ближе поверхности
   * сдвигом глубины (SceneView), как у DXF. Без поверхностей или без 2D-геометрии ничего не делается, индекс не строится.
   */
  private fun drape() {
    if (surfaces.isEmpty()) return
    val flatKeys = lines.entries.filter { it.key.flat && it.value.count > 0 }.map { it.key }
    if (flatKeys.isEmpty() && flatPoints.size == 0 && labels.none { it.flat }) return
    val all = surfaces.sortedByDescending { it.area }
    val drapeAll = LandXmlDrape(all)
    // что осталось вне поверхностей, сцена кладёт на нижний уровень сеток (flatLevel в buildRenderScene); ставим ту же
    // отметку сами: иначе z = 0 рядом с отметками рельефа выглядит для сцены как «чертёж с бессмысленными отметками»
    // (drawingLooksPlanar), и она кладёт в плоскость всё, вместе с севшими линиями
    val level = meshes.filter { it.triangleCount > 0 }.minOf { it.minZ }
    val refDrapes = ArrayList<LandXmlDrape>()
    for (key in flatKeys) {
      val ref = key.surfaceRef?.let { name -> all.firstOrNull { it.name == name } }
      val drape = if (ref != null) LandXmlDrape(listOf(ref)).also { refDrapes.add(it) } else drapeAll
      val batch = lines.getValue(key)
      val coords = batch.coordsArray()
      val ids = batch.objectIds()
      batch.clear()
      val onSurface = lines(key.layer, key.color, flat = false, lineWeight = key.lineWeight, dashed = key.dashed)
      val out = drape.out
      for (k in 0 until coords.size / 6) {
        val at = k * 6
        out.clear()
        drape.segment(coords[at], coords[at + 1], coords[at + 2], coords[at + 3], coords[at + 4], coords[at + 5])
        drapedSegments++
        val id = if (ids != null) ids[k] else NO_OBJECT
        for (p in 0 until out.size / 7) {
          val o = p * 7
          if (out[o + 6] != 0.0) {
            onSurface.add(out[o], out[o + 1], out[o + 2], out[o + 3], out[o + 4], out[o + 5], id)
          } else {
            batch.add(out[o], out[o + 1], level, out[o + 3], out[o + 4], level, id)
          }
        }
      }
    }
    val cut = drapeAll.cut + refDrapes.sumOf { it.cut }
    if (cut > 0) log(UiText.key("log_drape_cut", cut))
    for (k in 0 until flatPoints.size) {
      val i = flatPoints[k]
      val p = points[i]
      val z = drapeAll.height(p.x, p.y)
      if (!z.isNaN()) drapedPoints++
      points[i] = PointMark(p.layer, p.color, p.x, p.y, if (z.isNaN()) level else z, p.rotationDeg, p.objectId)
    }
    for (label in labels) {
      if (!label.flat) continue
      val z = drapeAll.height(label.x, label.y)
      label.z = if (z.isNaN()) level else z
    }
  }

  /** Отрезки одного слоя и цвета: координаты подряд (x1 y1 z1 x2 y2 z2), номера объектов — сериями. */
  inner class Lines {
    private var coords = GrowingDoubleArray()
    private var runs = ObjectRuns()
    internal var count = 0
      private set

    internal fun coordsArray(): DoubleArray = coords.toArray()

    internal fun objectIds(): IntArray? = runs.expand(count)

    // пакет пуст: отрезки уходят из счёта лимита — посадка добавит их заново
    internal fun clear() {
      segmentCount -= count
      count = 0
      coords = GrowingDoubleArray()
      runs = ObjectRuns()
    }

    fun add(x1: Double, y1: Double, z1: Double, x2: Double, y2: Double, z2: Double, objectId: Int = NO_OBJECT) {
      if (segmentCount >= maxSegments) {
        truncated = true
        return
      }
      segmentCount++
      if (objectId != NO_OBJECT) runs.mark(count, objectId)
      count++
      coords.add(x1)
      coords.add(y1)
      coords.add(z1)
      coords.add(x2)
      coords.add(y2)
      coords.add(z2)
      include(x1, y1, z1)
      include(x2, y2, z2)
    }

    internal fun toBatch(key: LineKey): LineBatch? {
      if (count == 0) return null
      return LineBatch(key.layer, key.color, key.flat, coords.toArray(), key.lineWeight, objectIds = runs.expand(count), dashed = key.dashed)
    }
  }

  /** Трасса с готовой геометрией и профилем (null — без профиля) — для разделов, привязанных к пикетам. */
  internal class BuiltAlignment(val name: String, val geometry: AlignmentGeometry, val profile: AlignmentProfile?)

  internal data class LineKey(
    val layer: String, val color: Int, val flat: Boolean, val surfaceRef: String? = null,
    val lineWeight: Int = 0, val dashed: Boolean = false,
  )

  private class PendingLabel(
    val layer: String, val color: Int, val x: Double, val y: Double, var z: Double, val text: String,
    val rotationDeg: Double, val height: Double, val objectId: Int, val flat: Boolean,
    val face: Int, val alignH: Int, val alignV: Int, val screenHorizontal: Boolean, val liftDp: Double, val shiftDp: Double,
    val tickRgb: Int,
  )

  private class PendingTitle(
    val layer: String, val color: Int, val x: Double, val y: Double, val text: String, val objectId: Int, val reach: Double,
  ) {
    var column: ArrayList<PendingTitle>? = null
  }

  private class Deferred(val stage: Int, val step: () -> Unit)

  companion object {
    /** Разрешение ссылок: `pntRef`, `refStart`/`refEnd`, `alignmentRef`… */
    const val STAGE_REFERENCES = 0
    /** Разбиение кривых по допуску от размера сцены. */
    const val STAGE_CURVES = 1
    /** Посадка линий и точек на поверхность. */
    const val STAGE_DRAPE = 2

    const val TICK_EVERY = 1024
    // подпись точки: подъём нижней строки над точкой, шаг строк, сдвиг вправо от значка, dp (в эталоне 3 и 5)
    private const val POINT_LIFT_DP = 3.0
    private const val POINT_LINE_DP = LabelScale.POINT_DP * 1.15
    private const val POINT_SHIFT_DP = LabelScale.MARK_SHIFT_DP
    // шаг строк столбика заголовков, dp
    private const val TITLE_LINE_DP = LabelScale.TITLE_DP * 1.2

    /** Отметка в подписи точки: два знака после запятой, «140,25». */
    fun elevationText(z: Double): String {
      if (!z.isFinite()) return z.toString()
      val hundredths = (kotlin.math.abs(z) * 100).roundToLong()
      val sign = if (z < 0 && hundredths != 0L) "-" else ""
      return sign + hundredths / 100 + "," + (hundredths % 100).toString().padStart(2, '0')
    }
  }
}
