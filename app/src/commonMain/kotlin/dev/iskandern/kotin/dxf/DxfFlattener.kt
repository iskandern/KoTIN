package dev.iskandern.kotin.dxf

import dev.iskandern.kotin.model.ALPHA_OPAQUE
import dev.iskandern.kotin.model.ArcSteps
import dev.iskandern.kotin.model.EXTRUSION_ENTITY
import dev.iskandern.kotin.model.EXTRUSION_NONE
import dev.iskandern.kotin.model.EXTRUSION_POINT
import dev.iskandern.kotin.model.FillBatch
import dev.iskandern.kotin.model.GrowingDoubleArray
import dev.iskandern.kotin.model.GrowingIntArray
import dev.iskandern.kotin.model.LayerInfo
import dev.iskandern.kotin.model.LineBatch
import dev.iskandern.kotin.model.MAX_SCENE_SEGMENTS
import dev.iskandern.kotin.model.MeshBuilder
import dev.iskandern.kotin.model.NO_OBJECT
import dev.iskandern.kotin.model.ObjectRuns
import dev.iskandern.kotin.model.PointMark
import dev.iskandern.kotin.model.SceneGeometry
import dev.iskandern.kotin.model.SceneLabel
import dev.iskandern.kotin.model.SceneObject
import dev.iskandern.kotin.model.toDegrees
import dev.iskandern.kotin.model.toRadians
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Раскрывает вхождения блоков (INSERT, MINSERT, вложенные блоки, размеры, таблицы) и переводит все сущности
 * в мировые координаты: 3DFACE и polyface — в сетки по слоям, кривые — в отрезки, тексты и точки — в метки.
 *
 * Наследование по правилам AutoCAD: сущность блока на слое «0» получает слой вхождения,
 * цвет BYBLOCK — цвет вхождения, BYLAYER — цвет итогового слоя.
 */
class DxfFlattener(private val document: DxfDocument, private val maxSegments: Int = MAX_SCENE_SEGMENTS) {

  private val meshes = LinkedHashMap<String, MeshBuilder>()
  private val batches = LinkedHashMap<BatchKey, GrowingDoubleArray>()
  private val fills = LinkedHashMap<BatchKey, FillBuffer>()
  // градиентные заливки: ещё и цвета вершин
  private val gradientFills = LinkedHashMap<BatchKey, FillBuffer>()
  // номер следующей заливки в порядке прорисовки
  private var fillOrder = 0
  private val labels = ArrayList<SceneLabel>()
  // из них — текст типов линий
  private var patternLabels = 0
  private val points = ArrayList<PointMark>()
  private val usedLayers = LinkedHashSet<String>()
  // выбираемые объекты: по одному на сущность верхнего уровня (вставка блока — одна целиком), заводятся при первом примитиве
  private val objects = ArrayList<SceneObject>()
  private var currentEntity: DxfEntity? = null
  private var currentObjectId = NO_OBJECT
  private val lineRuns = HashMap<BatchKey, ObjectRuns>()
  private val expanding = HashSet<String>()
  private var segments = 0
  private var truncated = false
  // грани тел, которые не рисуются (не плоские), и тела без читаемого SAT — для статистики
  private var hiddenBodyFaces = 0
  private var unreadBodies = 0
  // диагональ габаритов модели (0 — неизвестна): по ней выбирается допуск гладкости дуг
  private var sceneSize = 0.0
  // те же габариты (min xyz, max xyz); null — сущностей с координатами нет
  private var entityBox: DoubleArray? = null

  /** Центр и диагональ габаритов сущностей и граней модели — докуда тянуть XLINE и RAY; null — сцена пуста. */
  private val xlineExtent: DoubleArray? by lazy {
    val box = entityBox?.copyOf() ?: doubleArrayOf(
      Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE,
    )
    for (mesh in document.faceMeshes.values) {
      if (mesh.minX > mesh.maxX) continue
      box[0] = minOf(box[0], mesh.minX); box[1] = minOf(box[1], mesh.minY); box[2] = minOf(box[2], mesh.minZ)
      box[3] = maxOf(box[3], mesh.maxX); box[4] = maxOf(box[4], mesh.maxY); box[5] = maxOf(box[5], mesh.maxZ)
    }
    if (box[0] > box[3]) return@lazy null
    doubleArrayOf(
      (box[0] + box[3]) / 2, (box[1] + box[4]) / 2, (box[2] + box[5]) / 2,
      hypot(hypot(box[3] - box[0], box[4] - box[1]), box[5] - box[2]),
    )
  }

  /** Приёмник точек кривой: [Path] (мировые отрезки) или контур хэтча (только x, y). */
  private interface PointSink {
    fun add(x: Double, y: Double, z: Double)
  }

  private val lineTypeCache = HashMap<String, DxfLineType>()

  // alpha — только у заливок, линии непрозрачны
  private data class BatchKey(
    val layer: String, val color: Int, val flat: Boolean, val lineWeight: Int = 0, val extrusion: Int = EXTRUSION_NONE,
    val unbounded: Boolean = false, val alpha: Int = ALPHA_OPAQUE,
  )

  /** Треугольники пакета заливок, номер заливки на каждый и (у градиента) цвета вершин. */
  private class FillBuffer {
    val coords = GrowingDoubleArray()
    val orders = GrowingIntArray()
    val colors = GrowingIntArray()
    val runs = ObjectRuns()
  }

  /** Итоговые слой и цвет сущности на её месте в чертеже. */
  private class Placement(
    val transform: DxfTransform, val layer: String, val color: Int,
    val lineType: DxfLineType = DxfLineType.CONTINUOUS,
    /** Масштаб паттерна сущности: `$LTSCALE` × код 48 × масштаб вхождений блоков. */
    val patternScale: Double = 1.0,
    /** Накопленный масштаб вхождений блоков — паттерн блока растёт вместе с ним. */
    val unitScale: Double = 1.0,
    /** Итоговая толщина линии, 1/100 мм; 0 — по умолчанию. */
    val lineWeight: Int = 0,
    /** Итоговая непрозрачность 0…255. */
    val alpha: Int = ALPHA_OPAQUE,
  )

  /** Объект текущей сущности верхнего уровня; заводится при первом обращении, чтобы пустые сущности не плодили объекты. */
  private fun currentObject(): Int {
    if (currentObjectId != NO_OBJECT) return currentObjectId
    val entity = currentEntity ?: return NO_OBJECT
    val common = entity.common
    objects.add(
      SceneObject(
        kind = objectKind(entity), name = objectName(entity), layer = common.layer,
        color = resolveColor(common, common.layer, DxfColors.WHITE, isInBlock = false),
      )
    )
    currentObjectId = objects.size - 1
    return currentObjectId
  }

  private fun objectKind(entity: DxfEntity): String = entity.sourceKind ?: when (entity) {
    is DxfEntity.Insert -> if (entity.blockName.startsWith("*D", ignoreCase = true)) "DIMENSION" else "INSERT"
    is DxfEntity.Face -> "3DFACE"
    is DxfEntity.Polyface -> "POLYFACE"
    is DxfEntity.Body -> "3DSOLID"
    is DxfEntity.Line -> "LINE"
    is DxfEntity.XLine -> "XLINE"
    is DxfEntity.Polyline -> "POLYLINE"
    is DxfEntity.Arc -> if (entity.isCircle) "CIRCLE" else "ARC"
    is DxfEntity.Ellipse -> "ELLIPSE"
    is DxfEntity.Spline -> "SPLINE"
    is DxfEntity.Solid -> "SOLID"
    is DxfEntity.Wipeout -> "WIPEOUT"
    is DxfEntity.Image -> "IMAGE"
    is DxfEntity.Hatch -> "HATCH"
    is DxfEntity.MLine -> "MLINE"
    is DxfEntity.Point -> "POINT"
    is DxfEntity.Text -> if (entity.inOcs) "TEXT" else "MTEXT"
  }

  private fun objectName(entity: DxfEntity): String = when (entity) {
    is DxfEntity.Insert -> entity.blockName
    is DxfEntity.Text -> entity.value.take(MAX_OBJECT_NAME)
    else -> ""
  }

  /** Пакет отрезков с этим ключом; отрезки, добавленные дальше, принадлежат текущему объекту. */
  private fun lineBuffer(key: BatchKey): GrowingDoubleArray {
    val buffer = batches.getOrPut(key) { GrowingDoubleArray() }
    lineRuns.getOrPut(key) { ObjectRuns() }.mark(buffer.size / 6, currentObject())
    return buffer
  }

  /** Сетка слоя; треугольники, добавленные дальше, принадлежат текущему объекту. */
  private fun meshBuilder(layer: String): MeshBuilder {
    val builder = meshes.getOrPut(layer) { MeshBuilder(layer) }
    builder.markObject(currentObject())
    return builder
  }

  fun flatten(): SceneGeometry {
    // грани модели уже собраны читателем
    for ((layer, builder) in document.faceMeshes) meshes[layer] = builder
    usedLayers.addAll(document.layerOrder)

    sceneSize = estimateSceneSize()
    val root = Placement(DxfTransform.IDENTITY, layer = "0", color = DxfColors.WHITE)
    for (entity in document.entities) {
      // части одной сущности (строки MTEXT, стрелка LEADER) — в объекте первой; пустая первая — объект заведёт следующая
      if (!entity.joinsPrevious || currentObjectId == NO_OBJECT) currentEntity = entity
      if (!entity.joinsPrevious) currentObjectId = NO_OBJECT
      place(entity, root, parent = null, depth = 0)
    }
    currentEntity = null
    currentObjectId = NO_OBJECT

    // 3DFACE модели читатель сложил в сетки слоёв без объектов: поверхность слоя — один объект
    val surfaceObjects = HashMap<String, Int>()
    for ((layer, builder) in document.faceMeshes) {
      if (builder.minX > builder.maxX) continue
      objects.add(SceneObject(kind = "3DFACE", name = layer, layer = layer, color = layerColor(layer)))
      surfaceObjects[layer] = objects.size - 1
    }

    val allLayers = LinkedHashMap<String, LayerInfo>()
    for (name in usedLayers) allLayers[name] = document.layers[name] ?: LayerInfo(name, DxfColors.WHITE_ACI, 0, null)
    return SceneGeometry(
      // 3DFACE модели — TIN: обводится внешняя граница
      meshes = meshes.map { (layer, builder) -> builder.build(surfaceObjects[layer] ?: NO_OBJECT, outline = layer in surfaceObjects) }
        .filter { it.triangleCount > 0 },
      lines = batches.map { (key, coords) ->
        LineBatch(
          key.layer, key.color, key.flat, coords.toArray(), key.lineWeight, key.extrusion, key.unbounded,
          objectIds = lineRuns[key]?.expand(coords.size / 6),
        )
      },
      fills = fills.map { (key, fill) ->
        FillBatch(
          key.layer, key.color, key.flat, fill.coords.toArray(),
          extrusion = key.extrusion, orders = fill.orders.toArray(), alpha = key.alpha,
          objectIds = fill.runs.expand(fill.orders.size),
        )
      } +
        gradientFills.map { (key, fill) ->
          val colors = fill.colors.toArray()
          FillBatch(
            key.layer, colors.firstOrNull() ?: GRADIENT_COLOR, key.flat, fill.coords.toArray(), colors, key.extrusion, fill.orders.toArray(), key.alpha,
            objectIds = fill.runs.expand(fill.orders.size),
          )
        },
      labels = labels,
      points = points,
      layers = allLayers.values.toList(),
      truncated = truncated,
      pointMode = document.pointMode,
      pointSize = document.pointSize,
      lineWeightDisplay = document.lineWeightDisplay,
      hiddenBodyFaces = hiddenBodyFaces,
      unreadBodies = unreadBodies,
      savedView = document.savedView,
      objects = objects,
      // у каждого листа свой раскрыватель и свой лимит отрезков; пустые листы (одно окно всего листа) не показываются;
      // модель в окна переносится при выборе листа ([DxfSheet.withModel])
      sheets = document.layouts.filter { it.hasContent }.map { layout ->
        DxfSheet(
          layout.name, DxfFlattener(document.sheetDocument(layout), maxSegments).flatten(),
          layout.viewports.filter { it !== layout.paperViewport && it.showsModel }.map { SheetWindow(it) },
        )
      },
    )
  }

