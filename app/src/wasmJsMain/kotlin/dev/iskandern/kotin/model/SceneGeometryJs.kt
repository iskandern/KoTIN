package dev.iskandern.kotin.model

import kotlinx.coroutines.yield
import dev.iskandern.kotin.dxf.DxfFont
import dev.iskandern.kotin.dxf.DxfSavedView
import dev.iskandern.kotin.dxf.DxfSheet
import dev.iskandern.kotin.dxf.SheetWindow
import dev.iskandern.kotin.dxf.DxfTextBackground
import dev.iskandern.kotin.dxf.DxfTextRun
import dev.iskandern.kotin.dxf.MTextFrame

// Передача SceneGeometry между Worker и главным потоком: числа — Float64Array/Int32Array (transferable),
// остальное — обычные объекты и массивы JS.

@JsFun("() => ({})")
private external fun newObject(): JsAny

@JsFun("() => []")
private external fun newArray(): JsAny

@JsFun("(obj, key, value) => { obj[key] = value; }")
private external fun setAny(obj: JsAny, key: String, value: JsAny?)

@JsFun("(obj, key, value) => { obj[key] = value; }")
private external fun setInt(obj: JsAny, key: String, value: Int)

@JsFun("(obj, key, value) => { obj[key] = value; }")
private external fun setDouble(obj: JsAny, key: String, value: Double)

@JsFun("(obj, key, value) => { obj[key] = value; }")
private external fun setString(obj: JsAny, key: String, value: String)

@JsFun("(obj, key, value) => { obj[key] = value; }")
private external fun setBoolean(obj: JsAny, key: String, value: Boolean)

@JsFun("(obj, key) => obj[key]")
private external fun getAny(obj: JsAny, key: String): JsAny

@JsFun("(obj, key) => obj[key]")
private external fun getInt(obj: JsAny, key: String): Int

@JsFun("(obj, key) => obj[key] != null")
private external fun has(obj: JsAny, key: String): Boolean

@JsFun("(obj, key) => obj[key]")
private external fun getDouble(obj: JsAny, key: String): Double

@JsFun("(obj, key) => obj[key]")
private external fun getString(obj: JsAny, key: String): String

@JsFun("(obj, key) => obj[key]")
private external fun getBoolean(obj: JsAny, key: String): Boolean

@JsFun("(array, value) => { array.push(value); }")
private external fun push(array: JsAny, value: JsAny)

@JsFun("(array, index) => array[index]")
private external fun at(array: JsAny, index: Int): JsAny

@JsFun("(array) => array.length")
private external fun length(array: JsAny): Int

@JsFun("(size) => new Float64Array(size)")
private external fun newFloat64Array(size: Int): JsAny

@JsFun("(size) => new Int32Array(size)")
private external fun newInt32Array(size: Int): JsAny

@JsFun("(array, index, value) => { array[index] = value; }")
private external fun setDoubleAt(array: JsAny, index: Int, value: Double)

@JsFun("(array, index, value) => { array[index] = value; }")
private external fun setIntAt(array: JsAny, index: Int, value: Int)

@JsFun("(array, index) => array[index]")
private external fun doubleAt(array: JsAny, index: Int): Double

@JsFun("(array, index) => array[index]")
private external fun intAt(array: JsAny, index: Int): Int

@JsFun("(transfer, array) => { transfer.push(array.buffer); }")
private external fun addTransfer(transfer: JsAny, array: JsAny)

private class Packer(val transfer: JsAny) {
  fun doubles(values: DoubleArray): JsAny {
    val result = newFloat64Array(values.size)
    for (i in values.indices) setDoubleAt(result, i, values[i])
    addTransfer(transfer, result)
    return result
  }

  fun ints(values: IntArray): JsAny {
    val result = newInt32Array(values.size)
    for (i in values.indices) setIntAt(result, i, values[i])
    addTransfer(transfer, result)
    return result
  }
}

