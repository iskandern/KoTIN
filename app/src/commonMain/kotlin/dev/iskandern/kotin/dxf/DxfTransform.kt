package dev.iskandern.kotin.dxf

import dev.iskandern.kotin.model.toRadians
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Аффинное преобразование 3D: матрица 3×4 по строкам, p' = M·p + t.
 * Нужна для вхождений блоков (система блока → мировая) и для систем координат объектов (OCS → мировая).
 */
class DxfTransform private constructor(private val m: DoubleArray) {

  val isIdentity: Boolean get() = m.contentEquals(IDENTITY.m)

  fun x(x: Double, y: Double, z: Double) = m[0] * x + m[1] * y + m[2] * z + m[3]
  fun y(x: Double, y: Double, z: Double) = m[4] * x + m[5] * y + m[6] * z + m[7]
  fun z(x: Double, y: Double, z: Double) = m[8] * x + m[9] * y + m[10] * z + m[11]

  /** Направление (без переноса). */
  fun dx(x: Double, y: Double, z: Double) = m[0] * x + m[1] * y + m[2] * z
  fun dy(x: Double, y: Double, z: Double) = m[4] * x + m[5] * y + m[6] * z
  fun dz(x: Double, y: Double, z: Double) = m[8] * x + m[9] * y + m[10] * z

  /** Сначала [inner], потом this. */
  operator fun times(inner: DxfTransform): DxfTransform {
    val a = m
    val b = inner.m
    val r = DoubleArray(12)
    for (row in 0..2) {
      for (col in 0..3) {
        var value = a[row * 4] * b[col] + a[row * 4 + 1] * b[4 + col] + a[row * 4 + 2] * b[8 + col]
        if (col == 3) value += a[row * 4 + 3]
        r[row * 4 + col] = value
      }
    }
    return DxfTransform(r)
  }

  companion object {
    val IDENTITY = DxfTransform(doubleArrayOf(1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0))

    /** Из 12 чисел по строкам: матрица 3×4. */
    fun of(rows: DoubleArray) = DxfTransform(rows.copyOf(12))

    fun translation(x: Double, y: Double, z: Double) =
      DxfTransform(doubleArrayOf(1.0, 0.0, 0.0, x, 0.0, 1.0, 0.0, y, 0.0, 0.0, 1.0, z))

    fun scale(x: Double, y: Double, z: Double) =
      DxfTransform(doubleArrayOf(x, 0.0, 0.0, 0.0, 0.0, y, 0.0, 0.0, 0.0, 0.0, z, 0.0))

    /** Поворот вокруг Z против часовой стрелки, в градусах. */
    fun rotationZ(degrees: Double): DxfTransform {
      val radians = toRadians(degrees)
      val c = cos(radians)
      val s = sin(radians)
      return DxfTransform(doubleArrayOf(c, -s, 0.0, 0.0, s, c, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0))
    }

    /**
     * Система координат объекта (OCS) → мировая по нормали, «произвольная ось» из спецификации DXF.
     * Для нормали (0, 0, 1) — тождественное преобразование.
     */
    fun fromExtrusion(normalX: Double, normalY: Double, normalZ: Double): DxfTransform {
      val length = sqrt(normalX * normalX + normalY * normalY + normalZ * normalZ)
      if (length == 0.0) return IDENTITY
      val nx = normalX / length
      val ny = normalY / length
      val nz = normalZ / length
      if (abs(nx) < EPS && abs(ny) < EPS && nz > 0) return IDENTITY

      // Ax = Wy × N, если нормаль почти вдоль Z, иначе Wz × N
      var ax: Double
      var ay: Double
      var az: Double
      if (abs(nx) < ARBITRARY_AXIS_LIMIT && abs(ny) < ARBITRARY_AXIS_LIMIT) {
        ax = nz; ay = 0.0; az = -nx
      } else {
        ax = -ny; ay = nx; az = 0.0
      }
      val aLength = sqrt(ax * ax + ay * ay + az * az)
      ax /= aLength; ay /= aLength; az /= aLength

      // Ay = N × Ax
      val bx = ny * az - nz * ay
      val by = nz * ax - nx * az
      val bz = nx * ay - ny * ax

      // столбцы матрицы — оси OCS в мировых координатах
      return DxfTransform(doubleArrayOf(ax, bx, nx, 0.0, ay, by, ny, 0.0, az, bz, nz, 0.0))
    }

    private const val ARBITRARY_AXIS_LIMIT = 1.0 / 64
    private const val EPS = 1e-12
  }
}