  private fun place(entity: DxfEntity, parentPlacement: Placement, parent: DxfEntity.Insert?, depth: Int) {
    if (segments >= maxSegments) {
      truncated = true
      return
    }
    val common = entity.common
    val layer = if (parent != null && common.layer == LAYER_0) parentPlacement.layer else common.layer
    usedLayers.add(layer)
    val color = resolveColor(common, layer, parentPlacement.color, isInBlock = parent != null)
    val lineType = resolveLineType(common, layer, parentPlacement.lineType, isInBlock = parent != null)
    val placement = Placement(
      parentPlacement.transform, layer, color, lineType,
      patternScale = document.lineTypeScale * common.lineTypeScale * parentPlacement.unitScale,
      unitScale = parentPlacement.unitScale,
      lineWeight = resolveLineWeight(common, layer, parentPlacement.lineWeight, isInBlock = parent != null),
      alpha = resolveAlpha(common, layer, parentPlacement.alpha, isInBlock = parent != null),
    )

    when (entity) {
      is DxfEntity.Insert -> expand(entity, placement, depth)
      is DxfEntity.Face -> addFace(entity, placement)
      is DxfEntity.Polyface -> addPolyface(entity, placement)
      is DxfEntity.Body -> addBody(entity, placement)
      is DxfEntity.Line -> addLine(entity.coords, placement, common)
      is DxfEntity.XLine -> addXLine(entity, placement)
      is DxfEntity.Polyline -> addPolyline(entity, placement)
      is DxfEntity.Arc -> addArc(entity, placement)
      is DxfEntity.Ellipse -> addEllipse(entity, placement)
      is DxfEntity.Spline -> addSpline(entity, placement)
      is DxfEntity.Solid -> addSolid(entity, placement)
      is DxfEntity.Wipeout -> addWipeout(entity, placement)
      is DxfEntity.Image -> addImage(entity, placement)
      is DxfEntity.Hatch -> addHatch(entity, placement, parentPlacement.color, isInBlock = parent != null)
      is DxfEntity.MLine -> addMLine(entity, placement)
      is DxfEntity.Point -> addPoint(entity, placement)
      is DxfEntity.Text -> addText(entity, placement)
    }
  }

  private fun resolveColor(common: DxfCommon, layer: String, parentColor: Int, isInBlock: Boolean): Int {
    val trueColor = common.trueColor
    if (trueColor != null) return trueColor and 0xFFFFFF
    return when (common.color) {
      DxfCommon.COLOR_BY_LAYER -> layerColor(layer)
      DxfCommon.COLOR_BY_BLOCK -> if (isInBlock) parentColor else DxfColors.WHITE
      else -> DxfColors.aciToRgb(common.color)
    }
  }

  private fun resolveLineType(common: DxfCommon, layer: String, parentType: DxfLineType, isInBlock: Boolean): DxfLineType {
    val name = common.lineType
    if (name == null || name.equals(BY_LAYER, ignoreCase = true)) return findLineType(document.layers[layer]?.lineType)
    if (name.equals(BY_BLOCK, ignoreCase = true)) return if (isInBlock) parentType else DxfLineType.CONTINUOUS
    return findLineType(name)
  }

  private fun resolveLineWeight(common: DxfCommon, layer: String, parentWeight: Int, isInBlock: Boolean): Int =
    when (val weight = common.lineWeight) {
      LINE_WEIGHT_BY_LAYER -> document.layers[layer]?.lineWeight?.coerceAtLeast(0) ?: 0
      LINE_WEIGHT_BY_BLOCK -> if (isInBlock) parentWeight else 0
      else -> weight.coerceAtLeast(0)
    }

  private fun resolveAlpha(common: DxfCommon, layer: String, parentAlpha: Int, isInBlock: Boolean): Int =
    when (val transparency = common.transparency) {
      DxfCommon.TRANSPARENCY_BY_LAYER -> document.layers[layer]?.alpha ?: ALPHA_OPAQUE
      DxfCommon.TRANSPARENCY_BY_BLOCK -> if (isInBlock) parentAlpha else ALPHA_OPAQUE
      else -> dxfTransparencyAlpha(transparency) ?: ALPHA_OPAQUE
    }

  private fun findLineType(name: String?): DxfLineType {
    if (name == null) return DxfLineType.CONTINUOUS
    return lineTypeCache.getOrPut(name) { document.lineTypes[name.uppercase()] ?: DxfLineType.CONTINUOUS }
  }

  private fun layerColor(name: String): Int {
    val layer = document.layers[name] ?: return DxfColors.WHITE
    val trueColor = layer.trueColor
    if (trueColor != null) return trueColor and 0xFFFFFF
    return DxfColors.aciToRgb(layer.color)
  }

  // ---------- блоки ----------

  private fun expand(insert: DxfEntity.Insert, placement: Placement, depth: Int) {
    // атрибуты записаны в той же системе координат, что и само вхождение
    for (attribute in insert.attributes) place(attribute, placement, parent = null, depth = depth)

    val key = insert.blockName.uppercase()
    val block = document.blocks[key] ?: return
    // защита от блока, вставляющего сам себя
    if (depth >= MAX_DEPTH || !expanding.add(key)) return

    val common = insert.common
    val toInsert = placement.transform * DxfTransform.fromExtrusion(common.nx, common.ny, common.nz) *
      DxfTransform.translation(insert.x, insert.y, insert.z) * DxfTransform.rotationZ(insert.angleDeg)
    val insertScale = (abs(insert.sx) + abs(insert.sy)) / 2
    val toBlock = DxfTransform.scale(insert.sx, insert.sy, insert.sz) *
      DxfTransform.translation(-block.bx, -block.by, -block.bz)

    for (row in 0 until insert.rows.coerceAtLeast(1)) {
      for (column in 0 until insert.columns.coerceAtLeast(1)) {
        // шаг массива MINSERT — в повёрнутой, но не масштабированной системе вхождения
        val cell = DxfTransform.translation(column * insert.columnSpacing, row * insert.rowSpacing, 0.0)
        val childPlacement = Placement(
          toInsert * cell * toBlock, placement.layer, placement.color, placement.lineType,
          unitScale = placement.unitScale * insertScale,
          lineWeight = placement.lineWeight,
          alpha = placement.alpha,
        )
        for (child in block.entities) place(child, childPlacement, parent = insert, depth = depth + 1)
      }
    }
    expanding.remove(key)
  }

  // ---------- поверхности ----------

  private val faceCorners = DoubleArray(12)

  private fun addFace(face: DxfEntity.Face, placement: Placement) {
    val t = placement.transform
    val c = face.corners
    for (i in 0 until 4) {
      val o = i * 3
      faceCorners[o] = t.x(c[o], c[o + 1], c[o + 2])
      faceCorners[o + 1] = t.y(c[o], c[o + 1], c[o + 2])
      faceCorners[o + 2] = t.z(c[o], c[o + 1], c[o + 2])
    }
    meshBuilder(placement.layer).addFace(faceCorners, face.invisible)
  }

  private fun addTriangle(c: DoubleArray, placement: Placement) {
    val t = placement.transform
    meshBuilder(placement.layer).addTriangle(
      t.x(c[0], c[1], c[2]), t.y(c[0], c[1], c[2]), t.z(c[0], c[1], c[2]),
      t.x(c[3], c[4], c[5]), t.y(c[3], c[4], c[5]), t.z(c[3], c[4], c[5]),
      t.x(c[6], c[7], c[8]), t.y(c[6], c[7], c[8]), t.z(c[6], c[7], c[8]),
    )
  }

  private fun addPolyface(polyface: DxfEntity.Polyface, placement: Placement) {
    val v = polyface.xyz
    val count = v.size / 3
    fun corner(i: Int) = doubleArrayOf(v[i * 3], v[i * 3 + 1], v[i * 3 + 2])
    // грань с n углами — веер от первого угла; повтор соседнего угла (треугольник в 4-угольной записи) и неверный индекс пропускаются
    for (face in polyface.faces) {
      for (k in 2 until face.size) {
        val a = face[0]
        val b = face[k - 1]
        val c = face[k]
        if (a !in 0 until count || b !in 0 until count || c !in 0 until count || c == b) continue
        addTriangle(corner(a) + corner(b) + corner(c), placement)
      }
    }
  }

  /**
   * Тело ACIS: плоская грань — петли рёбер (дуги с шагом [arcSteps]) в мировых координатах, проекция на плоскость
   * грани и триангуляция с отверстиями ([HatchTriangulator]); кривая (конус, цилиндр, тор, сфера) — [ParamFace]. Вершины
   * у каждой грани свои, в каркас идут только рёбра петель, не диагонали треугольников. Остальные грани и тела
   * без SAT только считаются.
   */
  private fun addBody(body: DxfEntity.Body, placement: Placement) {
    val acis = body.acis
    if (acis == null) {
      unreadBodies++
      return
    }
    hiddenBodyFaces += acis.skippedFaces
    if (acis.faces.isEmpty()) return
    val builder = meshBuilder(placement.layer)
    for (face in acis.faces) {
      // лимит — и между гранями: одно большое тело не уходит за 5 млн
      if (segments >= maxSegments) {
        truncated = true
        return
      }
      builder.beginGroup()
      val surface = face.surface
      if (surface == null) {
        segments += addPlanarFace(face.loops.map { sampleBodyLoop(it, placement) }, builder)
      } else {
        val n = addCurvedFace(face.loops.map { sampleBodyLoop(it, placement, world = false) }, surface, placement, builder)
        if (n == 0) hiddenBodyFaces++
        segments += n
      }
      builder.endGroup()
    }
  }

  // грань кривой поверхности: (u, v) и сетка — в координатах тела, вершины — в мировые
  private fun addCurvedFace(loops: List<DoubleArray>, surface: AcisSurface, placement: Placement, builder: MeshBuilder): Int {
    val t = placement.transform
    val scale = hypot(t.dx(1.0, 0.0, 0.0), t.dy(1.0, 0.0, 0.0))
    return ParamFace.triangulate(loops, surface, { r -> ArcSteps.perTurn(r * (if (scale > 0) scale else 1.0), sceneSize) }) { c, edges ->
      builder.addTriangle(
        t.x(c[0], c[1], c[2]), t.y(c[0], c[1], c[2]), t.z(c[0], c[1], c[2]),
        t.x(c[3], c[4], c[5]), t.y(c[3], c[4], c[5]), t.z(c[3], c[4], c[5]),
        t.x(c[6], c[7], c[8]), t.y(c[6], c[7], c[8]), t.z(c[6], c[7], c[8]),
        edges,
      )
    }
  }

  // петля → точки x, y, z в мировых координатах ([world] false — в координатах тела), без повтора первой
  private fun sampleBodyLoop(loop: List<AcisEdge>, placement: Placement, world: Boolean = true): DoubleArray {
    val t = placement.transform
    val out = GrowingDoubleArray()
    fun add(x: Double, y: Double, z: Double) {
      if (!world) {
        out.add(x); out.add(y); out.add(z)
        return
      }
      out.add(t.x(x, y, z)); out.add(t.y(x, y, z)); out.add(t.z(x, y, z))
    }
    for (edge in loop) {
      // сплайн — готовая ломаная без последней точки (она — начало следующего ребра)
      val spline = edge.points
      if (spline != null) {
        for (i in 0 until spline.size / 3 - 1) add(spline[i * 3], spline[i * 3 + 1], spline[i * 3 + 2])
        continue
      }
      val e = edge.ellipse
      val m = e?.major
      val a = if (m == null) 0.0 else sqrt(m[0] * m[0] + m[1] * m[1] + m[2] * m[2])
      if (e == null || m == null || a == 0.0 || e.ratio <= 0.0) {
        add(edge.start[0], edge.start[1], edge.start[2])
        continue
      }
      val c = e.center
      val n = e.normal
      // малая полуось: нормаль × большая · ratio; точка — c + m·cos t + v·sin t
      val vx = (n[1] * m[2] - n[2] * m[1]) * e.ratio
      val vy = (n[2] * m[0] - n[0] * m[2]) * e.ratio
      val vz = (n[0] * m[1] - n[1] * m[0]) * e.ratio
      val bb = a * a * e.ratio * e.ratio
      fun angle(p: DoubleArray): Double {
        val dx = p[0] - c[0]
        val dy = p[1] - c[1]
        val dz = p[2] - c[2]
        return atan2((dx * vx + dy * vy + dz * vz) / bb, (dx * m[0] + dy * m[1] + dz * m[2]) / (a * a))
      }
      val t0 = angle(edge.start)
      // замкнутое ребро (одна вершина) — полный оборот
      var sweep = angle(edge.end) - t0
      if (e.alongCurve) {
        while (sweep <= 0) sweep += 2 * PI
      } else {
        while (sweep >= 0) sweep -= 2 * PI
      }
      val steps = arcSteps(sweep, a, placement)
      for (i in 0 until steps) {
        val p = t0 + sweep * i / steps
        val cos = cos(p)
        val sin = sin(p)
        add(c[0] + m[0] * cos + vx * sin, c[1] + m[1] * cos + vy * sin, c[2] + m[2] * cos + vz * sin)
      }
    }
    return out.toArray()
  }

