package dev.iskandern.kotin.landxml

/** Единицы длины из `Units`: коэффициенты перевода в метры. Строки разбираются один раз при чтении `Units`. */
internal object LandXmlUnits {
  /** Метров в единице; NaN — единица неизвестна. Имя — без учёта регистра. */
  fun meters(name: String?): Double = when (name?.lowercase()) {
    "millimeter" -> 0.001
    "centimeter" -> 0.01
    "meter" -> 1.0
    "kilometer" -> 1000.0
    "inch" -> 0.0254
    "foot" -> 0.3048
    "ussurveyfoot" -> 1200.0 / 3937.0
    "mile" -> 1609.344
    else -> Double.NaN
  }

  /** Во сколько раз размер сечения (`diameterUnit`) отличается от линейной единицы файла; NaN — неизвестно. */
  fun sectionScale(linearUnit: String?, diameterUnit: String?): Double = meters(diameterUnit) / meters(linearUnit)
}
