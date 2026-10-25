package dev.iskandern.kotin.model

import dev.iskandern.kotin.dxf.DxfSavedView

/**
 * Геометрия с переставленными осями X и Y. Геодезические выгрузки пишут «X Y» как «север восток», а DXF ждёт
 * «восток север»: без перестановки план повёрнут на 90° и зеркален. Перестановка — отражение, поэтому у треугольников
 * меняется порядок обхода (вершины 2 и 3), а направление текста и значков становится 90° − угол.
 */
fun SceneGeometry.swappedXY(): SceneGeometry = SceneGeometry(
  meshes = meshes.map { it.swappedXY() },
  lines = lines.map {
    LineBatch(it.layer, it.color, it.flat, it.coords.swapXY(3), it.lineWeight, it.extrusion, it.unbounded, it.objectIds, it.dashed)
  },
  fills = fills.map { it.swappedXY() },
  labels = labels.map {
    SceneLabel(
      it.layer, it.color, it.y, it.x, it.z, it.height, 90.0 - it.rotationDeg, it.text, it.alignH, it.alignV,
      it.widthFactor, it.obliqueDeg, it.fitLength, it.frame, it.runs, it.background, it.font, it.objectId,
      it.screenHorizontal, it.liftDp, it.shiftDp, it.tickRgb,
    )
  },
  points = points.map { PointMark(it.layer, it.color, it.y, it.x, it.z, 90.0 - it.rotationDeg, it.objectId) },
  layers = layers,
  truncated = truncated,
  pointMode = pointMode,
  pointSize = pointSize,
  lineWeightDisplay = lineWeightDisplay,
  lineWeightsOffered = lineWeightsOffered,
  flattenOffered = flattenOffered,
  flattenAuto = flattenAuto,
  hiddenBodyFaces = hiddenBodyFaces,
  unreadBodies = unreadBodies,
  savedView = savedView?.let { DxfSavedView(it.centerY, it.centerX, it.height) },
  sheets = sheets,
  objects = objects,
  coordinateSystems = coordinateSystems,
)

/** Меняет местами первые две координаты каждой точки из [stride] чисел. */
private fun DoubleArray.swapXY(stride: Int): DoubleArray {
  val result = copyOf()
  var i = 0
  while (i + 1 < size) {
    result[i] = this[i + 1]
    result[i + 1] = this[i]
    i += stride
  }
  return result
}

private fun SceneMesh.swappedXY(): SceneMesh {
  val flipped = triangles.copyOf()
  var t = 0
  while (t + 2 < flipped.size) {
    flipped[t + 1] = triangles[t + 2]
    flipped[t + 2] = triangles[t + 1]
    t += 3
  }
  // рёбра после обмена b и c: a-b ↔ c-a, b-c остаётся
  val mask = edgeMask?.let { old ->
    ByteArray(old.size) { i ->
      val m = old[i].toInt()
      ((m and 2) or ((m and 4) shr 2) or ((m and 1) shl 2)).toByte()
    }
  }
  return SceneMesh(layer, coords.swapXY(3), flipped, minY, minX, minZ, maxY, maxX, maxZ, mask, triangleObjects, objectId, structure, outline)
}

private fun FillBatch.swappedXY(): FillBatch {
  val swapped = coords.swapXY(3)
  // вершины 2 и 3 каждого треугольника меняются местами вместе с цветами
  val result = swapped.copyOf()
  var i = 0
  while (i + 8 < swapped.size) {
    for (k in 0 until 3) {
      result[i + 3 + k] = swapped[i + 6 + k]
      result[i + 6 + k] = swapped[i + 3 + k]
    }
    i += 9
  }
  val flippedColors = colors?.let { old ->
    old.copyOf().also { c ->
      var v = 0
      while (v + 2 < old.size) {
        c[v + 1] = old[v + 2]
        c[v + 2] = old[v + 1]
        v += 3
      }
    }
  }
  return FillBatch(layer, color, flat, result, flippedColors, extrusion, orders, alpha, objectIds)
}