  /** Плоский многоугольник с отверстиями (петли x, y, z) → треугольники [builder]; возвращает их число. */
  private fun addPlanarFace(loops: List<DoubleArray>, builder: MeshBuilder): Int {
    // нормаль по Ньюэллу: отверстия обходятся навстречу внешней петле и только укорачивают её
    var nx = 0.0
    var ny = 0.0
    var nz = 0.0
    for (p in loops) {
      val k = p.size / 3
      for (i in 0 until k) {
        val a = i * 3
        val b = (i + 1) % k * 3
        nx += (p[a + 1] - p[b + 1]) * (p[a + 2] + p[b + 2])
        ny += (p[a + 2] - p[b + 2]) * (p[a] + p[b])
        nz += (p[a] - p[b]) * (p[a + 1] + p[b + 1])
      }
    }
    val length = sqrt(nx * nx + ny * ny + nz * nz)
    if (length == 0.0 || length.isNaN()) return 0
    nx /= length; ny /= length; nz /= length
    // оси плоскости: u ⟂ n, v = n × u
    var ux = if (abs(nx) < 0.9) 1.0 - nx * nx else -ny * nx
    var uy = if (abs(nx) < 0.9) -nx * ny else 1.0 - ny * ny
    var uz = if (abs(nx) < 0.9) -nx * nz else -ny * nz
    val uLength = sqrt(ux * ux + uy * uy + uz * uz)
    ux /= uLength; uy /= uLength; uz /= uLength
    val vx = ny * uz - nz * uy
    val vy = nz * ux - nx * uz
    val vz = nx * uy - ny * ux

    // точки грани: плоские координаты → номер, рёбра петель — пары номеров
    val ox = loops[0][0]
    val oy = loops[0][1]
    val oz = loops[0][2]
    val points = GrowingDoubleArray()
    val index = HashMap<Pair<Double, Double>, Int>()
    val loopEdges = HashSet<Long>()
    fun edgeKey(a: Int, b: Int): Long = minOf(a, b).toLong() shl 32 or maxOf(a, b).toLong()
    val flat = loops.map { p ->
      val k = p.size / 3
      val xy = DoubleArray(k * 2)
      val ids = IntArray(k)
      for (i in 0 until k) {
        val dx = p[i * 3] - ox
        val dy = p[i * 3 + 1] - oy
        val dz = p[i * 3 + 2] - oz
        xy[i * 2] = dx * ux + dy * uy + dz * uz
        xy[i * 2 + 1] = dx * vx + dy * vy + dz * vz
        ids[i] = index.getOrPut(xy[i * 2] to xy[i * 2 + 1]) {
          for (axis in 0..2) points.add(p[i * 3 + axis])
          points.size / 3 - 1
        }
      }
      for (i in 0 until k) loopEdges.add(edgeKey(ids[i], ids[(i + 1) % k]))
      xy
    }

    val triangles = HatchTriangulator.triangulate(flat)
    for (t in 0 until triangles.size / 6) {
      // триангуляция возвращает те же плоские координаты, что получила
      val a = index[triangles[t * 6] to triangles[t * 6 + 1]] ?: continue
      val b = index[triangles[t * 6 + 2] to triangles[t * 6 + 3]] ?: continue
      val c = index[triangles[t * 6 + 4] to triangles[t * 6 + 5]] ?: continue
      val edges = (if (edgeKey(a, b) in loopEdges) 1 else 0) or (if (edgeKey(b, c) in loopEdges) 2 else 0) or
        (if (edgeKey(c, a) in loopEdges) 4 else 0)
      builder.addTriangle(
        points[a * 3], points[a * 3 + 1], points[a * 3 + 2],
        points[b * 3], points[b * 3 + 1], points[b * 3 + 2],
        points[c * 3], points[c * 3 + 1], points[c * 3 + 2],
        edges,
      )
    }
    return triangles.size / 6
  }

  // ---------- кривые ----------

  /**
   * Ломаная в системе [ocs] (или мировой) → отрезки в мировых координатах.
   * [thicknessOf] — сущность с толщиной (код 39): ломаная выдавливается вдоль её нормали ([extrude]).
   */
  private inner class Path(
    private val placement: Placement, ocs: DxfTransform?, thicknessOf: DxfCommon? = null,
    // XLINE / RAY — свой пакет вне габаритов сцены
    private val unbounded: Boolean = false,
  ) : PointSink {
    private val transform = if (ocs == null) placement.transform else placement.transform * ocs
    private val extrusion = thicknessOf?.let { extrusionVector(it, transform, inOcs = ocs != null) }
    private val coords = GrowingDoubleArray()
    private var flat = true
    // номера точек, с которых паттерн идёт заново (вершины полилинии без PLINEGEN), по возрастанию
    private var restarts: GrowingIntArray? = null

    override fun add(x: Double, y: Double, z: Double) {
      val wx = transform.x(x, y, z)
      val wy = transform.y(x, y, z)
      val wz = transform.z(x, y, z)
      if (wz != 0.0) flat = false
      coords.add(wx)
      coords.add(wy)
      coords.add(wz)
    }

    // замыкает контур первой точкой (она уже в мировых координатах)
    fun close() {
      if (coords.size < 6) return
      val x = coords[0]
      val y = coords[1]
      val z = coords[2]
      coords.add(x)
      coords.add(y)
      coords.add(z)
    }

    // паттерн начинается заново с последней добавленной точки
    fun restartPattern() {
      if (placement.lineType.isSolid || coords.size == 0) return
      val r = restarts ?: GrowingIntArray().also { restarts = it }
      r.add(coords.size / 3 - 1)
    }

    fun commit() {
      val n = coords.size / 3
      if (n < 2) return
      val batch = lineBuffer(BatchKey(placement.layer, placement.color, flat, placement.lineWeight, unbounded = unbounded))
      val start = batch.size
      addPlanLength(n)
      if (placement.lineType.isSolid || !commitDashed(batch, n)) {
        for (i in 0 until n - 1) {
          for (k in 0..5) batch.add(coords[i * 3 + k])
        }
        segments += n - 1
      }
      extrusion?.let { extrude(batch, start, n, it) }
    }

    // длина в плане — у кривых верхнего уровня (не у вставок, текстов, штриховок); по ломаной до штрихов
    private fun addPlanLength(n: Int) {
      when (currentEntity) {
        is DxfEntity.Line, is DxfEntity.Polyline, is DxfEntity.Arc, is DxfEntity.Ellipse, is DxfEntity.Spline -> Unit
        else -> return
      }
      val obj = objects.getOrNull(currentObjectId) ?: return
      var length = 0.0
      for (i in 0 until n - 1) {
        val dx = coords[i * 3 + 3] - coords[i * 3]
        val dy = coords[i * 3 + 4] - coords[i * 3 + 1]
        length += sqrt(dx * dx + dy * dy)
      }
      obj.planLength = if (obj.planLength.isNaN()) length else obj.planLength + length
    }

    /**
     * Толщина: копия только что добавленных отрезков (со штрихами) со сдвигом на [v], рёбра вдоль [v] в концах
     * и изломах ломаной (на дугах и окружностях — нет) и боковые грани заливкой — по два треугольника на сегмент,
     * цвет сущности притемнён, чтобы рёбра и контуры читались. Пакеты толщины отдельные ([EXTRUSION_ENTITY]).
     */
    private fun extrude(batch: GrowingDoubleArray, start: Int, n: Int, v: DoubleArray) {
      val copied = (batch.size - start) / 6
      // у замкнутой ломаной последняя вершина повторяет первую (у окружности — с ошибкой округления)
      val gap = abs(coords[0] - coords[n * 3 - 3]) + abs(coords[1] - coords[n * 3 - 2]) + abs(coords[2] - coords[n * 3 - 1])
      val closed = n > 2 && gap <= CLOSE_TOLERANCE * (1 + abs(coords[0]) + abs(coords[1]) + abs(coords[2]))
      val corners = (0 until if (closed) n - 1 else n).filter { isCorner(it, n, closed) }
      if (segments + copied + corners.size + 2 * (n - 1) > maxSegments) {
        truncated = true
        return
      }
      val lines = lineBuffer(BatchKey(placement.layer, placement.color, flat, placement.lineWeight, EXTRUSION_ENTITY))
      // start кратен 6: i % 3 — номер оси
      for (i in start until batch.size) lines.add(batch[i] + v[i % 3])
      for (i in corners) {
        for (k in 0..2) lines.add(coords[i * 3 + k])
        for (k in 0..2) lines.add(coords[i * 3 + k] + v[k])
      }
      val faceColor = shade(placement.color, EXTRUSION_SIDE_SHADE)
      val faces = GrowingDoubleArray()
      var triangles = 0
      for (i in 0 until n - 1) {
        val a = i * 3
        val b = a + 3
        if (coords[a] == coords[b] && coords[a + 1] == coords[b + 1] && coords[a + 2] == coords[b + 2]) continue
        // (A, B, B + v) и (A, B + v, A + v)
        for (k in 0..2) faces.add(coords[a + k])
        for (k in 0..2) faces.add(coords[b + k])
        for (k in 0..2) faces.add(coords[b + k] + v[k])
        for (k in 0..2) faces.add(coords[a + k])
        for (k in 0..2) faces.add(coords[b + k] + v[k])
        for (k in 0..2) faces.add(coords[a + k] + v[k])
        triangles += 2
      }
      if (triangles > 0) {
        val key = BatchKey(placement.layer, faceColor, flat, extrusion = EXTRUSION_ENTITY, alpha = placement.alpha)
        addFill(fills.getOrPut(key) { FillBuffer() }, faces.toArray())
      }
      segments += copied + corners.size + triangles
    }

    // конец открытой ломаной или поворот круче 25° ([CORNER_COS]); шаг дуги не больше 360° / ArcSteps.MIN_STEPS_PER_TURN — не излом
    private fun isCorner(i: Int, n: Int, closed: Boolean): Boolean {
      if (!closed && (i == 0 || i == n - 1)) return true
      val prev = (if (i == 0) n - 2 else i - 1) * 3
      val next = (i + 1) * 3
      val p = i * 3
      val ax = coords[p] - coords[prev]; val ay = coords[p + 1] - coords[prev + 1]; val az = coords[p + 2] - coords[prev + 2]
      val bx = coords[next] - coords[p]; val by = coords[next + 1] - coords[p + 1]; val bz = coords[next + 2] - coords[p + 2]
      val lengths = sqrt((ax * ax + ay * ay + az * az) * (bx * bx + by * by + bz * bz))
      if (lengths == 0.0) return true
      return (ax * bx + ay * by + az * bz) / lengths < CORNER_COS
    }

    /**
     * Режет ломаную на штрихи типа линии, фаза идёт вдоль ломаной и сбрасывается в [restarts]. Текст элемента
     * ставится подписью в его конце. false — паттерн не применим, рисовать сплошной.
     */
    private fun commitDashed(batch: GrowingDoubleArray, n: Int): Boolean {
      val scale = placement.patternScale
      val elements = placement.lineType.pattern
      val embedded = placement.lineType.embedded
      var period = 0.0
      for (e in elements) period += abs(e)
      period *= scale
      if (!(period > 0) || !period.isFinite()) return false
      var length = 0.0
      for (i in 0 until n - 1) {
        length += hypot(hypot(coords[i * 3 + 3] - coords[i * 3], coords[i * 3 + 4] - coords[i * 3 + 1]), coords[i * 3 + 5] - coords[i * 3 + 2])
      }
      if (length / period * elements.size > MAX_DASHES_PER_PATH) return false

      val dot = period * DOT_FRACTION
      val restarts = restarts
      var nextRestart = 0
      var index = 0
      var remaining = abs(elements[0]) * scale
      for (i in 0 until n - 1) {
        if (restarts != null && nextRestart < restarts.size && restarts[nextRestart] <= i) {
          while (nextRestart < restarts.size && restarts[nextRestart] <= i) nextRestart++
          index = 0
          remaining = abs(elements[0]) * scale
        }
        val x1 = coords[i * 3]; val y1 = coords[i * 3 + 1]; val z1 = coords[i * 3 + 2]
        val dx = coords[i * 3 + 3] - x1; val dy = coords[i * 3 + 4] - y1; val dz = coords[i * 3 + 5] - z1
        val segmentLength = hypot(hypot(dx, dy), dz)
        if (segmentLength == 0.0) continue
        var position = 0.0
        while (position < segmentLength) {
          val element = elements[index]
          if (element == 0.0) {
            // точка — короткий штрих, сама длины не занимает; у элемента с текстом или фигурой точки нет
            val text = embedded?.get(index)
            if (text == null) {
              emit(batch, x1, y1, z1, dx, dy, dz, position / segmentLength, minOf(position + dot, segmentLength) / segmentLength)
            } else {
              addPatternText(text, x1, y1, z1, dx, dy, dz, position / segmentLength, scale)
            }
            index = (index + 1) % elements.size
            remaining = abs(elements[index]) * scale
            continue
          }
          val take = minOf(remaining, segmentLength - position)
          if (element > 0) emit(batch, x1, y1, z1, dx, dy, dz, position / segmentLength, (position + take) / segmentLength)
          position += take
          remaining -= take
          if (remaining <= 0.0) {
            embedded?.get(index)?.let { addPatternText(it, x1, y1, z1, dx, dy, dz, position / segmentLength, scale) }
            index = (index + 1) % elements.size
            remaining = abs(elements[index]) * scale
          }
          if (segments >= maxSegments) {
            truncated = true
            return true
          }
        }
      }
      return true
    }

    private fun emit(batch: GrowingDoubleArray, x: Double, y: Double, z: Double, dx: Double, dy: Double, dz: Double, t1: Double, t2: Double) {
      batch.add(x + dx * t1); batch.add(y + dy * t1); batch.add(z + dz * t1)
      batch.add(x + dx * t2); batch.add(y + dy * t2); batch.add(z + dz * t2)
      segments++
    }

    /** Текст элемента паттерна в точке t сегмента: смещения — вдоль сегмента и влево от него, фигуры не рисуются. */
    private fun addPatternText(text: DxfLineTypeText, x: Double, y: Double, z: Double, dx: Double, dy: Double, dz: Double, t: Double, scale: Double,
    ) {
      if (text.text.isBlank() || patternLabels >= MAX_PATTERN_LABELS) return
      val length = hypot(dx, dy)
      // сегмент вдоль Z: направления в плоскости XY нет — берём ось X
      val ux = if (length > 0) dx / length else 1.0
      val uy = if (length > 0) dy / length else 0.0
      val along = text.dx * scale
      val across = text.dy * scale
      val angle = if (text.absolute) text.rotation else atan2(uy, ux) + text.rotation
      val style = document.textStylesByHandle[text.styleHandle.uppercase()] ?: DxfTextStyle.STANDARD
      patternLabels++
      labels.add(
        SceneLabel(
          layer = placement.layer,
          color = placement.color,
          x = x + dx * t + ux * along - uy * across,
          y = y + dy * t + uy * along + ux * across,
          z = z + dz * t,
          height = text.height * scale,
          rotationDeg = toDegrees(angle),
          text = text.text,
          widthFactor = style.widthFactor,
          obliqueDeg = style.obliqueDeg,
          font = style.font,
          objectId = currentObject(),
        )
      )
    }
  }

