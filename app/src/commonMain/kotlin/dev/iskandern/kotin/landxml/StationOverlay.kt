package dev.iskandern.kotin.landxml

import dev.iskandern.kotin.model.NO_OBJECT
import dev.iskandern.kotin.model.PointMark
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Значения «на отрезке пикетов» (скорость, интенсивность, …) и «в точке пикета» (знак, угол поворота) на карте:
 * отрезок трассы повторяется параллельной полосой со смещением [LANE_SHIFT] × номер полосы (чтобы не слиться с самой
 * трассой и друг с другом), в своём слое и цвете. Положение — пикет → расстояние ([Stationing]) → точка трассы.
 * С профилем полоса идёт по его отметкам, без профиля — плоская (накопитель сажает её на поверхность).
 */
internal object StationOverlay {
  /** Смещение одной полосы от трассы, доля размера сцены. */
  const val LANE_SHIFT = 0.0025
  private const val STEP_DIVISOR = 1000.0
  private const val MAX_STEPS = 4000

  /** Расстояние от начала трассы до пикета; за концами — ближний конец. */
  fun distance(geometry: AlignmentGeometry, station: Double): Double {
    val d = geometry.stationing.distance(station)
    if (!d.isNaN()) return d
    return if (station <= geometry.stationing.station(0.0)) 0.0 else geometry.length
  }

  /** Пикет внутри трассы (с допуском). */
  fun contains(geometry: AlignmentGeometry, station: Double): Boolean = !geometry.stationing.distance(station).isNaN()

  /** Точка на пикете [station] со смещением [lane] полос влево (минус — вправо); null — у трассы нет геометрии. */
  fun point(
    geometry: AlignmentGeometry, profile: AlignmentProfile?, station: Double, sceneSize: Double, lane: Double = 0.0,
    out: DoubleArray,
  ): Boolean = pointAt(geometry, profile, distance(geometry, station), sceneSize * LANE_SHIFT * lane, out)

  /** [out]: x, y, z; z NaN — отметки нет (точка плоская). [shift] — метры влево от трассы. */
  fun pointAt(
    geometry: AlignmentGeometry, profile: AlignmentProfile?, distance: Double, shift: Double, out: DoubleArray,
  ): Boolean {
    val p = geometry.atDistance(distance) ?: return false
    out[0] = p.x - sin(p.direction) * shift
    out[1] = p.y + cos(p.direction) * shift
    out[2] = profile?.elevation(distance) ?: Double.NaN
    return true
  }

  /**
   * Полоса по трассе от пикета [from] до [to] в слое [layer] цветом [color], смещённая на [lane] полос.
   * Возвращает число отрезков.
   */
  fun drawSpan(
    scene: LandXmlSceneBuilder, geometry: AlignmentGeometry, profile: AlignmentProfile?, layer: String, color: Int,
    from: Double, to: Double, lane: Double, sceneSize: Double, objectId: Int = NO_OBJECT,
  ): Int {
    val d0 = distance(geometry, from)
    val d1 = distance(geometry, to)
    val lo = minOf(d0, d1)
    val hi = max(d0, d1)
    if (hi - lo <= 0.0) return 0
    val step = if (sceneSize > 0.0) sceneSize / STEP_DIVISOR else hi - lo
    val n = ceil((hi - lo) / step).toInt().coerceIn(1, MAX_STEPS)
    val flat = scene.lines(layer, color, flat = true)
    val elevated = scene.lines(layer, color, flat = false)
    val shift = sceneSize * LANE_SHIFT * lane
    val a = DoubleArray(3)
    val b = DoubleArray(3)
    var count = 0
    if (!pointAt(geometry, profile, lo, shift, a)) return 0
    for (i in 1..n) {
      if (!pointAt(geometry, profile, lo + (hi - lo) * i / n, shift, b)) break
      if (!a[2].isNaN() && !b[2].isNaN()) {
        elevated.add(a[0], a[1], a[2], b[0], b[1], b[2], objectId)
      } else {
        flat.add(a[0], a[1], 0.0, b[0], b[1], 0.0, objectId)
      }
      count++
      b.copyInto(a)
    }
    return count
  }

  /** Подпись [text] у пикета [station] на полосе [lane]. */
  fun label(
    scene: LandXmlSceneBuilder, geometry: AlignmentGeometry, profile: AlignmentProfile?, layer: String, station: Double,
    lane: Double, text: String, sceneSize: Double, objectId: Int = NO_OBJECT, along: Double = 0.0,
  ) {
    val p = DoubleArray(3)
    // подписи слоёв у одного пикета разнесены вдоль трассы: [along] метров от пикета, но не за конец трассы
    val distance = (distance(geometry, station) + along).coerceIn(0.0, geometry.length)
    if (!pointAt(geometry, profile, distance, sceneSize * LANE_SHIFT * lane, p)) return
    val flat = p[2].isNaN()
    scene.addLabel(layer, LandXmlPalette.LABEL, p[0], p[1], if (flat) 0.0 else p[2], text, objectId = objectId, flat = flat)
  }

  /**
   * Подпись [text] участка [from]…[to]: у участка нет точки применения, поэтому подпись — в его середине; [slot] —
   * сдвиг вдоль трассы (метры) для разведения подписей разных слоёв, но не за концы участка.
   */
  fun labelSpan(
    scene: LandXmlSceneBuilder, geometry: AlignmentGeometry, profile: AlignmentProfile?, layer: String, from: Double,
    to: Double, lane: Double, text: String, sceneSize: Double, objectId: Int = NO_OBJECT, slot: Double = 0.0,
  ) {
    val d0 = distance(geometry, from)
    val d1 = distance(geometry, to)
    val lo = minOf(d0, d1)
    val hi = max(d0, d1)
    val p = DoubleArray(3)
    val distance = ((lo + hi) / 2 + slot).coerceIn(lo, hi)
    if (!pointAt(geometry, profile, distance, sceneSize * LANE_SHIFT * lane, p)) return
    val flat = p[2].isNaN()
    scene.addLabel(layer, LandXmlPalette.LABEL, p[0], p[1], if (flat) 0.0 else p[2], text, objectId = objectId, flat = flat)
  }

  /** Значок в точке с боковым смещением [offset] метров влево (минус — вправо). */
  fun mark(
    scene: LandXmlSceneBuilder, geometry: AlignmentGeometry, profile: AlignmentProfile?, layer: String, color: Int,
    station: Double, offset: Double, objectId: Int = NO_OBJECT, text: String? = null,
  ): Boolean {
    val p = DoubleArray(3)
    if (!pointAt(geometry, profile, distance(geometry, station), offset, p)) return false
    val flat = p[2].isNaN()
    val z = if (flat) 0.0 else p[2]
    scene.addPoint(PointMark(layer, color, p[0], p[1], z, objectId = objectId), flat)
    if (!text.isNullOrEmpty()) {
      scene.addLabel(layer, LandXmlPalette.LABEL, p[0], p[1], z, text, objectId = objectId, flat = flat, alignV = 2, shiftDp = LabelScale.MARK_SHIFT_DP)
    }
    return true
  }

  /** Сдвиг подписи от середины участка для полосы [lane] (1…[LANES]): подписи полос идут по трассе друг за другом. */
  fun labelShift(lane: Double, sceneSize: Double): Double = (abs(lane) - (LANES + 1) / 2.0) * sceneSize * LABEL_SHIFT

  private const val LANES = 4

  private const val LABEL_SHIFT = 0.02

}