/** Упаковывает [geometry] в JS-объект; буферы типизированных массивов добавляются в [transfer] (JS-массив). */
fun packGeometry(geometry: SceneGeometry, transfer: JsAny): JsAny {
  val packer = Packer(transfer)
  val result = newObject()

  val meshes = newArray()
  for (mesh in geometry.meshes) {
    val item = newObject()
    setString(item, "layer", mesh.layer)
    setAny(item, "coords", packer.doubles(mesh.coords))
    setAny(item, "triangles", packer.ints(mesh.triangles))
    setAny(item, "bounds", packer.doubles(doubleArrayOf(mesh.minX, mesh.minY, mesh.minZ, mesh.maxX, mesh.maxY, mesh.maxZ)))
    // видимые рёбра 3DFACE — только у сеток с четырёхугольниками или скрытыми рёбрами
    mesh.edgeMask?.let { mask -> setAny(item, "edgeMask", packer.ints(IntArray(mask.size) { mask[it].toInt() })) }
    mesh.triangleObjects?.let { setAny(item, "triangleObjects", packer.ints(it)) }
    setInt(item, "objectId", mesh.objectId)
    setInt(item, "structure", if (mesh.structure) 1 else 0)
    setBoolean(item, "outline", mesh.outline)
    push(meshes, item)
  }
  setAny(result, "meshes", meshes)

  val lines = newArray()
  for (batch in geometry.lines) {
    val item = newObject()
    setString(item, "layer", batch.layer)
    setInt(item, "color", batch.color)
    setBoolean(item, "flat", batch.flat)
    setInt(item, "lineWeight", batch.lineWeight)
    setInt(item, "extrusion", batch.extrusion)
    setBoolean(item, "unbounded", batch.unbounded)
    setBoolean(item, "dashed", batch.dashed)
    setAny(item, "coords", packer.doubles(batch.coords))
    batch.objectIds?.let { setAny(item, "objectIds", packer.ints(it)) }
    push(lines, item)
  }
  setAny(result, "lines", lines)

  val fills = newArray()
  for (batch in geometry.fills) {
    val item = newObject()
    setString(item, "layer", batch.layer)
    setInt(item, "color", batch.color)
    setBoolean(item, "flat", batch.flat)
    setInt(item, "extrusion", batch.extrusion)
    setInt(item, "alpha", batch.alpha)
    setAny(item, "coords", packer.doubles(batch.coords))
    // градиент — цвет на вершину
    batch.colors?.let { setAny(item, "colors", packer.ints(it)) }
    // порядок прорисовки — номер на треугольник
    batch.orders?.let { setAny(item, "orders", packer.ints(it)) }
    batch.objectIds?.let { setAny(item, "objectIds", packer.ints(it)) }
    push(fills, item)
  }
  setAny(result, "fills", fills)

  // подписи: числа в одном Float64Array по 18 штук (x y z height rotation color alignH alignV widthFactor oblique fitLength face widthScale objectId screenHorizontal liftDp tickRgb shiftDp), строки — рядом
  val labels = geometry.labels
  val labelNumbers = DoubleArray(labels.size * LABEL_FIELDS)
  val labelLayers = newArray()
  val labelTexts = newArray()
  // рамки MTEXT подряд: индекс подписи, width pitch attachment columnWidth gutter height, число высот колонок и они сами
  val labelFrames = ArrayList<Double>()
  // фрагменты MTEXT: индекс подписи, число фрагментов и по 4 числа на фрагмент (end color scale decoration);
  // плашки: индекс подписи, color margin width
  val labelRuns = ArrayList<Double>()
  val labelBackgrounds = ArrayList<Double>()
  for ((i, label) in labels.withIndex()) {
    val o = i * LABEL_FIELDS
    labelNumbers[o] = label.x; labelNumbers[o + 1] = label.y; labelNumbers[o + 2] = label.z
    labelNumbers[o + 3] = label.height; labelNumbers[o + 4] = label.rotationDeg
    labelNumbers[o + 5] = label.color.toDouble(); labelNumbers[o + 6] = label.alignH.toDouble(); labelNumbers[o + 7] = label.alignV.toDouble()
    labelNumbers[o + 8] = label.widthFactor; labelNumbers[o + 9] = label.obliqueDeg; labelNumbers[o + 10] = label.fitLength
    labelNumbers[o + 11] = label.font.face.toDouble(); labelNumbers[o + 12] = label.font.widthScale
    labelNumbers[o + 13] = label.objectId.toDouble()
    labelNumbers[o + 14] = if (label.screenHorizontal) 1.0 else 0.0
    labelNumbers[o + 15] = label.liftDp; labelNumbers[o + 16] = label.tickRgb.toDouble(); labelNumbers[o + 17] = label.shiftDp
    push(labelLayers, stringAsAny(label.layer))
    push(labelTexts, stringAsAny(label.text))
    label.frame?.let { f ->
      labelFrames.addAll(listOf(i.toDouble(), f.width, f.pitch, f.attachment.toDouble(), f.columnWidth, f.gutter, f.height))
      labelFrames.add(f.columnHeights.size.toDouble())
      f.columnHeights.forEach { labelFrames.add(it) }
    }
    label.runs?.let { runs ->
      labelRuns.add(i.toDouble())
      labelRuns.add(runs.size.toDouble())
      for (run in runs) labelRuns.addAll(listOf(run.end.toDouble(), run.color.toDouble(), run.scale, run.decoration.toDouble()))
    }
    label.background?.let { labelBackgrounds.addAll(listOf(i.toDouble(), it.color.toDouble(), it.margin, it.width)) }
  }
  setAny(result, "labelNumbers", packer.doubles(labelNumbers))
  setAny(result, "labelFrames", packer.doubles(labelFrames.toDoubleArray()))
  setAny(result, "labelRuns", packer.doubles(labelRuns.toDoubleArray()))
  setAny(result, "labelBackgrounds", packer.doubles(labelBackgrounds.toDoubleArray()))
  setAny(result, "labelLayers", labelLayers)
  setAny(result, "labelTexts", labelTexts)

  val points = geometry.points
  val pointNumbers = DoubleArray(points.size * POINT_FIELDS)
  val pointLayers = newArray()
  for ((i, point) in points.withIndex()) {
    val o = i * POINT_FIELDS
    pointNumbers[o] = point.x; pointNumbers[o + 1] = point.y; pointNumbers[o + 2] = point.z; pointNumbers[o + 3] = point.color.toDouble()
    pointNumbers[o + 4] = point.rotationDeg
    pointNumbers[o + 5] = point.objectId.toDouble()
    push(pointLayers, stringAsAny(point.layer))
  }
  setAny(result, "pointNumbers", packer.doubles(pointNumbers))
  setAny(result, "pointLayers", pointLayers)

  // слои: имя, имя типа линии и группа — строки, остальное — числа (color flags trueColor(-1 нет) hasTrueColor lineWeight)
  val layers = geometry.layers
  val layerNumbers = IntArray(layers.size * LAYER_FIELDS)
  val layerNames = newArray()
  val layerLineTypes = newArray()
  val layerGroups = newArray()
  for ((i, layer) in layers.withIndex()) {
    val o = i * LAYER_FIELDS
    layerNumbers[o] = layer.color; layerNumbers[o + 1] = layer.flags
    layerNumbers[o + 2] = layer.trueColor ?: 0; layerNumbers[o + 3] = if (layer.trueColor != null) 1 else 0
    layerNumbers[o + 4] = layer.lineWeight
    push(layerNames, stringAsAny(layer.name))
    push(layerLineTypes, stringAsAny(layer.lineType))
    push(layerGroups, stringAsAny(layer.group))
  }
  setAny(result, "layerNumbers", packer.ints(layerNumbers))
  setAny(result, "layerNames", layerNames)
  setAny(result, "layerLineTypes", layerLineTypes)
  setAny(result, "layerGroups", layerGroups)

  // объекты: вид, имя, слой — строки; цвет и число свойств — числа; свойства подряд (название, значение)
  val objects = geometry.objects
  val objectNumbers = IntArray(objects.size * 2)
  val objectKinds = newArray()
  val objectNames = newArray()
  val objectLayers = newArray()
  val attributeStrings = newArray()
  for ((i, obj) in objects.withIndex()) {
    objectNumbers[i * 2] = obj.color
    objectNumbers[i * 2 + 1] = obj.attributes.size
    push(objectKinds, stringAsAny(obj.kind))
    push(objectNames, stringAsAny(obj.name))
    push(objectLayers, stringAsAny(obj.layer))
    for (attribute in obj.attributes) {
      push(attributeStrings, stringAsAny(attribute.name))
      push(attributeStrings, stringAsAny(attribute.value))
    }
  }
  setAny(result, "objectNumbers", packer.ints(objectNumbers))
  setAny(result, "objectKinds", objectKinds)
  setAny(result, "objectNames", objectNames)
  setAny(result, "objectLayers", objectLayers)
  setAny(result, "attributeStrings", attributeStrings)
  // длина в плане (у кривых DXF), NaN — нет
  setAny(result, "objectLengths", packer.doubles(DoubleArray(objects.size) { objects[it].planLength }))

  setBoolean(result, "truncated", geometry.truncated)
  setInt(result, "pointMode", geometry.pointMode)
  setBoolean(result, "lineWeightDisplay", geometry.lineWeightDisplay)
  setBoolean(result, "flattenOffered", geometry.flattenOffered)
  setBoolean(result, "flattenAuto", geometry.flattenAuto)
  setDouble(result, "pointSize", geometry.pointSize)
  setInt(result, "hiddenBodyFaces", geometry.hiddenBodyFaces)
  setInt(result, "unreadBodies", geometry.unreadBodies)
  geometry.savedView?.let { view ->
    setDouble(result, "viewX", view.centerX)
    setDouble(result, "viewY", view.centerY)
    setDouble(result, "viewHeight", view.height)
  }
  // листы — та же упаковка, вложенная
  val sheets = newArray()
  for (sheet in geometry.sheets) {
    val item = newObject()
    setString(item, "name", sheet.name)
    setAny(item, "geometry", packGeometry(sheet.geometry, transfer))
    // окна с моделью: числа подряд и замороженные слои каждого
    val windows = newArray()
    for (window in sheet.windows) {
      val numbers = newArray()
      for (value in doubleArrayOf(
        window.centerX, window.centerY, window.width, window.height, window.viewCenterX, window.viewCenterY,
        window.targetX, window.targetY, window.viewHeight, window.twistDeg,
      )) push(numbers, value.toJsNumber())
      val frozen = newArray()
      for (layer in window.frozenLayers) push(frozen, stringAsAny(layer))
      val packed = newObject()
      setAny(packed, "numbers", numbers)
      setAny(packed, "frozen", frozen)
      push(windows, packed)
    }
    setAny(item, "windows", windows)
    push(sheets, item)
  }
  setAny(result, "sheets", sheets)
  // системы координат: по массиву строк «имя, значение, …» на элемент; единицы файла — одни на все
  if (geometry.coordinateSystems.isNotEmpty()) {
    val systems = newArray()
    for (system in geometry.coordinateSystems) {
      val strings = newArray()
      for ((name, value) in system.attributes) {
        push(strings, stringAsAny(name))
        push(strings, stringAsAny(value))
      }
      push(systems, strings)
    }
    setAny(result, "coordinateSystems", systems)
    geometry.coordinateSystems.first().fileUnits?.let { setString(result, "fileUnits", it) }
  }
  return result
}