  private fun ocs(common: DxfCommon): DxfTransform = DxfTransform.fromExtrusion(common.nx, common.ny, common.nz)

  /**
   * Толщина сущности (код 39) мировым вектором: в OCS — вдоль оси Z, у мировых координат (LINE, POINT) — вдоль
   * нормали 210/220/230; [transform] переводит в мир (масштаб вставки по Z тоже действует). null — толщины нет.
   */
  private fun extrusionVector(common: DxfCommon, transform: DxfTransform, inOcs: Boolean): DoubleArray? {
    val t = common.thickness
    if (t == 0.0 || !t.isFinite()) return null
    var x = 0.0
    var y = 0.0
    var z = t
    if (!inOcs) {
      val length = sqrt(common.nx * common.nx + common.ny * common.ny + common.nz * common.nz)
      if (!(length > 0)) return null
      x = common.nx / length * t
      y = common.ny / length * t
      z = common.nz / length * t
    }
    val v = doubleArrayOf(transform.dx(x, y, z), transform.dy(x, y, z), transform.dz(x, y, z))
    return if (v[0] == 0.0 && v[1] == 0.0 && v[2] == 0.0) null else v
  }

  private fun addLine(c: DoubleArray, placement: Placement, common: DxfCommon) {
    // у LINE точки по стандарту всегда мировые, нормаль к ним не применяется (только к толщине)
    val path = Path(placement, ocs = null, thicknessOf = common)
    path.add(c[0], c[1], c[2])
    path.add(c[3], c[4], c[5])
    path.commit()
  }

  /**
   * XLINE / RAY — отрезок, обрезанный с запасом: от точки на (расстояние до центра сцены + две её диагонали)
   * в обе стороны (у луча — только вперёд). Сцена без размера — [XLINE_FALLBACK_LENGTH].
   */
  private fun addXLine(xline: DxfEntity.XLine, placement: Placement) {
    val t = placement.transform
    val wx = t.x(xline.x, xline.y, xline.z)
    val wy = t.y(xline.x, xline.y, xline.z)
    val wz = t.z(xline.x, xline.y, xline.z)
    // длина направления в мире: вхождение блока может его масштабировать
    val worldDir = hypot(hypot(t.dx(xline.dx, xline.dy, xline.dz), t.dy(xline.dx, xline.dy, xline.dz)), t.dz(xline.dx, xline.dy, xline.dz))
    if (!(worldDir > 0) || !worldDir.isFinite()) return
    val extent = xlineExtent
    val reach = if (extent != null && extent[3] > 0) {
      hypot(hypot(wx - extent[0], wy - extent[1]), wz - extent[2]) + 2 * extent[3]
    } else {
      XLINE_FALLBACK_LENGTH
    }
    val k = reach / worldDir
    val from = if (xline.isRay) 0.0 else -k
    // куски, а не один отрезок: отрезок с концом за камерой GPU теряет целиком, а так пропадает только кусок у камеры
    val path = Path(placement, ocs = null, unbounded = true)
    for (i in 0..XLINE_PIECES) {
      val s = from + (k - from) * i / XLINE_PIECES
      path.add(xline.x + xline.dx * s, xline.y + xline.dy * s, xline.z + xline.dz * s)
    }
    path.commit()
  }

  private fun addPolyline(polyline: DxfEntity.Polyline, placement: Placement) {
    val widths = polyline.widths
    if (widths != null) {
      addWidePolyline(polyline, widths, placement)
      return
    }
    // толщина — только у плоской полилинии
    val path = if (polyline.inOcs) Path(placement, ocs(polyline.common), polyline.common) else Path(placement, null)
    val v = polyline.xyz
    val n = polyline.vertexCount
    val last = if (polyline.closed) n else n - 1
    path.add(v[0], v[1], v[2])
    for (i in 0 until last) {
      // без PLINEGEN паттерн начинается заново в каждой вершине
      if (i > 0 && !polyline.continuousPattern) path.restartPattern()
      val j = (i + 1) % n
      val bulge = polyline.bulges.getOrElse(i) { 0.0 }
      addBulgeSegment(path, placement, v[i * 3], v[i * 3 + 1], v[j * 3], v[j * 3 + 1], v[j * 3 + 2], bulge)
    }
    path.commit()
  }

  /**
   * Сегмент от (x1, y1) до (x2, y2): при bulge ≠ 0 — дуга, bulge = tg(угол/4), знак — направление.
   * Центр дуги — на перпендикуляре к хорде; точки добавляются без начальной.
   */
  private fun addBulgeSegment(path: PointSink, placement: Placement, x1: Double, y1: Double, x2: Double, y2: Double, z: Double, bulge: Double) {
    val dx = x2 - x1
    val dy = y2 - y1
    val chord = hypot(dx, dy)
    if (bulge == 0.0 || chord == 0.0) {
      path.add(x2, y2, z)
      return
    }
    val sagitta = bulge * chord / 2
    val radius = (chord * chord / 4 + sagitta * sagitta) / (2 * sagitta)
    val h = radius - sagitta
    val cx = (x1 + x2) / 2 - dy / chord * h
    val cy = (y1 + y2) / 2 + dx / chord * h
    val start = atan2(y1 - cy, x1 - cx)
    val sweep = 4 * atan(bulge)
    val steps = arcSteps(sweep, abs(radius), placement)
    val r = abs(radius)
    for (s in 1..steps) {
      if (s == steps) {
        path.add(x2, y2, z)
        continue
      }
      val a = start + sweep * s / steps
      path.add(cx + r * cos(a), cy + r * sin(a), z)
    }
  }

  /**
   * Полилиния с шириной: сегмент с ненулевой шириной — заливка полосой (трапеция, у дуги — полоса вдоль дуги,
   * ширина меняется линейно), с нулевой — линией, как обычно. Соседние полосы стыкуются срезом по биссектрисе;
   * острый угол или слишком короткий сегмент — скос треугольником, без щели. Ширина — в единицах OCS.
   */
  private fun addWidePolyline(polyline: DxfEntity.Polyline, widths: DoubleArray, placement: Placement) {
    val ocsTransform = if (polyline.inOcs) ocs(polyline.common) else null
    val v = polyline.xyz
    val n = polyline.vertexCount
    val count = if (polyline.closed) n else n - 1
    // полосы сегментов: на точку оси — левый и правый край (lx, ly, rx, ry); null — сегмент рисуется линией
    val strips = arrayOfNulls<DoubleArray>(count)
    var path: Path? = null
    for (i in 0 until count) {
      val j = (i + 1) % n
      val bulge = polyline.bulges.getOrElse(i) { 0.0 }
      val x1 = v[i * 3]; val y1 = v[i * 3 + 1]; val x2 = v[j * 3]; val y2 = v[j * 3 + 1]
      if (widths[i * 2] == 0.0 && widths[i * 2 + 1] == 0.0) {
        val line = path ?: Path(placement, ocsTransform, polyline.common.takeIf { polyline.inOcs })
          .also { it.add(x1, y1, v[i * 3 + 2]); path = it }
        addBulgeSegment(line, placement, x1, y1, x2, y2, v[j * 3 + 2], bulge)
        continue
      }
      path?.commit()
      path = null
      if (x1 != x2 || y1 != y2) strips[i] = wideStrip(x1, y1, x2, y2, bulge, widths[i * 2], widths[i * 2 + 1], placement)
    }
    path?.commit()

    // стыки соседних полос: у замкнутой — и последней с первой
    val bevels = GrowingDoubleArray()
    for (i in 0 until count) {
      if (i == count - 1 && !polyline.closed) break
      val a = strips[i] ?: continue
      val next = (i + 1) % count
      val b = strips[next] ?: continue
      val j = (i + 1) % n
      joinStrips(a, b, v[j * 3], v[j * 3 + 1], bevels)
    }

    val transform = if (ocsTransform == null) placement.transform else placement.transform * ocsTransform
    val xy = GrowingDoubleArray()
    for (i in 0 until count) {
      val strip = strips[i] ?: continue
      for (k in 0 until strip.size / 4 - 1) {
        val p = k * 4
        // четырёхугольник (L0, R0, R1, L1) двумя треугольниками; вырожденные (ширина 0 с одного края) пропускаются
        addTriangle(xy, strip[p], strip[p + 1], strip[p + 2], strip[p + 3], strip[p + 6], strip[p + 7])
        addTriangle(xy, strip[p], strip[p + 1], strip[p + 6], strip[p + 7], strip[p + 4], strip[p + 5])
      }
    }
    for (i in 0 until bevels.size / 6) {
      val p = i * 6
      addTriangle(xy, bevels[p], bevels[p + 1], bevels[p + 2], bevels[p + 3], bevels[p + 4], bevels[p + 5])
    }
    if (xy.size == 0) return
    // у плоской полилинии отметка общая; ширина у 3D-полилиний не читается
    val world = toWorld(xy.toArray(), transform, v[2])
    val triangles = world.size / 9
    if (segments + triangles > maxSegments) {
      truncated = true
      return
    }
    addFill(fills.getOrPut(BatchKey(placement.layer, placement.color, isFlat(world), alpha = placement.alpha)) { FillBuffer() }, world)
    segments += triangles
  }

  // треугольники одной заливки (тройки мировых точек) — со следующим номером порядка прорисовки
  private fun addFill(fill: FillBuffer, world: DoubleArray) {
    val order = fillOrder++
    fill.runs.mark(fill.orders.size, currentObject())
    for (value in world) fill.coords.add(value)
    repeat(world.size / 9) { fill.orders.add(order) }
  }

  // треугольник в пары x, y; нулевой площади не добавляется
  private fun addTriangle(xy: GrowingDoubleArray, x1: Double, y1: Double, x2: Double, y2: Double, x3: Double, y3: Double) {
    if ((x2 - x1) * (y3 - y1) - (y2 - y1) * (x3 - x1) == 0.0) return
    xy.add(x1); xy.add(y1); xy.add(x2); xy.add(y2); xy.add(x3); xy.add(y3)
  }

