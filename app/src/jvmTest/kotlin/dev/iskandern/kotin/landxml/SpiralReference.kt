package dev.iskandern.kotin.landxml

import java.io.StringReader
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Эталон переходных кривых (KoTIN-64, раздел эталона KoTIN-75): написан по формулам кривизны, а не по коду
 * читателя. Клотоида от прямой или к прямой — ряды Френеля; остальное — кривизна по формуле типа, направление —
 * трапециями по кривизне, точка — трапециями по направлению, мелкий шаг ([STEPS]).
 */
object SpiralReference {
  private const val STEPS = 200_000

  /** Кривизна в доле `x` длины, от 0 (начальная) до 1 (конечная); типы без формулы — клотоида. */
  fun shape(type: String, x: Double): Double = when (type.removePrefix("rev").lowercase()) {
    "bloss" -> 3 * x * x - 2 * x * x * x
    "sinusoid" -> x - sin(2 * PI * x) / (2 * PI)
    "cosine" -> (1 - cos(PI * x)) / 2
    "biquadratic" -> if (x <= 0.5) 2 * x * x else 1 - 2 * (1 - x) * (1 - x)
    else -> x
  }

  /**
   * Точка на расстоянии [s] от начала: x, y, направление. Начало — ([x0], [y0]) с направлением [theta0];
   * кривизны со знаком (+ влево), 0 — бесконечный радиус.
   */
  fun at(type: String, length: Double, k0: Double, k1: Double, x0: Double, y0: Double, theta0: Double, s: Double): DoubleArray {
    if (shape(type, 0.5) == 0.5 && shape(type, 0.25) == 0.25 && s == length && (k0 == 0.0 || k1 == 0.0)) {
      return fresnel(length, k0, k1, x0, y0, theta0)
    }
    val n = (STEPS * s / length).toInt().coerceAtLeast(1)
    val h = s / n
    var theta = theta0
    var x = x0
    var y = y0
    var k = k0
    for (i in 1..n) {
      val k2 = k0 + (k1 - k0) * shape(type, i * h / length)
      val theta2 = theta + (k + k2) / 2 * h
      x += (cos(theta) + cos(theta2)) / 2 * h
      y += (sin(theta) + sin(theta2)) / 2 * h
      theta = theta2
      k = k2
    }
    return doubleArrayOf(x, y, theta)
  }

  /**
   * Клотоида целиком, один радиус бесконечен: `C = ∫₀ᴸ cos(a·u²)`, `S = ∫₀ᴸ sin(a·u²)` рядами;
   * от прямой поворот `a·s²`, к прямой — то же от конца.
   */
  private fun fresnel(length: Double, k0: Double, k1: Double, x0: Double, y0: Double, theta0: Double): DoubleArray {
    val fromLine = k0 == 0.0
    val a = (if (fromLine) k1 else k0) / (2 * length)
    var c = 0.0
    var s = 0.0
    var term = length // a^m·L^(2m+1)/m! со знаком
    for (m in 0 until 60) {
      val part = term / (2 * m + 1)
      when (m % 4) {
        0 -> c += part
        1 -> s += part
        2 -> c -= part
        3 -> s -= part
      }
      term *= a * length * length / (m + 1)
    }
    return if (fromLine) {
      doubleArrayOf(x0 + cos(theta0) * c - sin(theta0) * s, y0 + sin(theta0) * c + cos(theta0) * s, theta0 + a * length * length)
    } else {
      // к прямой: поворот θE − a·u², u — расстояние до конца
      val end = theta0 + k0 * length / 2
      doubleArrayOf(x0 + cos(end) * c + sin(end) * s, y0 + sin(end) * c - cos(end) * s, end)
    }
  }

  /** Спираль файла: атрибуты, точки (восток, север) и расчётный по эталону конец; начальное направление — на PI. */
  class Item(
    val type: String, val length: Double, val k0: Double, val k1: Double,
    val start: DoubleArray, val pi: DoubleArray, val end: DoubleArray,
  ) {
    val theta0: Double get() = atan2(pi[1] - start[1], pi[0] - start[0])
    fun at(s: Double) = at(type, length, k0, k1, start[0], start[1], theta0, s)
  }

  /** Все `Spiral` файла по порядку. */
  fun spirals(bytes: ByteArray): List<Item> {
    val xml = XMLInputFactory.newInstance().apply {
      setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
      setProperty(XMLInputFactory.SUPPORT_DTD, false)
    }.createXMLStreamReader(StringReader(LandXmlReference.decodeXml(bytes)))
    val result = ArrayList<Item>()
    var attrs: Map<String, String>? = null
    val points = HashMap<String, DoubleArray>()
    while (xml.hasNext()) {
      when (xml.next()) {
        XMLStreamConstants.START_ELEMENT -> when (xml.localName) {
          "Spiral" -> {
            attrs = (0 until xml.attributeCount).associate { xml.getAttributeLocalName(it) to xml.getAttributeValue(it) }
            points.clear()
          }
          "Start", "PI", "End" -> if (attrs != null) {
            val ne = xml.elementText.trim().split(Regex("\\s+")).map { it.toDouble() }
            points[xml.localName] = doubleArrayOf(ne[1], ne[0])
          }
        }
        XMLStreamConstants.END_ELEMENT -> if (xml.localName == "Spiral") {
          val a = attrs!!
          val rot = if (a["rot"] == "cw") -1.0 else 1.0
          fun k(name: String) = a.getValue(name).let { if (it == "INF") 0.0 else rot / it.toDouble() }
          result.add(Item(a.getValue("spiType"), a.getValue("length").toDouble(), k("radiusStart"), k("radiusEnd"),
            points.getValue("Start"), points.getValue("PI"), points.getValue("End")))
          attrs = null
        }
      }
    }
    xml.close()
    return result
  }
}
