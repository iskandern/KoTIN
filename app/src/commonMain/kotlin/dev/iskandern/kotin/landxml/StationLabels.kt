package dev.iskandern.kotin.landxml

import dev.iskandern.kotin.dxf.FACE_MONO_BOLD
import dev.iskandern.kotin.dxf.FACE_MONO_ITALIC
import dev.iskandern.kotin.model.NO_OBJECT
import dev.iskandern.kotin.model.NO_TICK
import dev.iskandern.kotin.model.TICK_DP
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToLong

/**
 * Запись пикета на языке интерфейса: [label] — шаблон с номером «12+34» (`%1$s`), [decimal] — знак дробной части.
 * По умолчанию — российская «ПК12+34,56», английская — «STA 12+34.56».
 */
class StationNotation(val label: String = "ПК%1\$s", val decimal: Char = ',') {
  fun text(number: String) = label.replace("%1\$s", number)
}

/**
 * Пикетаж трассы: на кратных шагу пикетах — засечка 14 dp вверх от трассы и над ней подпись «ПК3» (на круглой сотне)
 * или «ПК12+34,56», жирным моноширинным постоянного размера на экране; на концах — тоже только пикет, имя трассы — одно,
 * посередине ([addName]). Подписи промежуточных пикетов — на своём слое [MID_LAYER]: их можно скрыть, оставив концы;
 * засечки всех пикетов — на слое пикетажа, видны всегда.
 */
internal object StationLabels {
  const val LAYER = "@layer_stationing"

  /** Пикеты между концами трассы (с засечками); скрыт — видны только концы. */
  const val MID_LAYER = "@layer_stationing_mid"

  // «круглые» шаги подписей, м
  private val STEPS = doubleArrayOf(10.0, 20.0, 50.0, 100.0, 250.0, 500.0, 1000.0, 2500.0, 5000.0, 10000.0)
  private const val MAX_LABELS_WANTED = 50
  private const val MAX_LABELS = 100

  // строка — на 1 dp выше засечки; имя — так же, в ряд с подписями пикетов
  private const val STATION_LIFT_DP = TICK_DP + 1.0

  /**
   * Шаг подписей: наименьший «круглый», при котором на размер сцены [sceneSize] приходится не больше ~50 подписей
   * (на трассу во всю сцену — 20–50), но и трассе длиной [length] — не больше [MAX_LABELS] (её длина бывает больше сцены).
   */
  fun labelStep(sceneSize: Double, length: Double = 0.0): Double =
    STEPS.firstOrNull { sceneSize / it <= MAX_LABELS_WANTED && length / it <= MAX_LABELS - 2 } ?: STEPS.last()

  /** Запись пикета (1 пикет = 100 м): «ПК12+34,56»; остаток — две цифры, дробная часть без нулей в конце. */
  fun format(station: Double, notation: StationNotation = StationNotation()): String {
    if (!station.isFinite()) return station.toString()
    val hundredths = (abs(station) * 100).roundToLong()
    val pk = hundredths / 10000
    val rest = hundredths % 10000
    val whole = (rest / 100).toString().padStart(2, '0')
    val fraction = (rest % 100).toString().padStart(2, '0').trimEnd('0')
    val sign = if (station < 0 && hundredths != 0L) "-" else ""
    return sign + notation.text("$pk+$whole" + if (fraction.isEmpty()) "" else "${notation.decimal}$fraction")
  }

  /** Короткая запись: на круглой сотне — только номер пикета («ПК3»), иначе — полная ([format]). */
  fun short(station: Double, notation: StationNotation = StationNotation()): String {
    if (!station.isFinite()) return station.toString()
    val hundredths = (abs(station) * 100).roundToLong()
    if (hundredths % 10000 != 0L) return format(station, notation)
    val sign = if (station < 0 && hundredths != 0L) "-" else ""
    return sign + notation.text((hundredths / 10000).toString())
  }

  /**
   * Угол подписи вдоль трассы, градусы, в (−90, 90]: текст не вверх ногами. Подписи пикетов горизонтальны на экране
   * (SceneLabel.screenHorizontal), угол — у имени трассы ([addName]).
   */
  fun readableAngle(direction: Double): Double {
    var deg = direction * 180.0 / PI
    deg -= floor((deg + 180.0) / 360.0) * 360.0
    if (deg > 90.0) deg -= 180.0 else if (deg <= -90.0) deg += 180.0
    return deg
  }