  /** Края полосы сегмента: прямого — две точки оси, дугового — точки дуги; полуширина линейно от [w1]/2 до [w2]/2. */
  private fun wideStrip(x1: Double, y1: Double, x2: Double, y2: Double, bulge: Double, w1: Double, w2: Double, placement: Placement): DoubleArray {
    val dx = x2 - x1
    val dy = y2 - y1
    val chord = hypot(dx, dy)
    if (bulge == 0.0) {
      // нормаль влево по ходу
      val nx = -dy / chord
      val ny = dx / chord
      return doubleArrayOf(
        x1 + nx * w1 / 2, y1 + ny * w1 / 2, x1 - nx * w1 / 2, y1 - ny * w1 / 2,
        x2 + nx * w2 / 2, y2 + ny * w2 / 2, x2 - nx * w2 / 2, y2 - ny * w2 / 2,
      )
    }
    // центр и радиус — как в addBulgeSegment
    val sagitta = bulge * chord / 2
    val radius = (chord * chord / 4 + sagitta * sagitta) / (2 * sagitta)
    val h = radius - sagitta
    val cx = (x1 + x2) / 2 - dy / chord * h
    val cy = (y1 + y2) / 2 + dx / chord * h
    val start = atan2(y1 - cy, x1 - cx)
    val sweep = 4 * atan(bulge)
    val r = abs(radius)
    val steps = arcSteps(sweep, r, placement)
    // против часовой левый край — к центру, по часовой — наружу
    val side = if (sweep > 0) -1.0 else 1.0
    val strip = DoubleArray((steps + 1) * 4)
    for (s in 0..steps) {
      val a = start + sweep * s / steps
      val ux = cos(a)
      val uy = sin(a)
      val px = when (s) { 0 -> x1; steps -> x2; else -> cx + r * ux }
      val py = when (s) { 0 -> y1; steps -> y2; else -> cy + r * uy }
      val half = (w1 + (w2 - w1) * s / steps) / 2 * side
      strip[s * 4] = px + ux * half
      strip[s * 4 + 1] = py + uy * half
      strip[s * 4 + 2] = px - ux * half
      strip[s * 4 + 3] = py - uy * half
    }
    return strip
  }

  /**
   * Стык полосы [a] (конец) и [b] (начало) в вершине ([cx], [cy]): каждый край обоих продлевается до пересечения
   * (срез по биссектрисе). Если края параллельны — ничего; если пересечение дальше [MITER_LIMIT] полуширин или
   * за пределами крайних участков — щель закрывается треугольником (вершина, конец [a], начало [b]) в [bevels].
   */
  private fun joinStrips(a: DoubleArray, b: DoubleArray, cx: Double, cy: Double, bevels: GrowingDoubleArray) {
    val last = a.size - 4
    for (side in 0..1) {
      val o = side * 2
      val ax0 = a[last - 4 + o]; val ay0 = a[last - 3 + o]
      val ax1 = a[last + o]; val ay1 = a[last + 1 + o]
      val bx0 = b[o]; val by0 = b[1 + o]
      val bx1 = b[4 + o]; val by1 = b[5 + o]
      val halfA = hypot(ax1 - cx, ay1 - cy)
      val halfB = hypot(bx0 - cx, by0 - cy)
      // у острия (ширина 0) стыковать нечего
      if (halfA == 0.0 || halfB == 0.0) continue
      val dax = ax1 - ax0; val day = ay1 - ay0
      val dbx = bx1 - bx0; val dby = by1 - by0
      val cross = dax * dby - day * dbx
      if (abs(cross) <= 1e-12 * hypot(dax, day) * hypot(dbx, dby)) continue
      // пересечение: A0 + s·dA = B0 + t·dB
      val s = ((bx0 - ax0) * dby - (by0 - ay0) * dbx) / cross
      val t = ((bx0 - ax0) * day - (by0 - ay0) * dax) / cross
      val mx = ax0 + dax * s
      val my = ay0 + day * s
      // обе полосы продлеваются (внешний край) или обе обрезаются (внутренний), но не дальше своего крайнего участка;
      // продление одной с обрезкой другой — это смена ширины без поворота, срезать нечего
      val outer = s >= 1 && t <= 0
      val inner = s <= 1 && t >= 0
      if (s > 0 && t < 1 && (outer || inner) && hypot(mx - cx, my - cy) <= MITER_LIMIT * maxOf(halfA, halfB)) {
        a[last + o] = mx; a[last + 1 + o] = my
        b[o] = mx; b[1 + o] = my
      } else {
        bevels.add(cx); bevels.add(cy); bevels.add(ax1); bevels.add(ay1); bevels.add(bx0); bevels.add(by0)
      }
    }
  }

  private fun addArc(arc: DxfEntity.Arc, placement: Placement) {
    val start = toRadians(arc.startDeg)
    var end = toRadians(arc.endDeg)
    // дуга идёт против часовой стрелки от начала к концу
    while (end <= start) end += 2 * PI
    val sweep = if (arc.isCircle) 2 * PI else end - start
    val steps = arcSteps(sweep, arc.radius, placement)
    val path = Path(placement, ocs(arc.common), arc.common)
    for (s in 0..steps) {
      val a = start + sweep * s / steps
      path.add(arc.cx + arc.radius * cos(a), arc.cy + arc.radius * sin(a), arc.cz)
    }
    path.commit()
  }

  private fun addEllipse(e: DxfEntity.Ellipse, placement: Placement) {
    // малая ось = (нормаль × большая ось) · отношение
    val c = e.common
    val nLength = sqrt(c.nx * c.nx + c.ny * c.ny + c.nz * c.nz).takeIf { it > 0 } ?: 1.0
    val nx = c.nx / nLength
    val ny = c.ny / nLength
    val nz = c.nz / nLength
    val vx = (ny * e.mz - nz * e.my) * e.ratio
    val vy = (nz * e.mx - nx * e.mz) * e.ratio
    val vz = (nx * e.my - ny * e.mx) * e.ratio
    var end = e.endParam
    while (end <= e.startParam) end += 2 * PI
    val sweep = end - e.startParam
    val steps = arcSteps(sweep, maxOf(sqrt(e.mx * e.mx + e.my * e.my + e.mz * e.mz), hypot(hypot(vx, vy), vz)), placement)
    val path = Path(placement, ocs = null)
    for (s in 0..steps) {
      val t = e.startParam + sweep * s / steps
      val ct = cos(t)
      val st = sin(t)
      path.add(e.cx + e.mx * ct + vx * st, e.cy + e.my * ct + vy * st, e.cz + e.mz * ct + vz * st)
    }
    path.commit()
  }

  /**
   * Сплайн: при согласованном узловом векторе — B-сплайн по алгоритму де Бура (рациональные — в однородных
   * координатах с делением на вес). Без согласованных контрольных точек — интерполяционный кубический сплайн через
   * опорные точки ([interpolateFitPoints]), тем же де Буром; иначе — ломаная через контрольные точки.
   */
  private fun addSpline(spline: DxfEntity.Spline, placement: Placement) {
    val control = spline.control
    val n = control.size / 3
    val degree = spline.degree
    val knots = spline.knots
    val path = Path(placement, ocs = null)
    val hasBSpline = degree >= 1 && n > degree && knots.size == n + degree + 1 && knots[n] > knots[degree]
    val interpolated = if (hasBSpline) null else interpolateFitPoints(spline.fit, spline.startTangent, spline.endTangent, spline.closed)
    when {
      hasBSpline -> sampleBSpline(control, spline.weights, knots, degree, n, path)
      interpolated != null -> sampleFitSpline(interpolated, path)
      else -> for (i in 0 until n) path.add(control[i * 3], control[i * 3 + 1], control[i * 3 + 2])
    }
    // замкнутый сплайн по опорным точкам уже периодический и сам возвращается в начало
    if (spline.closed && interpolated == null) path.close()
    path.commit()
  }

  // B-сплайн по де Буру: точек на кривой — по SPLINE_SAMPLES_PER_POINT на каждую из [points] исходных точек
  private fun sampleBSpline(
    control: DoubleArray, weights: DoubleArray?, knots: DoubleArray, degree: Int, points: Int, sink: PointSink,
  ) {
    val n = control.size / 3
    val start = knots[degree]
    val end = knots[n]
    val samples = (points * SPLINE_SAMPLES_PER_POINT).coerceIn(SPLINE_MIN_SAMPLES, SPLINE_MAX_SAMPLES)
    val out = DoubleArray(3)
    for (i in 0..samples) {
      deBoor(control, weights, knots, degree, start + (end - start) * i / samples, out)
      sink.add(out[0], out[1], out[2])
    }
  }

  // у сплайна по опорным точкам три контрольные точки на пролёт — плотность считаем по пролётам
  private fun sampleFitSpline(fit: FitSpline, sink: PointSink) =
    sampleBSpline(fit.control, null, fit.knots, 3, (fit.control.size / 3 - 1) / 3 + 1, sink)

  private fun deBoor(control: DoubleArray, weights: DoubleArray?, knots: DoubleArray, degree: Int, u: Double, out: DoubleArray) {
    val n = control.size / 3
    // отрезок узлового вектора с u; конец области — в последний непустой
    var span = degree
    while (span < n - 1 && u >= knots[span + 1]) span++
    // однородные координаты (x·w, y·w, z·w, w); без весов w = 1
    val d = Array(degree + 1) { j ->
      val i = span - degree + j
      val w = weights?.get(i) ?: 1.0
      doubleArrayOf(control[i * 3] * w, control[i * 3 + 1] * w, control[i * 3 + 2] * w, w)
    }
    for (r in 1..degree) {
      for (j in degree downTo r) {
        val left = knots[j + span - degree]
        val right = knots[j + 1 + span - r]
        val alpha = if (right == left) 0.0 else (u - left) / (right - left)
        for (k in 0..3) d[j][k] = (1 - alpha) * d[j - 1][k] + alpha * d[j][k]
      }
    }
    val w = d[degree][3]
    for (k in 0..2) out[k] = d[degree][k] / w
  }

  private fun addSolid(solid: DxfEntity.Solid, placement: Placement) {
    // порядок углов SOLID: 1, 2, 4, 3 (треугольник, если 4-й совпадает с 3-м)
    val c = solid.corners
    // с толщиной — призма: боковые грани и рёбра даёт контур, верхнюю грань — заливка
    val path = Path(placement, ocs(solid.common), solid.common)
    val triangle = c[9] == c[6] && c[10] == c[7] && c[11] == c[8]
    for (i in intArrayOf(0, 1, 3, 2)) {
      if (i == 3 && triangle) continue
      path.add(c[i * 3], c[i * 3 + 1], c[i * 3 + 2])
    }
    path.close()
    path.commit()
    addSolidFill(solid, placement, triangle)
  }

  // заливка SOLID/TRACE: треугольники 1-2-4-3 → (1, 2, 4) и (1, 4, 3); если 4-й угол совпадает с 3-м — один (1, 2, 3)
  private fun addSolidFill(solid: DxfEntity.Solid, placement: Placement, triangle: Boolean) {
    val c = solid.corners
    val transform = placement.transform * ocs(solid.common)
    val world = DoubleArray(12)
    var flat = true
    for (i in 0..3) {
      val x = c[i * 3]
      val y = c[i * 3 + 1]
      val z = c[i * 3 + 2]
      world[i * 3] = transform.x(x, y, z)
      world[i * 3 + 1] = transform.y(x, y, z)
      world[i * 3 + 2] = transform.z(x, y, z)
      if (world[i * 3 + 2] != 0.0) flat = false
    }
    val corners = if (triangle) intArrayOf(0, 1, 2) else intArrayOf(0, 1, 3, 0, 3, 2)
    val triangles = DoubleArray(corners.size * 3) { world[corners[it / 3] * 3 + it % 3] }
    addFill(fills.getOrPut(BatchKey(placement.layer, placement.color, flat, alpha = placement.alpha)) { FillBuffer() }, triangles)
    segments += if (triangle) 1 else 2

    // верхняя грань призмы — та же заливка со сдвигом на толщину
    val v = extrusionVector(solid.common, transform, inOcs = true) ?: return
    if (segments + 2 > maxSegments) {
      truncated = true
      return
    }
    val top = fills.getOrPut(
      BatchKey(placement.layer, placement.color, flat, extrusion = EXTRUSION_ENTITY, alpha = placement.alpha),
    ) { FillBuffer() }
    addFill(top, DoubleArray(triangles.size) { triangles[it] + v[it % 3] })
    segments += if (triangle) 1 else 2
  }

