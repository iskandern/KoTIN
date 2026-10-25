package androidx.compose.animation.core

// Кривые замедления Compose Animation (та же формула), без зависимости от Compose UI — нужны камере (CameraController)

fun interface Easing {
  fun transform(fraction: Float): Float
}

class CubicBezierEasing(private val a: Float, private val b: Float, private val c: Float, private val d: Float) : Easing {
  private fun evaluateCubic(a: Float, b: Float, m: Float): Float = 3 * a * (1 - m) * (1 - m) * m + 3 * b * (1 - m) * m * m + m * m * m

  override fun transform(fraction: Float): Float {
    if (fraction <= 0f || fraction >= 1f) return fraction
    var start = 0f
    var end = 1f
    while (true) {
      val midpoint = (start + end) / 2
      val estimate = evaluateCubic(a, c, midpoint)
      if (kotlin.math.abs(fraction - estimate) < 0.001f) return evaluateCubic(b, d, midpoint)
      if (estimate < fraction) start = midpoint else end = midpoint
    }
  }
}

val FastOutSlowInEasing: Easing = CubicBezierEasing(0.4f, 0.0f, 0.2f, 1.0f)
val LinearOutSlowInEasing: Easing = CubicBezierEasing(0.0f, 0.0f, 0.2f, 1.0f)
val FastOutLinearInEasing: Easing = CubicBezierEasing(0.4f, 0.0f, 1.0f, 1.0f)