  /**
   * Подписи пикетов трассы [geometry] в [scene]: начало и конец (и концы профиля [profile], если линия короче трассы),
   * остальные — на кратных шагу пикетах, строки — на слое [MID_LAYER]. Шаг — от размера сцены (без неё — от габаритов трассы). С профилем [profile] — на его отметках, без
   * профиля (и за концами профиля) — плоские: их сажает посадка. [objectId] — объект трассы; запись пикета —
   * [LandXmlSceneBuilder.stationNotation].
   */
  fun add(
    scene: LandXmlSceneBuilder, geometry: AlignmentGeometry, objectId: Int = NO_OBJECT,
    profile: AlignmentProfile? = null,
  ) {
    val length = geometry.length
    if (length <= 0.0) return
    val layer = scene.layer(LAYER, LandXmlPalette.STATIONING, LandXmlGroup.ALIGNMENTS)
    // сцена к этой стадии уже с трассой; пустой не бывает, но на всякий случай — длина трассы
    val sceneSize = if (scene.size > 0.0) scene.size else length
    val stationing = geometry.stationing
    val eps = max(1e-6, length * 1e-9)
    val height = LabelScale.height(LabelScale.STATION_DP)
    val notation = scene.stationNotation
    var labels = 0

    fun mark(distance: Double, text: String, layer: String, tick: Boolean = true) {
      val p = geometry.atDistance(distance) ?: return
      val z = profile?.elevation(distance) ?: Double.NaN
      val flat = z.isNaN()
      scene.addLabel(
        layer, LandXmlPalette.STATIONING, p.x, p.y, if (flat) 0.0 else z, text,
        rotationDeg = readableAngle(p.direction), height = height, objectId = objectId, flat = flat,
        face = FACE_MONO_BOLD, alignH = 1, alignV = 1, liftDp = STATION_LIFT_DP,
        tickRgb = if (tick) LandXmlPalette.ALIGNMENT else NO_TICK,
      )
    }

    val step = labelStep(sceneSize, length)
    // линия с частичным профилем есть только между его концами (drawProfiled) — там тоже подписи концов
    val drawnStart = profile?.start?.takeIf { it > eps }
    val drawnEnd = profile?.end?.takeIf { it < length - eps }
    fun atDrawnEnd(d: Double) = (drawnStart != null && abs(d - drawnStart) <= eps) || (drawnEnd != null && abs(d - drawnEnd) <= eps)
    // начало и конец — всегда, вне лимита; промежуточных — не больше MAX_LABELS − 2
    mark(0.0, short(stationing.station(0.0), notation), layer)
    if (drawnStart != null) mark(drawnStart, short(stationing.station(drawnStart), notation), layer)
    stationing.forEachMultiple(step) { d, station ->
      // строка подписи — только после проверки лимита
      if (d > eps && d < length - eps && !atDrawnEnd(d) && labels < MAX_LABELS - 2) {
        labels++
        // засечка — на слое пикетажа: место пикета видно и тогда, когда его подпись скрыта
        mark(d, "", layer)
        mark(d, short(station, notation), scene.layer(MID_LAYER, LandXmlPalette.STATIONING, LandXmlGroup.ALIGNMENTS), tick = false)
      }
    }
    if (drawnEnd != null) mark(drawnEnd, short(stationing.station(drawnEnd), notation), layer)
    mark(length, short(stationing.station(length), notation), layer)
  }

  /**
   * Имя трассы [name] курсивом вдоль линии посередине трассы, над засечками в ряд с подписями пикетов, на слое [layer]
   * трассы (видно и без пикетажа). С профилем — на его отметке, без — плоская: её сажает посадка.
   */
  fun addName(
    scene: LandXmlSceneBuilder, layer: String, geometry: AlignmentGeometry, name: String, objectId: Int = NO_OBJECT,
    profile: AlignmentProfile? = null,
  ) {
    if (name.isEmpty() || geometry.length <= 0.0) return
    val middle = geometry.length / 2
    val p = geometry.atDistance(middle) ?: return
    val z = profile?.elevation(middle) ?: Double.NaN
    scene.addLabel(
      layer, LandXmlPalette.STATIONING, p.x, p.y, if (z.isNaN()) 0.0 else z, name,
      rotationDeg = readableAngle(p.direction), height = LabelScale.height(LabelScale.TITLE_DP),
      objectId = objectId, flat = z.isNaN(), face = FACE_MONO_ITALIC, alignH = 1, alignV = 1,
      screenHorizontal = false, liftDp = STATION_LIFT_DP,
    )
  }
}