  // ---------- WIPEOUT ----------

  /**
   * WIPEOUT: многоугольник — непрозрачная заливка цвета фона со своим номером порядка прорисовки, она закрывает
   * более ранние заливки. Линии в сцене всегда над заливками, поэтому уже выданные отрезки обрезаются по контуру
   * в плане (x, y): под маской видно только то, что нарисовано позже. Рамка — линией, если её включает
   * WIPEOUTVARIABLES.
   */
  private fun addWipeout(wipeout: DxfEntity.Wipeout, placement: Placement) {
    val t = placement.transform
    val c = wipeout.xyz
    val n = c.size / 3
    val world = DoubleArray(n * 3)
    for (i in 0 until n) {
      val o = i * 3
      world[o] = t.x(c[o], c[o + 1], c[o + 2])
      world[o + 1] = t.y(c[o], c[o + 1], c[o + 2])
      world[o + 2] = t.z(c[o], c[o + 1], c[o + 2])
    }
    val xy = DoubleArray(n * 2) { world[it / 2 * 3 + it % 2] }
    clipLinesUnder(xy)

    // плоскость контура по Ньютону: высота точек треугольников; стоящая на ребре маска в плане не видна
    var nx = 0.0; var ny = 0.0; var nz = 0.0
    for (i in 0 until n) {
      val a = i * 3
      val b = (i + 1) % n * 3
      nx += (world[a + 1] - world[b + 1]) * (world[a + 2] + world[b + 2])
      ny += (world[a + 2] - world[b + 2]) * (world[a] + world[b])
      nz += (world[a] - world[b]) * (world[a + 1] + world[b + 1])
    }
    val triangles = HatchTriangulator.triangulate(listOf(xy))
    if (triangles.isNotEmpty() && abs(nz) > 1e-9 * (abs(nx) + abs(ny) + abs(nz))) {
      val fill = DoubleArray(triangles.size / 2 * 3)
      for (i in 0 until triangles.size / 2) {
        val x = triangles[i * 2]
        val y = triangles[i * 2 + 1]
        fill[i * 3] = x
        fill[i * 3 + 1] = y
        fill[i * 3 + 2] = world[2] - (nx * (x - world[0]) + ny * (y - world[1])) / nz
      }
      addFill(fills.getOrPut(BatchKey(placement.layer, DxfColors.BACKGROUND, isFlat(fill))) { FillBuffer() }, fill)
      segments += triangles.size / 6
    }

    if (document.wipeoutFrame) {
      val path = Path(placement, ocs = null)
      for (i in 0 until n) path.add(c[i * 3], c[i * 3 + 1], c[i * 3 + 2])
      path.close()
      path.commit()
    }
  }

  // ---------- IMAGE ----------

  /**
   * IMAGE: картинка не грузится — рамка по контуру цветом сущности и подпись с путём из IMAGEDEF (нет связи или пути —
   * «изображение») в центре рамки, вдоль оси U. Подпись в пределах габарита: длинный путь режется с многоточием
   * посередине, имя файла остаётся.
   */
  private fun addImage(image: DxfEntity.Image, placement: Placement) {
    val n = image.xyz.size / 3
    val path = Path(placement, ocs = null)
    for (i in 0 until n) path.add(image.xyz[i * 3], image.xyz[i * 3 + 1], image.xyz[i * 3 + 2])
    path.close()
    path.commit()

    val def = image.defHandle?.let { document.objects[it.uppercase()] } as? DxfImageDef
    val full = def?.path?.trim().orEmpty().ifEmpty { IMAGE_NO_PATH }
    val height = image.height * IMAGE_LABEL_HEIGHT
    val available = image.width * IMAGE_LABEL_FILL
    if (height <= 0.0 || available <= 0.0) return
    // путь не помещается — буквы мельче (не меньше трети высоты), дальше обрезка
    val tail = full.substringAfterLast('/').substringAfterLast('\\')
    var h = height
    val fullWidth = labelWidth(full, 1.0)
    if (fullWidth * h > available) h = maxOf(available / fullWidth, height / IMAGE_LABEL_MIN_SHRINK)
    val label = fitLabel(full, tail, available / h)
    addText(
      DxfEntity.Text(
        image.cx, image.cy, image.cz, h, image.angleDeg, label, inOcs = false, alignH = 1, alignV = 2,
      ).also { it.common.copyFrom(image.common) },
      placement,
    )
  }

  // ширина строки в высотах букв без метрик шрифта: точки и пробелы узкие, остальное — 0,8
  private fun labelWidth(text: String, height: Double): Double =
    text.sumOf { if (it == '.' || it == ',' || it == ' ') 0.35 else 0.8 } * height

  // путь [full] с многоточием посередине в [limit] высот букв: имя файла [tail] в конце остаётся, а если и оно длинное — режется слева
  private fun fitLabel(full: String, tail: String, limit: Double): String {
    if (labelWidth(full, 1.0) <= limit) return full
    var head = full.length - tail.length
    // голова — путь без имени: укорачиваем, пока «голова…имя» не поместится
    while (head > 0 && labelWidth(full.substring(0, head) + "…" + tail, 1.0) > limit) head--
    if (head > 0) return full.substring(0, head) + "…" + tail
    var keep = tail.length
    while (keep > 1 && labelWidth("…" + tail.takeLast(keep), 1.0) > limit) keep--
    return "…" + tail.takeLast(keep)
  }

  // отрезки уже выданных пакетов линий, попавшие под многоугольник [xy] в плане, обрезаются по нему
  private fun clipLinesUnder(xy: DoubleArray) {
    var minX = Double.MAX_VALUE; var minY = Double.MAX_VALUE
    var maxX = -Double.MAX_VALUE; var maxY = -Double.MAX_VALUE
    for (i in 0 until xy.size / 2) {
      minX = minOf(minX, xy[i * 2]); maxX = maxOf(maxX, xy[i * 2])
      minY = minOf(minY, xy[i * 2 + 1]); maxY = maxOf(maxY, xy[i * 2 + 1])
    }
    for (entry in batches.entries) {
      val coords = entry.value
      // новый массив заводится только у пакета, который маска задевает
      var clipped: GrowingDoubleArray? = null
      for (s in 0 until coords.size / 6) {
        val o = s * 6
        val x1 = coords[o]; val y1 = coords[o + 1]
        val x2 = coords[o + 3]; val y2 = coords[o + 4]
        val outside = maxOf(x1, x2) < minX || minOf(x1, x2) > maxX || maxOf(y1, y2) < minY || minOf(y1, y2) > maxY
        if (outside) {
          clipped?.let { for (k in 0..5) it.add(coords[o + k]) }
          continue
        }
        val out = clipped ?: GrowingDoubleArray().also { copy ->
          for (i in 0 until o) copy.add(coords[i])
          clipped = copy
        }
        segments += clipSegment(coords, o, xy, out) - 1
      }
      clipped?.let { entry.setValue(it) }
    }
  }

  /** Части отрезка [c] (с [o]) вне многоугольника [xy] в плане — в [out]; возвращает их число. */
  private fun clipSegment(c: GrowingDoubleArray, o: Int, xy: DoubleArray, out: GrowingDoubleArray): Int {
    val x1 = c[o]; val y1 = c[o + 1]; val z1 = c[o + 2]
    val dx = c[o + 3] - x1; val dy = c[o + 4] - y1; val dz = c[o + 5] - z1
    // параметры пересечений с рёбрами
    val cuts = GrowingDoubleArray()
    cuts.add(0.0)
    cuts.add(1.0)
    val n = xy.size / 2
    var j = n - 1
    for (i in 0 until n) {
      val ax = xy[j * 2]; val ay = xy[j * 2 + 1]
      val ex = xy[i * 2] - ax; val ey = xy[i * 2 + 1] - ay
      val den = dx * ey - dy * ex
      if (den != 0.0) {
        val t = ((ax - x1) * ey - (ay - y1) * ex) / den
        val s = ((ax - x1) * dy - (ay - y1) * dx) / den
        if (t > 0.0 && t < 1.0 && s >= 0.0 && s <= 1.0) cuts.add(t)
      }
      j = i
    }
    val ts = cuts.toArray().also { it.sort() }
    var pieces = 0
    // начало текущей видимой части; NaN — её нет
    var start = Double.NaN
    fun emit(a: Double, b: Double) {
      out.add(x1 + dx * a); out.add(y1 + dy * a); out.add(z1 + dz * a)
      out.add(x1 + dx * b); out.add(y1 + dy * b); out.add(z1 + dz * b)
      pieces++
    }
    for (k in 0 until ts.size - 1) {
      val a = ts[k]
      val b = ts[k + 1]
      if (b <= a) continue
      val mid = (a + b) / 2
      val hidden = HatchTriangulator.contains(xy, x1 + dx * mid, y1 + dy * mid)
      if (!hidden && start.isNaN()) start = a
      if (hidden && !start.isNaN()) {
        emit(start, a)
        start = Double.NaN
      }
    }
    if (!start.isNaN()) emit(start, 1.0)
    return pieces
  }

  // ---------- MLINE ----------

  /**
   * MLINE: каждый элемент стиля — ломаная через точки «вершина + скос × смещение» (смещение из вершины, без
   * него — из стиля × масштаб), цветом и типом линии элемента. По флагам стиля: заливка между крайними элементами,
   * линии скоса в изломах, торцы линией, внешней дугой и внутренними дугами (у замкнутой торцов нет).
   */
  private fun addMLine(mline: DxfEntity.MLine, placement: Placement) {
    val style = document.mlineStyle(mline.styleHandle, mline.styleName)
    val count = style.elementCount
    if (count == 0) return
    val n = mline.vertexCount
    val v = mline.xyz
    val m = mline.miters
    val d = mline.directions
    // точки элементов: [вершина][элемент] → x, y, z
    val pts = DoubleArray(n * count * 3)
    for (i in 0 until n) {
      // смещение стиля — поперёк сегмента; вдоль скоса оно длиннее в 1 / sin(угла между скосом и сегментом)
      val cx = d[i * 3 + 1] * m[i * 3 + 2] - d[i * 3 + 2] * m[i * 3 + 1]
      val cy = d[i * 3 + 2] * m[i * 3] - d[i * 3] * m[i * 3 + 2]
      val cz = d[i * 3] * m[i * 3 + 1] - d[i * 3 + 1] * m[i * 3]
      val lengths = sqrt(d[i * 3] * d[i * 3] + d[i * 3 + 1] * d[i * 3 + 1] + d[i * 3 + 2] * d[i * 3 + 2]) *
        sqrt(m[i * 3] * m[i * 3] + m[i * 3 + 1] * m[i * 3 + 1] + m[i * 3 + 2] * m[i * 3 + 2])
      val sine = if (lengths > 0) sqrt(cx * cx + cy * cy + cz * cz) / lengths else 1.0
      val stretch = if (sine > MIN_MITER_SINE) 1 / sine else 1.0
      for (e in 0 until count) {
        val given = if (e < mline.elementCount) mline.offsets[i * mline.elementCount + e] else Double.NaN
        val offset = if (given.isNaN()) style.offsets[e] * mline.scale * stretch else given
        val o = (i * count + e) * 3
        for (k in 0..2) pts[o + k] = v[i * 3 + k] + m[i * 3 + k] * offset
      }
    }
    fun point(path: Path, i: Int, e: Int) {
      val o = (i * count + e) * 3
      path.add(pts[o], pts[o + 1], pts[o + 2])
    }

    // крайние элементы по смещению стиля
    var outer1 = 0
    var outer2 = 0
    for (e in 1 until count) {
      if (style.offsets[e] > style.offsets[outer1]) outer1 = e
      if (style.offsets[e] < style.offsets[outer2]) outer2 = e
    }
    if (style.flags and DxfMLineStyle.FILL != 0 && outer1 != outer2) addMLineFill(mline, style, pts, count, outer1, outer2, placement)

    for (e in 0 until count) {
      val path = Path(mLineElementPlacement(placement, style.colors[e], style.lineTypes[e]), ocs = null)
      for (i in 0 until n) point(path, i, e)
      if (mline.closed) path.close()
      path.commit()
    }

    // торцы и скосы — цветом самой мультилинии
    val first = if (mline.closed) 0 else 1
    val last = if (mline.closed) n else n - 1
    if (style.flags and DxfMLineStyle.MITERS != 0 && outer1 != outer2) {
      for (i in first until last) {
        val path = Path(placement, ocs = null)
        point(path, i, outer1)
        point(path, i, outer2)
        path.commit()
      }
    }
    if (mline.closed) return
    // элементы от внешнего к внутреннему: пары для дуг — (0, last), (1, last − 1), …
    val order = (0 until count).sortedByDescending { style.offsets[it] }
    fun caps(i: Int, line: Int, round: Int, inner: Int, sign: Double) {
      val d = mline.directions
      // направление наружу: в начале — против первого сегмента, в конце — вдоль последнего
      val di = if (sign < 0) 0 else n - 2
      var ox = d[di * 3] * sign
      var oy = d[di * 3 + 1] * sign
      var oz = d[di * 3 + 2] * sign
      val length = sqrt(ox * ox + oy * oy + oz * oz)
      if (length == 0.0) return
      ox /= length; oy /= length; oz /= length
      if (style.flags and line != 0 && outer1 != outer2) {
        val path = Path(placement, ocs = null)
        point(path, i, outer1)
        point(path, i, outer2)
        path.commit()
      }
      for (k in 0 until count / 2) {
        if (style.flags and (if (k == 0) round else inner) == 0) continue
        addMLineCapArc(pts, (i * count + order[k]) * 3, (i * count + order[count - 1 - k]) * 3, ox, oy, oz, placement)
      }
    }
    if (!mline.suppressStartCaps) caps(0, DxfMLineStyle.START_LINE, DxfMLineStyle.START_ROUND, DxfMLineStyle.START_INNER_ARCS, -1.0)
    if (!mline.suppressEndCaps) caps(n - 1, DxfMLineStyle.END_LINE, DxfMLineStyle.END_ROUND, DxfMLineStyle.END_INNER_ARCS, 1.0)
  }