@JsFun("(s) => s")
private external fun stringAsAny(s: String): JsAny

@JsFun("(s) => s")
private external fun anyAsString(s: JsAny): String

private const val LABEL_FIELDS = 18
private const val POINT_FIELDS = 6
private const val LAYER_FIELDS = 5

// копирование массивов JS в Kotlin порциями с уступкой потока, чтобы интерфейс не замирал
private const val CHUNK = 1 shl 16

private suspend fun doublesFrom(array: JsAny): DoubleArray {
  val size = length(array)
  val result = DoubleArray(size)
  var i = 0
  while (i < size) {
    val end = minOf(size, i + CHUNK)
    while (i < end) { result[i] = doubleAt(array, i); i++ }
    yield()
  }
  return result
}

private suspend fun intsFrom(array: JsAny): IntArray {
  val size = length(array)
  val result = IntArray(size)
  var i = 0
  while (i < size) {
    val end = minOf(size, i + CHUNK)
    while (i < end) { result[i] = intAt(array, i); i++ }
    yield()
  }
  return result
}

/** Обратное к [packGeometry]; копирует порциями с [yield], вызывать из корутины главного потока. */
suspend fun unpackGeometry(packed: JsAny): SceneGeometry {
  val meshes = ArrayList<SceneMesh>()
  val meshItems = getAny(packed, "meshes")
  for (i in 0 until length(meshItems)) {
    val item = at(meshItems, i)
    val b = doublesFrom(getAny(item, "bounds"))
    val triangleObjects = if (has(item, "triangleObjects")) intsFrom(getAny(item, "triangleObjects")) else null
    val edgeMask = if (has(item, "edgeMask")) intsFrom(getAny(item, "edgeMask")).let { m -> ByteArray(m.size) { m[it].toByte() } } else null
    meshes.add(SceneMesh(getString(item, "layer"), doublesFrom(getAny(item, "coords")), intsFrom(getAny(item, "triangles")), b[0], b[1], b[2], b[3], b[4], b[5], edgeMask, triangleObjects, getInt(item, "objectId"), getInt(item, "structure") != 0,
      getBoolean(item, "outline")))
  }

  val lines = ArrayList<LineBatch>()
  val lineItems = getAny(packed, "lines")
  for (i in 0 until length(lineItems)) {
    val item = at(lineItems, i)
    lines.add(
      LineBatch(
        getString(item, "layer"), getInt(item, "color"), getBoolean(item, "flat"), doublesFrom(getAny(item, "coords")),
        getInt(item, "lineWeight"), getInt(item, "extrusion"), getBoolean(item, "unbounded"),
        if (has(item, "objectIds")) intsFrom(getAny(item, "objectIds")) else null,
        dashed = getBoolean(item, "dashed"),
      )
    )
  }

  val fills = ArrayList<FillBatch>()
  val fillItems = getAny(packed, "fills")
  for (i in 0 until length(fillItems)) {
    val item = at(fillItems, i)
    val colors = if (has(item, "colors")) intsFrom(getAny(item, "colors")) else null
    val orders = if (has(item, "orders")) intsFrom(getAny(item, "orders")) else null
    fills.add(
      FillBatch(
        getString(item, "layer"), getInt(item, "color"), getBoolean(item, "flat"), doublesFrom(getAny(item, "coords")),
        colors, getInt(item, "extrusion"), orders, getInt(item, "alpha"),
        if (has(item, "objectIds")) intsFrom(getAny(item, "objectIds")) else null,
      ),
    )
  }

  val labelNumbers = doublesFrom(getAny(packed, "labelNumbers"))
  val labelLayers = getAny(packed, "labelLayers")
  val labelTexts = getAny(packed, "labelTexts")
  val frameNumbers = doublesFrom(getAny(packed, "labelFrames"))
  val frames = HashMap<Int, MTextFrame>()
  var f = 0
  while (f < frameNumbers.size) {
    val count = frameNumbers[f + 7].toInt()
    frames[frameNumbers[f].toInt()] = MTextFrame(
      frameNumbers[f + 1], frameNumbers[f + 2], frameNumbers[f + 3].toInt(), frameNumbers[f + 4], frameNumbers[f + 5], frameNumbers[f + 6],
      frameNumbers.copyOfRange(f + 8, f + 8 + count),
    )
    f += 8 + count
  }
  val runNumbers = doublesFrom(getAny(packed, "labelRuns"))
  val runs = HashMap<Int, List<DxfTextRun>>()
  var r = 0
  while (r < runNumbers.size) {
    val count = runNumbers[r + 1].toInt()
    val o = r + 2
    runs[runNumbers[r].toInt()] = List(count) {
      val k = o + it * 4
      DxfTextRun(runNumbers[k].toInt(), runNumbers[k + 1].toInt(), runNumbers[k + 2], runNumbers[k + 3].toInt())
    }
    r = o + count * 4
  }
  val backgroundNumbers = doublesFrom(getAny(packed, "labelBackgrounds"))
  val backgrounds = HashMap<Int, DxfTextBackground>()
  for (b in backgroundNumbers.indices step 4) {
    backgrounds[backgroundNumbers[b].toInt()] =
      DxfTextBackground(backgroundNumbers[b + 1].toInt(), backgroundNumbers[b + 2], backgroundNumbers[b + 3])
  }
  val labels = ArrayList<SceneLabel>(length(labelLayers))
  for (i in 0 until length(labelLayers)) {
    val o = i * LABEL_FIELDS
    labels.add(
      SceneLabel(
        anyAsString(at(labelLayers, i)), labelNumbers[o + 5].toInt(),
        labelNumbers[o], labelNumbers[o + 1], labelNumbers[o + 2], labelNumbers[o + 3], labelNumbers[o + 4],
        anyAsString(at(labelTexts, i)), labelNumbers[o + 6].toInt(), labelNumbers[o + 7].toInt(),
        labelNumbers[o + 8], labelNumbers[o + 9], labelNumbers[o + 10], frames[i],
        runs[i], backgrounds[i], DxfFont(labelNumbers[o + 11].toInt(), labelNumbers[o + 12]),
        labelNumbers[o + 13].toInt(), labelNumbers[o + 14] != 0.0, labelNumbers[o + 15], labelNumbers[o + 17],
        labelNumbers[o + 16].toInt(),
      ),
    )
    if (i % 4096 == 4095) yield()
  }

  val pointNumbers = doublesFrom(getAny(packed, "pointNumbers"))
  val pointLayers = getAny(packed, "pointLayers")
  val points = ArrayList<PointMark>(length(pointLayers))
  for (i in 0 until length(pointLayers)) {
    val o = i * POINT_FIELDS
    points.add(
      PointMark(
        anyAsString(at(pointLayers, i)), pointNumbers[o + 3].toInt(), pointNumbers[o], pointNumbers[o + 1], pointNumbers[o + 2],
        pointNumbers[o + 4], pointNumbers[o + 5].toInt(),
      )
    )
    if (i % 4096 == 4095) yield()
  }

  val layerNumbers = intsFrom(getAny(packed, "layerNumbers"))
  val layerNames = getAny(packed, "layerNames")
  val layerLineTypes = getAny(packed, "layerLineTypes")
  val layerGroups = getAny(packed, "layerGroups")
  val layers = ArrayList<LayerInfo>(length(layerNames))
  for (i in 0 until length(layerNames)) {
    val o = i * LAYER_FIELDS
    layers.add(
      LayerInfo(
        anyAsString(at(layerNames, i)), layerNumbers[o], layerNumbers[o + 1],
        if (layerNumbers[o + 3] != 0) layerNumbers[o + 2] else null,
        anyAsString(at(layerLineTypes, i)), layerNumbers[o + 4], group = anyAsString(at(layerGroups, i)),
      ),
    )
  }

  val objectNumbers = intsFrom(getAny(packed, "objectNumbers"))
  val objectKinds = getAny(packed, "objectKinds")
  val objectNames = getAny(packed, "objectNames")
  val objectLayers = getAny(packed, "objectLayers")
  val attributeStrings = getAny(packed, "attributeStrings")
  val objectLengths = doublesFrom(getAny(packed, "objectLengths"))
  val objects = ArrayList<SceneObject>(length(objectKinds))
  var attributeIndex = 0
  for (i in 0 until length(objectKinds)) {
    val attributes = List(objectNumbers[i * 2 + 1]) {
      SceneAttribute(anyAsString(at(attributeStrings, attributeIndex++)), anyAsString(at(attributeStrings, attributeIndex++)))
    }
    objects.add(
      SceneObject(
        anyAsString(at(objectKinds, i)), anyAsString(at(objectNames, i)), anyAsString(at(objectLayers, i)),
        objectNumbers[i * 2], attributes,
      ).also { it.planLength = objectLengths[i] }
    )
    if (i % 4096 == 4095) yield()
  }

  val sheets = ArrayList<DxfSheet>()
  if (has(packed, "sheets")) {
    val sheetItems = getAny(packed, "sheets")
    for (i in 0 until length(sheetItems)) {
      val item = at(sheetItems, i)
      val windows = ArrayList<SheetWindow>()
      if (has(item, "windows")) {
        val packedWindows = getAny(item, "windows")
        for (w in 0 until length(packedWindows)) {
          val window = at(packedWindows, w)
          val numbers = getAny(window, "numbers")
          val n = DoubleArray(length(numbers)) { doubleAt(numbers, it) }
          val frozen = getAny(window, "frozen")
          val layers = (0 until length(frozen)).mapTo(HashSet()) { anyAsString(at(frozen, it)) }
          windows.add(SheetWindow(n[0], n[1], n[2], n[3], n[4], n[5], n[6], n[7], n[8], n[9], layers))
        }
      }
      sheets.add(DxfSheet(getString(item, "name"), unpackGeometry(getAny(item, "geometry")), windows))
    }
  }

  val coordinateSystems = ArrayList<CoordinateSystemInfo>()
  if (has(packed, "coordinateSystems")) {
    val systems = getAny(packed, "coordinateSystems")
    val fileUnits = if (has(packed, "fileUnits")) getString(packed, "fileUnits") else null
    for (i in 0 until length(systems)) {
      val strings = at(systems, i)
      val attributes = List(length(strings) / 2) { anyAsString(at(strings, 2 * it)) to anyAsString(at(strings, 2 * it + 1)) }
      coordinateSystems.add(CoordinateSystemInfo(attributes, fileUnits))
    }
  }

  return SceneGeometry(
    meshes, lines, fills, labels, points, layers,
    coordinateSystems = coordinateSystems,
    sheets = sheets,
    objects = objects,
    truncated = getBoolean(packed, "truncated"),
    pointMode = getInt(packed, "pointMode"),
    lineWeightDisplay = getBoolean(packed, "lineWeightDisplay"),
    flattenOffered = getBoolean(packed, "flattenOffered"),
    flattenAuto = getBoolean(packed, "flattenAuto"),
    pointSize = getDouble(packed, "pointSize"),
    hiddenBodyFaces = getInt(packed, "hiddenBodyFaces"),
    unreadBodies = getInt(packed, "unreadBodies"),
    savedView = if (has(packed, "viewHeight")) {
      DxfSavedView(getDouble(packed, "viewX"), getDouble(packed, "viewY"), getDouble(packed, "viewHeight"))
    } else {
      null
    },
  )
}
