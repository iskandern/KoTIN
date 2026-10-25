package dev.iskandern.kotin.dxf

import dev.iskandern.kotin.model.GrowingIntArray

/** Сетка после подразделения: вершины (x, y, z подряд) и грани — индексы с 0. */
class SubdividedMesh(val xyz: DoubleArray, val faces: List<IntArray>)

/**
 * Сглаживание MESH по Catmull–Clark ([levels] шагов, не больше [MAX_SUBD_LEVELS]; шаг, после которого граней стало бы
 * больше [MAX_SUBD_FACES], не делается). Каждый шаг: n-угольник → n четырёхугольников.
 *
 * Складки: [edges] — пары индексов вершин, [creases] — значение складки ребра с тем же номером (−1 — всегда острое).
 * Ребро со складкой c острое на первых c шагах (у его половинок складка c − 1). Острое и граничное ребро — точка
 * ребра в середине; вершина с двумя острыми рёбрами — по правилу складки, с тремя и больше — остаётся на месте.
 * Грани с неверным индексом пропускаются, повтор соседнего угла убирается.
 */
fun subdivideMesh(
  xyz: DoubleArray, faces: List<IntArray>, edges: IntArray, creases: DoubleArray, levels: Int,
): SubdividedMesh {
  val count = xyz.size / 3
  var currentFaces = faces.mapNotNull { face -> cleanFace(face, count) }
  var currentXyz = xyz
  // складки по ключу ребра
  var sharp = HashMap<Long, Double>()
  for (i in 0 until minOf(edges.size / 2, creases.size)) {
    val a = edges[i * 2]
    val b = edges[i * 2 + 1]
    if (a !in 0 until count || b !in 0 until count || a == b || creases[i] == 0.0) continue
    sharp[edgeKey(a, b)] = creases[i]
  }
  repeat(levels.coerceAtMost(MAX_SUBD_LEVELS)) {
    if (currentFaces.sumOf { it.size.toLong() } > MAX_SUBD_FACES) return SubdividedMesh(currentXyz, currentFaces)
    val step = CatmullClarkStep(currentXyz, currentFaces, sharp)
    currentXyz = step.xyz
    currentFaces = step.faces
    sharp = step.sharp
  }
  return SubdividedMesh(currentXyz, currentFaces)
}

/** Наибольший уровень подразделения MESH. */
const val MAX_SUBD_LEVELS = 3

/** Наибольшее число граней после подразделения (каждая — 2 треугольника). */
const val MAX_SUBD_FACES = 1_000_000

private fun cleanFace(face: IntArray, count: Int): IntArray? {
  if (face.any { it !in 0 until count }) return null
  val out = IntArray(face.size)
  var n = 0
  for (v in face) if (n == 0 || out[n - 1] != v) out[n++] = v
  while (n > 1 && out[n - 1] == out[0]) n--
  return if (n >= 3) out.copyOf(n) else null
}

private fun edgeKey(a: Int, b: Int): Long =
  if (a < b) (a.toLong() shl 32) or b.toLong() else (b.toLong() shl 32) or a.toLong()

private fun isSharp(crease: Double?): Boolean = crease != null && (crease < 0 || crease > 0)

/** Один шаг Catmull–Clark. Новые вершины: старые, затем точки граней, затем точки рёбер. */
private class CatmullClarkStep(xyz: DoubleArray, faces: List<IntArray>, creases: Map<Long, Double>) {
  val xyz: DoubleArray
  val faces = ArrayList<IntArray>()
  val sharp = HashMap<Long, Double>()