  /** Полуокружность на отрезке между точками [a] и [b] массива [pts], выгнутая в сторону (ox, oy, oz). */
  private fun addMLineCapArc(pts: DoubleArray, a: Int, b: Int, ox: Double, oy: Double, oz: Double, placement: Placement) {
    val cx = (pts[a] + pts[b]) / 2
    val cy = (pts[a + 1] + pts[b + 1]) / 2
    val cz = (pts[a + 2] + pts[b + 2]) / 2
    val ux = pts[a] - cx
    val uy = pts[a + 1] - cy
    val uz = pts[a + 2] - cz
    val r = sqrt(ux * ux + uy * uy + uz * uz)
    if (r == 0.0) return
    val steps = arcSteps(PI, r, placement)
    val path = Path(placement, ocs = null)
    for (s in 0..steps) {
      val t = PI * s / steps
      val c = cos(t)
      val sn = sin(t) * r
      path.add(cx + ux * c + ox * sn, cy + uy * c + oy * sn, cz + uz * c + oz * sn)
    }
    path.commit()
  }

  /** Заливка мультилинии: четырёхугольник между крайними элементами на каждом сегменте, цвет заливки стиля. */
  private fun addMLineFill(
    mline: DxfEntity.MLine, style: DxfMLineStyle, pts: DoubleArray, count: Int, outer1: Int, outer2: Int, placement: Placement,
  ) {
    val n = mline.vertexCount
    val segmentCount = if (mline.closed) n else n - 1
    if (segments + segmentCount * 2 > maxSegments) {
      truncated = true
      return
    }
    val t = placement.transform
    val world = DoubleArray(segmentCount * 18)
    var w = 0
    fun corner(i: Int, e: Int) {
      val o = (i * count + e) * 3
      val x = pts[o]; val y = pts[o + 1]; val z = pts[o + 2]
      world[w++] = t.x(x, y, z); world[w++] = t.y(x, y, z); world[w++] = t.z(x, y, z)
    }
    for (i in 0 until segmentCount) {
      val j = (i + 1) % n
      corner(i, outer1); corner(i, outer2); corner(j, outer2)
      corner(i, outer1); corner(j, outer2); corner(j, outer1)
    }
    val fill = mLineElementPlacement(placement, style.fillColor, BY_LAYER)
    addFill(fills.getOrPut(BatchKey(fill.layer, fill.color, isFlat(world), alpha = fill.alpha)) { FillBuffer() }, world)
    segments += segmentCount * 2
  }

  /** Цвет и тип линии элемента стиля: BYLAYER и BYBLOCK — как у самой мультилинии (она уже разрешена по слою). */
  private fun mLineElementPlacement(placement: Placement, aci: Int, lineTypeName: String): Placement {
    val color = if (aci == DxfCommon.COLOR_BY_LAYER || aci == DxfCommon.COLOR_BY_BLOCK) placement.color else DxfColors.aciToRgb(aci)
    val lineType = if (lineTypeName.equals(BY_LAYER, ignoreCase = true) || lineTypeName.equals(BY_BLOCK, ignoreCase = true)) {
      placement.lineType
    } else {
      findLineType(lineTypeName)
    }
    return Placement(placement.transform, placement.layer, color, lineType, placement.patternScale, placement.unitScale, placement.lineWeight, placement.alpha)
  }

  // ---------- HATCH ----------

  /**
   * Контуры границы → точки в OCS → заливка (сплошная или градиент: триангуляция с отверстиями) или штриховка
   * (линии паттерна, отсечённые по контурам); всё — в мировые координаты. У MPOLYGON контуры ещё и линией цвета
   * сущности, а заливка и штриховка — своим цветом, если он задан.
   */
  private fun addHatch(hatch: DxfEntity.Hatch, placement: Placement, parentColor: Int, isInBlock: Boolean) {
    val sampled = ArrayList<DoubleArray>()
    for (loop in hatch.loops) sampleHatchLoop(loop, placement)?.let { sampled.add(it) }
    if (sampled.isEmpty()) return
    if (hatch.outline) {
      for (loop in sampled) {
        val path = Path(placement, ocs(hatch.common))
        for (i in 0 until loop.size / 2) path.add(loop[i * 2], loop[i * 2 + 1], hatch.elevation)
        path.close()
        path.commit()
      }
    }
    val loops = HatchTriangulator.applyStyle(sampled, hatch.style)
    val fill = hatch.fillColor?.let { aci ->
      val color = resolveColor(DxfCommon().also { it.color = aci }, placement.layer, parentColor, isInBlock)
      Placement(placement.transform, placement.layer, color, placement.lineType, placement.patternScale, placement.unitScale, placement.lineWeight, placement.alpha)
    } ?: placement
    val transform = placement.transform * ocs(hatch.common)
    val pattern = hatch.pattern
    val gradient = hatch.gradient
    when {
      gradient != null -> addGradientFill(loops, gradient, transform, hatch.elevation, fill)
      pattern == null -> addHatchFill(loops, transform, hatch.elevation, fill.layer, fill.color, fill.alpha)
      else -> addHatchPattern(loops, pattern, transform, hatch.elevation, fill)
    }
  }

  private fun addHatchFill(loops: List<DoubleArray>, transform: DxfTransform, z: Double, layer: String, color: Int, alpha: Int) {
    val xy = HatchTriangulator.triangulate(loops)
    if (xy.isEmpty()) return
    val world = toWorld(xy, transform, z)
    addFill(fills.getOrPut(BatchKey(layer, color, isFlat(world), alpha = alpha)) { FillBuffer() }, world)
    segments += xy.size / 6
  }

  private fun addGradientFill(loops: List<DoubleArray>, gradient: HatchGradient, transform: DxfTransform, z: Double, placement: Placement) {
    val triangles = HatchTriangulator.triangulate(loops)
    if (triangles.isEmpty()) return
    val (xy, colors) = HatchGradientShader.shade(triangles, loops, gradient)
    if (xy.isEmpty()) return
    val world = toWorld(xy, transform, z)
    val fill = gradientFills.getOrPut(BatchKey(placement.layer, GRADIENT_COLOR, isFlat(world), alpha = placement.alpha)) {
      FillBuffer()
    }
    addFill(fill, world)
    for (color in colors) fill.colors.add(color)
    segments += xy.size / 6
  }

  /**
   * Штриховка: отрезки паттерна (свои линии из файла или стандартные ANSI31/ANSI37/DOTS) в пакет линий хэтча.
   * Слишком частая — шаг меньше размера сцены / [HATCH_DENSITY_DIVISOR] или больше [MAX_HATCH_SEGMENTS] отрезков —
   * и паттерн без линий заливаются средним цветом: цвет хэтча пополам с тёмным фоном.
   */
  private fun addHatchPattern(loops: List<DoubleArray>, pattern: HatchPattern, transform: DxfTransform, z: Double, placement: Placement) {
    val lines = pattern.lines.ifEmpty { HatchPatternClipper.standardLines(pattern) }
    // шаг в единицах OCS хэтча: мировой порог делится на масштаб вхождения
    val scale = hypot(hypot(transform.dx(1.0, 0.0, 0.0), transform.dy(1.0, 0.0, 0.0)), transform.z(1.0, 0.0, 0.0) - transform.z(0.0, 0.0, 0.0))
    val minSpacing = if (sceneSize > 0 && scale > 0) sceneSize / HATCH_DENSITY_DIVISOR / scale else 0.0
    val xy = if (lines.isEmpty()) null else HatchPatternClipper.clip(loops, lines, minSpacing, MAX_HATCH_SEGMENTS)
    if (xy == null) {
      addHatchFill(loops, transform, z, placement.layer, shade(placement.color, DENSE_HATCH_SHADE), placement.alpha)
      return
    }
    if (xy.isEmpty()) return
    val world = toWorld(xy, transform, z)
    val batch = lineBuffer(BatchKey(placement.layer, placement.color, isFlat(world), placement.lineWeight))
    for (i in 0 until world.size / 6) {
      for (k in 0..5) batch.add(world[i * 6 + k])
      if (++segments >= maxSegments) {
        truncated = true
        return
      }
    }
  }

  /** Пары x, y в OCS на высоте [z] → тройки мировых координат. */
  private fun toWorld(xy: DoubleArray, transform: DxfTransform, z: Double): DoubleArray {
    val world = DoubleArray(xy.size / 2 * 3)
    for (i in 0 until xy.size / 2) {
      world[i * 3] = transform.x(xy[i * 2], xy[i * 2 + 1], z)
      world[i * 3 + 1] = transform.y(xy[i * 2], xy[i * 2 + 1], z)
      world[i * 3 + 2] = transform.z(xy[i * 2], xy[i * 2 + 1], z)
    }
    return world
  }

  private fun isFlat(world: DoubleArray): Boolean {
    for (i in 0 until world.size / 3) if (world[i * 3 + 2] != 0.0) return false
    return true
  }

  // цвет, смешанный с чёрным: доля [k] от исходного
  private fun shade(rgb: Int, k: Double): Int {
    fun channel(shift: Int) = (((rgb shr shift) and 0xFF) * k + 0.5).toInt().coerceIn(0, 255) shl shift
    return channel(16) or channel(8) or channel(0)
  }

  private class XySink : PointSink {
    val points = GrowingDoubleArray()
    override fun add(x: Double, y: Double, z: Double) {
      points.add(x)
      points.add(y)
    }
  }

  /** Рёбра контура → замкнутая ломаная (пары x, y без повтора первой точки); null — контур не замкнут. */
  private fun sampleHatchLoop(loop: HatchLoop, placement: Placement): DoubleArray? {
    var pts = DoubleArray(0)
    var tol = 0.0
    for ((index, edge) in loop.edges.withIndex()) {
      val sink = XySink()
      sampleHatchEdge(edge, sink, placement)
      var e = sink.points.toArray()
      if (e.size < 4) continue
      if (pts.isEmpty()) {
        pts = e
        tol = HATCH_GAP * (1.0 + pts.maxOf { abs(it) })
        continue
      }
      fun near(a: DoubleArray, i: Int, b: DoubleArray, j: Int) = abs(a[i] - b[j]) <= tol && abs(a[i + 1] - b[j + 1]) <= tol
      val last = pts.size - 2
      val eLast = e.size - 2
      when {
        near(pts, last, e, 0) -> {}
        near(pts, last, e, eLast) -> e = reversedPoints(e)
        // второе ребро стыкуется с началом первого — первое идёт не в ту сторону
        index == 1 && near(pts, 0, e, 0) -> pts = reversedPoints(pts)
        index == 1 && near(pts, 0, e, eLast) -> { pts = reversedPoints(pts); e = reversedPoints(e) }
        else -> return null
      }
      pts += e.copyOfRange(2, e.size)
    }
    if (pts.size < 8) return null
    // последняя точка должна замкнуть контур
    if (abs(pts[0] - pts[pts.size - 2]) > tol || abs(pts[1] - pts[pts.size - 1]) > tol) return null
    return pts.copyOf(pts.size - 2)
  }

  private fun reversedPoints(xy: DoubleArray): DoubleArray {
    val n = xy.size / 2
    return DoubleArray(xy.size) { k -> xy[(n - 1 - k / 2) * 2 + k % 2] }
  }

