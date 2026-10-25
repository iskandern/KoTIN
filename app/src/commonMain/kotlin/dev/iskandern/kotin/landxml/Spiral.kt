package dev.iskandern.kotin.landxml

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Переходная кривая `Spiral` (KoTIN-64): кривизна на длине `L` меняется от `κ0` до `κ1` по профилю `f(s/L)`,
 * направление — интеграл кривизны (в замкнутом виде), точка — интеграл направления (Симпсон).
 *
 * Профили (`f`, от 0 до 1): клотоида — `x`; Блосс — `3x² − 2x³`; синусоида — `x − sin(2πx)/2π`;
 * косинусоида — `(1 − cos πx)/2`; биквадратная (Шрамм) — `2x²` при `x ≤ ½`, иначе `1 − 2(1 − x)²`.
 * Все четыре нелинейных профиля центрально-симметричны (`f(x) + f(1 − x) = 1`), поэтому `rev*` («от конца»:
 * `1 − f(1 − x)`) совпадают с прямыми. Остальные типы считаются клотоидой ([isFallback]).
 */
internal object Spiral {
  /** Значения `spiType` по порядку номеров типа. */
  val TYPE_NAMES = arrayOf(
    "clothoid", "bloss", "sinusoid", "cosine", "biquadratic",
    "revBloss", "revSinusoid", "revCosine", "revBiquadratic",
    "biquadraticParabola", "cubic", "cubicParabola", "japaneseCubic", "radioid", "weinerBogen", "sineHalfWave",
  )
  val TYPE_BYTES = Array(TYPE_NAMES.size) { TYPE_NAMES[it].encodeToByteArray() }

  /** Тип не указан или неизвестен — клотоида. */
  const val UNKNOWN = -1

  private const val CLOTHOID = 0
  private const val BLOSS = 1
  private const val SINUSOID = 2
  private const val COSINE = 3
  private const val BIQUADRATIC = 4
  private const val FIRST_FALLBACK = 9

  /** Тип без своих формул: рисуется клотоидой с предупреждением. */
  fun isFallback(type: Int): Boolean = type < 0 || type >= FIRST_FALLBACK

  // профиль по номеру типа: rev* — как прямые, запасные — клотоида
  private fun profile(type: Int): Int = when (type) {
    1, 5 -> BLOSS
    2, 6 -> SINUSOID
    3, 7 -> COSINE
    4, 8 -> BIQUADRATIC
    else -> CLOTHOID
  }

  /** Интеграл профиля `F(x) = ∫₀ˣ f`, `F(1) = ½` у всех профилей. */
  fun profileIntegral(type: Int, x: Double): Double = when (profile(type)) {
    BLOSS -> x * x * x * (1 - x / 2)
    SINUSOID -> x * x / 2 + (cos(2 * PI * x) - 1) / (4 * PI * PI)
    COSINE -> x / 2 - sin(PI * x) / (2 * PI)
    BIQUADRATIC -> if (x <= 0.5) 2 * x * x * x / 3 else {
      val r = 1 - x
      x - 0.5 + 2 * r * r * r / 3
    }
    else -> x * x / 2
  }

  /** Поворот касательной от начала до [s]: `κ0·s + (κ1 − κ0)·L·F(s/L)`. */
  fun turn(type: Int, k0: Double, k1: Double, length: Double, s: Double): Double =
    k0 * s + (k1 - k0) * length * profileIntegral(type, s / length)

  /**
   * Шагов Симпсона на всю кривую (чётное): поворот за шаг не больше [STEP_TURN], не меньше [MIN_STEPS] —
   * погрешность конца не больше ~1e-8 длины (оценка `(Lκ)²/(60n⁴)`).
   */
  fun steps(k0: Double, k1: Double, length: Double): Int {
    val k = max(abs(k0), abs(k1))
    val n = ceil(length * k / STEP_TURN).toInt().coerceIn(MIN_STEPS, MAX_STEPS)
    return n + (n and 1)
  }

  /**
   * Смещение от [s0] до [s1] (Симпсон, [steps] — чётное) при начальном направлении [theta0] — прибавляется
   * к [out] (x, y). Без аллокаций.
   */
  fun integrate(
    type: Int, k0: Double, k1: Double, length: Double, theta0: Double,
    s0: Double, s1: Double, steps: Int, out: DoubleArray,
  ) {
    if (s1 == s0) return
    val h = (s1 - s0) / steps
    val a0 = theta0 + turn(type, k0, k1, length, s0)
    var sx = cos(a0)
    var sy = sin(a0)
    for (k in 1..steps) {
      val a = theta0 + turn(type, k0, k1, length, s0 + h * k)
      val w = if (k == steps) 1.0 else if (k and 1 == 1) 4.0 else 2.0
      sx += w * cos(a)
      sy += w * sin(a)
    }
    out[0] += sx * h / 3
    out[1] += sy * h / 3
  }

  private const val STEP_TURN = 0.02
  private const val MIN_STEPS = 32
  private const val MAX_STEPS = 1 shl 14
}