  init {
    val count = xyz.size / 3
    // рёбра: ключ → номер, у каждого — до двух граней
    val edgeIndex = HashMap<Long, Int>()
    val edgeA = GrowingIntArray()
    val edgeB = GrowingIntArray()
    val edgeFaces = GrowingIntArray()
    val edgeFaceCount = GrowingIntArray()
    for ((f, face) in faces.withIndex()) {
      for (k in face.indices) {
        val a = face[k]
        val b = face[(k + 1) % face.size]
        val key = edgeKey(a, b)
        val e = edgeIndex.getOrPut(key) {
          edgeA.add(a); edgeB.add(b); edgeFaces.add(-1); edgeFaces.add(-1); edgeFaceCount.add(0)
          edgeA.size - 1
        }
        val used = edgeFaceCount[e]
        if (used < 2) edgeFaces[e * 2 + used] = f
        edgeFaceCount[e] = used + 1
      }
    }
    val edgeCount = edgeA.size
    val faceBase = count
    val edgeBase = count + faces.size
    val out = DoubleArray((edgeBase + edgeCount) * 3)

    // точки граней
    for ((f, face) in faces.withIndex()) {
      val o = (faceBase + f) * 3
      for (v in face) for (c in 0..2) out[o + c] += xyz[v * 3 + c]
      for (c in 0..2) out[o + c] /= face.size.toDouble()
    }

    // острое ребро: со складкой, граничное или общее больше чем для двух граней
    val edgeSharp = BooleanArray(edgeCount)
    for (e in 0 until edgeCount) {
      edgeSharp[e] = edgeFaceCount[e] != 2 || isSharp(creases[edgeKey(edgeA[e], edgeB[e])])
    }

    // точки рёбер
    for (e in 0 until edgeCount) {
      val o = (edgeBase + e) * 3
      val a = edgeA[e] * 3
      val b = edgeB[e] * 3
      if (edgeSharp[e]) {
        for (c in 0..2) out[o + c] = (xyz[a + c] + xyz[b + c]) / 2
      } else {
        val f1 = (faceBase + edgeFaces[e * 2]) * 3
        val f2 = (faceBase + edgeFaces[e * 2 + 1]) * 3
        for (c in 0..2) out[o + c] = (xyz[a + c] + xyz[b + c] + out[f1 + c] + out[f2 + c]) / 4
      }
    }

    // старые вершины: суммы по граням и рёбрам вокруг
    val faceSum = DoubleArray(count * 3)
    val faceN = IntArray(count)
    for ((f, face) in faces.withIndex()) {
      val o = (faceBase + f) * 3
      for (v in face) {
        for (c in 0..2) faceSum[v * 3 + c] += out[o + c]
        faceN[v]++
      }
    }
    val midSum = DoubleArray(count * 3)
    val edgeN = IntArray(count)
    val sharpN = IntArray(count)
    // сумма дальних концов острых рёбер — для правила складки
    val sharpSum = DoubleArray(count * 3)
    for (e in 0 until edgeCount) {
      val a = edgeA[e]
      val b = edgeB[e]
      for (c in 0..2) {
        val mid = (xyz[a * 3 + c] + xyz[b * 3 + c]) / 2
        midSum[a * 3 + c] += mid
        midSum[b * 3 + c] += mid
      }
      edgeN[a]++
      edgeN[b]++
      if (edgeSharp[e]) {
        sharpN[a]++
        sharpN[b]++
        for (c in 0..2) {
          sharpSum[a * 3 + c] += xyz[b * 3 + c]
          sharpSum[b * 3 + c] += xyz[a * 3 + c]
        }
      }
    }
    for (v in 0 until count) {
      val o = v * 3
      val n = edgeN[v]
      when {
        // вершина вне граней или угол из трёх и больше острых рёбер — на месте
        faceN[v] == 0 || sharpN[v] > 2 -> for (c in 0..2) out[o + c] = xyz[o + c]
        sharpN[v] == 2 -> for (c in 0..2) out[o + c] = (6 * xyz[o + c] + sharpSum[o + c]) / 8
        else -> for (c in 0..2) {
          val f = faceSum[o + c] / faceN[v]
          val r = midSum[o + c] / n
          out[o + c] = (f + 2 * r + (n - 3) * xyz[o + c]) / n
        }
      }
    }
    this.xyz = out

    // новые грани: угол, точка ребра после него, точка грани, точка ребра перед ним
    for ((f, face) in faces.withIndex()) {
      for (k in face.indices) {
        val v = face[k]
        val next = face[(k + 1) % face.size]
        val prev = face[(k - 1 + face.size) % face.size]
        val eNext = edgeBase + edgeIndex.getValue(edgeKey(v, next))
        val ePrev = edgeBase + edgeIndex.getValue(edgeKey(prev, v))
        this.faces.add(intArrayOf(v, eNext, faceBase + f, ePrev))
      }
    }

    // половинки ребра со складкой c получают c − 1 (−1 — остаётся всегда острым)
    for (e in 0 until edgeCount) {
      val crease = creases[edgeKey(edgeA[e], edgeB[e])] ?: continue
      val next = if (crease < 0) crease else crease - 1
      if (!isSharp(next) || next < 0 && crease >= 0) continue
      sharp[edgeKey(edgeA[e], edgeBase + e)] = next
      sharp[edgeKey(edgeB[e], edgeBase + e)] = next
    }
  }
}
