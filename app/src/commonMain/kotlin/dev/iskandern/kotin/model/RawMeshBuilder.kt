package dev.iskandern.kotin.model

/**
 * Сетка без склейки вершин: номера вершин треугольников задаёт вызывающий (в отличие от [MeshBuilder]).
 * Вершины и треугольники — в растущих массивах, видимые рёбра — у каждого треугольника, объект треугольника — сериями.
 */
internal class RawMeshBuilder {
  private val coords = GrowingDoubleArray()
  private val triangles = GrowingIntArray()
  private var edges = ByteArray(64)
  private val runs = ObjectRuns()
  private var minX = Double.POSITIVE_INFINITY
  private var minY = Double.POSITIVE_INFINITY
  private var minZ = Double.POSITIVE_INFINITY
  private var maxX = Double.NEGATIVE_INFINITY
  private var maxY = Double.NEGATIVE_INFINITY
  private var maxZ = Double.NEGATIVE_INFINITY

  val vertexCount: Int get() = coords.size / 3
  val triangleCount: Int get() = triangles.size / 3

  /** Следующие треугольники принадлежат объекту [id]. */
  fun markObject(id: Int) = runs.mark(triangleCount, id)

  /** Новая вершина; её номер — [vertexCount] до вызова. */
  fun vertex(x: Double, y: Double, z: Double) {
    coords.add(x)
    coords.add(y)
    coords.add(z)
    if (x < minX) minX = x
    if (x > maxX) maxX = x
    if (y < minY) minY = y
    if (y > maxY) maxY = y
    if (z < minZ) minZ = z
    if (z > maxZ) maxZ = z
  }

  /** Треугольник из вершин [a], [b], [c] с видимыми рёбрами [mask] (биты как у [SceneMesh.edgeMask]). */
  fun triangle(a: Int, b: Int, c: Int, mask: Int) {
    val index = triangleCount
    if (index == edges.size) edges = edges.copyOf(index * 2)
    edges[index] = mask.toByte()
    triangles.add(a)
    triangles.add(b)
    triangles.add(c)
  }

  fun build(layer: String, structure: Boolean): SceneMesh {
    val count = triangleCount
    return SceneMesh(
      layer, coords.toArray(), triangles.toArray(), minX, minY, minZ, maxX, maxY, maxZ,
      edgeMask = edges.copyOf(count), triangleObjects = runs.expand(count), structure = structure,
    )
  }
}