  private fun sampleHatchEdge(edge: HatchEdge, sink: PointSink, placement: Placement) {
    when (edge) {
      is HatchEdge.Segments -> {
        val v = edge.xy
        val n = v.size / 2
        sink.add(v[0], v[1], 0.0)
        val last = if (edge.closed) n else n - 1
        for (i in 0 until last) {
          val j = (i + 1) % n
          addBulgeSegment(sink, placement, v[i * 2], v[i * 2 + 1], v[j * 2], v[j * 2 + 1], 0.0, edge.bulges.getOrElse(i) { 0.0 })
        }
      }
      is HatchEdge.Arc -> {
        val sweep = hatchSweep(edge.startDeg, edge.endDeg, edge.ccw)
        val start = toRadians(edge.startDeg)
        val steps = arcSteps(sweep, edge.radius, placement)
        for (s in 0..steps) {
          val a = start + sweep * s / steps
          sink.add(edge.cx + edge.radius * cos(a), edge.cy + edge.radius * sin(a), 0.0)
        }
      }
      is HatchEdge.Ellipse -> {
        // малая ось — большая, повёрнутая на 90°, на отношение осей
        val nx = -edge.my * edge.ratio
        val ny = edge.mx * edge.ratio
        val sweep = hatchSweep(edge.startDeg, edge.endDeg, edge.ccw)
        val start = toRadians(edge.startDeg)
        val steps = arcSteps(sweep, maxOf(hypot(edge.mx, edge.my), hypot(nx, ny)), placement)
        for (s in 0..steps) {
          val t = start + sweep * s / steps
          sink.add(edge.cx + edge.mx * cos(t) + nx * sin(t), edge.cy + edge.my * cos(t) + ny * sin(t), 0.0)
        }
      }
      is HatchEdge.Spline -> {
        val n = edge.control.size / 2
        val degree = edge.degree
        val knots = edge.knots
        val hasBSpline = degree >= 1 && n > degree && knots.size == n + degree + 1 && knots[n] > knots[degree]
        // пары x, y → тройки с z = 0
        fun xyz(xy: DoubleArray) = DoubleArray(xy.size / 2 * 3) { if (it % 3 == 2) 0.0 else xy[it / 3 * 2 + it % 3] }
        fun tangent(xy: DoubleArray?) = xy?.let { doubleArrayOf(it[0], it[1], 0.0) }
        val interpolated = if (hasBSpline) null else interpolateFitPoints(
          xyz(edge.fit), tangent(edge.startTangent), tangent(edge.endTangent), closed = false,
        )
        when {
          hasBSpline -> sampleBSpline(xyz(edge.control), edge.weights, knots, degree, n, sink)
          interpolated != null -> sampleFitSpline(interpolated, sink)
          else -> for (i in 0 until n) sink.add(edge.control[i * 2], edge.control[i * 2 + 1], 0.0)
        }
      }
    }
  }

  // угол дуги границы: против часовой — от начала к концу в положительную сторону, иначе в отрицательную
  private fun hatchSweep(startDeg: Double, endDeg: Double, ccw: Boolean): Double {
    val start = toRadians(startDeg)
    var end = toRadians(endDeg)
    if (ccw) {
      while (end <= start) end += 2 * PI
    } else {
      while (end >= start) end -= 2 * PI
    }
    return end - start
  }

  // ---------- точки и тексты ----------

  /** Угол значка (код 50) пересчитывается через вхождение, как направление строки у текста. */
  private fun addPoint(point: DxfEntity.Point, placement: Placement) {
    val t = placement.transform
    val x = t.x(point.x, point.y, point.z)
    val y = t.y(point.x, point.y, point.z)
    val z = t.z(point.x, point.y, point.z)
    val a = toRadians(point.angleDeg)
    points.add(
      PointMark(
        placement.layer, placement.color, x, y, z,
        toDegrees(atan2(t.dy(cos(a), sin(a), 0.0), t.dx(cos(a), sin(a), 0.0))),
        currentObject(),
      )
    )

    // толщина точки — отрезок вдоль нормали, в своём пакете (показывается отдельным переключателем)
    val v = extrusionVector(point.common, t, inOcs = false) ?: return
    if (segments >= maxSegments) {
      truncated = true
      return
    }
    val batch = lineBuffer(BatchKey(placement.layer, placement.color, z == 0.0, placement.lineWeight, EXTRUSION_POINT))
    batch.add(x); batch.add(y); batch.add(z)
    batch.add(x + v[0]); batch.add(y + v[1]); batch.add(z + v[2])
    segments++
  }

  /** Поворот и высота пересчитываются через вхождение: берём образы направлений «вдоль строки» и «вверх». */
  private fun addText(text: DxfEntity.Text, placement: Placement) {
    val transform = if (text.inOcs) placement.transform * ocs(text.common) else placement.transform
    val a = toRadians(text.angleDeg)
    val alongX = transform.dx(cos(a), sin(a), 0.0)
    val alongY = transform.dy(cos(a), sin(a), 0.0)
    val upX = transform.dx(-sin(a), cos(a), 0.0)
    val upY = transform.dy(-sin(a), cos(a), 0.0)
    // неравномерный масштаб вставки меняет ширину букв относительно высоты
    val alongScale = hypot(alongX, alongY)
    val upScale = hypot(upX, upY)
    labels.add(
      SceneLabel(
        layer = placement.layer,
        color = placement.color,
        x = transform.x(text.x, text.y, text.z),
        y = transform.y(text.x, text.y, text.z),
        z = transform.z(text.x, text.y, text.z),
        height = text.height * upScale,
        rotationDeg = toDegrees(atan2(alongY, alongX)),
        text = text.value,
        alignH = text.alignH,
        alignV = text.alignV,
        widthFactor = if (upScale > 0) text.widthFactor * alongScale / upScale else text.widthFactor,
        obliqueDeg = text.obliqueDeg,
        fitLength = text.fitLength * alongScale,
        frame = text.frame?.scaled(alongScale, upScale),
        runs = text.runs,
        background = text.background?.let { DxfTextBackground(it.color, it.margin, it.width * alongScale) },
        font = text.font,
        objectId = currentObject(),
      )
    )
  }

  /** Число шагов дуги ([ArcSteps]): радиус сущности переводится в мировые единицы масштабом вставки. */
  private fun arcSteps(sweep: Double, radius: Double, placement: Placement): Int {
    val scale = hypot(placement.transform.dx(1.0, 0.0, 0.0), placement.transform.dy(1.0, 0.0, 0.0))
    return ArcSteps.steps(sweep, abs(radius) * (if (scale > 0) scale else 1.0), sceneSize)
  }

  /** Диагональ габаритов опорных точек сущностей модели (без блоков и граней — они не нужны для допуска). */
  private fun estimateSceneSize(): Double {
    var minX = Double.MAX_VALUE; var minY = Double.MAX_VALUE; var minZ = Double.MAX_VALUE
    var maxX = -Double.MAX_VALUE; var maxY = -Double.MAX_VALUE; var maxZ = -Double.MAX_VALUE
    fun add(x: Double, y: Double, z: Double) {
      if (x.isNaN() || y.isNaN() || z.isNaN()) return
      if (x < minX) minX = x; if (x > maxX) maxX = x
      if (y < minY) minY = y; if (y > maxY) maxY = y
      if (z < minZ) minZ = z; if (z > maxZ) maxZ = z
    }
    fun addAll(xyz: DoubleArray) { for (i in 0 until xyz.size / 3) add(xyz[i * 3], xyz[i * 3 + 1], xyz[i * 3 + 2]) }
    for (e in document.entities) {
      when (e) {
        is DxfEntity.Line -> addAll(e.coords)
        is DxfEntity.Polyline -> addAll(e.xyz)
        is DxfEntity.Polyface -> addAll(e.xyz)
        is DxfEntity.Face -> addAll(e.corners)
        is DxfEntity.Solid -> addAll(e.corners)
        is DxfEntity.Wipeout -> addAll(e.xyz)
        is DxfEntity.Image -> addAll(e.xyz)
        is DxfEntity.Hatch -> for (loop in e.loops) for (edge in loop.edges) when (edge) {
          is HatchEdge.Segments -> for (i in 0 until edge.xy.size / 2) add(edge.xy[i * 2], edge.xy[i * 2 + 1], e.elevation)
          is HatchEdge.Arc -> { add(edge.cx - edge.radius, edge.cy - edge.radius, e.elevation); add(edge.cx + edge.radius, edge.cy + edge.radius, e.elevation) }
          else -> {}
        }
        is DxfEntity.Spline -> { addAll(e.control); addAll(e.fit) }
        is DxfEntity.MLine -> addAll(e.xyz)
        is DxfEntity.Arc -> { add(e.cx - e.radius, e.cy - e.radius, e.cz); add(e.cx + e.radius, e.cy + e.radius, e.cz) }
        is DxfEntity.Ellipse -> {
          val r = sqrt(e.mx * e.mx + e.my * e.my + e.mz * e.mz)
          add(e.cx - r, e.cy - r, e.cz - r); add(e.cx + r, e.cy + r, e.cz + r)
        }
        is DxfEntity.Point -> add(e.x, e.y, e.z)
        is DxfEntity.Text -> add(e.x, e.y, e.z)
        is DxfEntity.Insert -> add(e.x, e.y, e.z)
        is DxfEntity.Body -> e.acis?.faces?.forEach { face -> face.loops.forEach { loop -> loop.forEach { addAll(it.start) } } }
        else -> {}
      }
    }
    if (minX > maxX) return 0.0
    entityBox = doubleArrayOf(minX, minY, minZ, maxX, maxY, maxZ)
    return ArcSteps.sceneSize(minX, minY, minZ, maxX, maxY, maxZ)
  }

  private companion object {
    const val LAYER_0 = "0"
    // подпись IMAGE: высота букв — доля габарита, занимает не больше доли его ширины; мельче трети не сжимается
    const val IMAGE_LABEL_HEIGHT = 0.2
    const val IMAGE_LABEL_FILL = 0.9
    const val IMAGE_LABEL_MIN_SHRINK = 3.0
    const val IMAGE_NO_PATH = "изображение"
    // допуск стыковки рёбер границы хэтча, доля от размера координат
    const val HATCH_GAP = 1e-6
    // штриховка с шагом меньше размера сцены / этого числа сливается в тон — заливается средним цветом
    const val HATCH_DENSITY_DIVISOR = 1000.0
    // больше отрезков на один хэтч не строим — тоже заливка средним цветом
    const val MAX_HATCH_SEGMENTS = 100_000
    // средний цвет частой штриховки: доля цвета хэтча на тёмном фоне
    const val DENSE_HATCH_SHADE = 0.5
    // ключ пакета градиентных заливок (настоящий цвет — у вершин)
    const val GRADIENT_COLOR = -1
    const val BY_LAYER = "BYLAYER"
    const val BY_BLOCK = "BYBLOCK"
    // слишком мелкий паттерн слился бы в пикселях: больше штрихов на одну ломаную не режем — сплошная линия
    const val MAX_DASHES_PER_PATH = 100_000
    // длина «точки» паттерна относительно его периода
    const val DOT_FRACTION = 0.02
    // подписей из текста в типах линий на весь чертёж — не больше, дальше линия идёт с пробелами без текста
    const val MAX_PATTERN_LABELS = 50_000
    // срез стыка широкой полилинии — не дальше стольких полуширин от вершины, иначе скос
    const val MITER_LIMIT = 4.0
    // боковые грани толщины: доля цвета сущности (темнее рёбер)
    const val EXTRUSION_SIDE_SHADE = 0.6
    // ребро толщины ставится в изломе круче 25° (cos 25°): шаги дуг (≤ 22,5°) изломами не считаются
    const val CORNER_COS = 0.9063077870366499
    // ломаная замкнута, если концы совпадают с такой относительной точностью
    const val CLOSE_TOLERANCE = 1e-9
    // скос MLINE почти вдоль сегмента: смещение по нему не растягиваем
    const val MIN_MITER_SINE = 0.05
    const val MAX_DEPTH = 16
    // полудлина XLINE / RAY, если габариты сцены неизвестны
    const val XLINE_FALLBACK_LENGTH = 1000.0
    // на столько отрезков режется XLINE / RAY
    const val XLINE_PIECES = 128
    // длина подписи объекта-текста в списке
    private const val MAX_OBJECT_NAME = 80
    const val SPLINE_SAMPLES_PER_POINT = 8
    const val SPLINE_MIN_SAMPLES = 16
    const val SPLINE_MAX_SAMPLES = 1024
  }
}
