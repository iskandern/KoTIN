package dev.iskandern.kotin.landxml

import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants
import javax.xml.stream.XMLStreamReader
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Эталонный читатель LandXML (KoTIN-75): StAX из JDK, медленный и простой, написан по спецификации,
 * а не по коду `landxml/` — чтобы ошибки читателя не переписались в эталон. Ничего из читателя (токенизатор, разбор
 * чисел, накопитель) не использует. Каждая задача LandXML добавляет сюда свой раздел сводки.
 */
object LandXmlReference {

  fun summarize(bytes: ByteArray): LandXmlSummary {
    val text = decodeXml(bytes)
    val xml = XMLInputFactory.newInstance().apply {
      setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
      setProperty(XMLInputFactory.SUPPORT_DTD, false)
    }.createXMLStreamReader(StringReader(text))
    val surfaces = ArrayList<SurfaceSummary>()
    val sourceData = ArrayList<SourceDataSummary>()
    // путь от корня — локальные имена (пространство имён 1.0, 1.1, 1.2 и 2.0 не различаем)
    val path = ArrayList<String>()
    var surface: SurfaceBuilder? = null
    val networks = ArrayList<PipeNetworkSummary>()
    var network: NetworkBuilder? = null
    while (xml.hasNext()) {
      when (xml.next()) {
        XMLStreamConstants.START_ELEMENT -> {
          val name = xml.localName
          val parent = path.lastOrNull()
          path.add(name)
          fun attr(n: String) = xml.getAttributeValue(null, n)
          when {
            name == "PipeNetwork" && parent == "PipeNetworks" -> network = NetworkBuilder(attr("name") ?: "", attr("pipeNetType") ?: "")
            network == null -> {}
            name == "Struct" && parent == "Structs" -> network.struct(attr("name") ?: "", attr("elevSump"))
            name == "Invert" && parent == "Struct" -> network.invert(attr("refPipe") ?: "", attr("elev"))
            name == "Pipe" && parent == "Pipes" -> network.pipe(attr("name") ?: "", attr("refStart") ?: "", attr("refEnd") ?: "")
          }
          when {
            name == "Surface" && parent == "Surfaces" -> surface = SurfaceBuilder(xml.getAttributeValue(null, "name") ?: "")
            surface == null -> {}
            name == "Definition" && parent == "Surface" -> surface.definition(xml)
            name == "P" && parent == "Pnts" && path.under("Definition") -> {
              val id = xml.getAttributeValue(null, "id")
              surface.point(id, readText(xml))
              path.removeLast()
            }
            name == "F" && parent == "Faces" && path.under("Definition") -> {
              surface.face(xml.getAttributeValue(null, "i"), readText(xml))
              path.removeLast()
            }
            // Surface/SourceData/Boundaries/Boundary/PntList3D и т. п.
            name == "Contour" && path.under("SourceData") -> surface.elev = xml.getAttributeValue(null, "elev")?.trim()?.toDouble()
            (name == "PntList2D" || name == "PntList3D") && parent in LINE_KINDS && path.size >= 4 && path[path.size - 4] == "SourceData" -> {
              surface.line(parent!!, if (name == "PntList2D") 2 else 3, readText(xml))
              path.removeLast()
            }
            (name == "PntList2D" || name == "PntList3D") && parent == "DataPoints" && path.under("SourceData") -> {
              surface.dataPoints(if (name == "PntList2D") 2 else 3, readText(xml))
              path.removeLast()
            }
          }
        }
        XMLStreamConstants.END_ELEMENT -> {
          val closed = path.removeLast()
          if (closed == "Surface" && path.lastOrNull() == "Surfaces") {
            surfaces.add(surface!!.build())
            sourceData.addAll(surface.sourceData())
            surface = null
          }
          if (closed == "PipeNetwork" && path.lastOrNull() == "PipeNetworks") {
            networks.add(network!!.build())
            network = null
          }
        }
      }
    }
    xml.close()
    return LandXmlSummary(
      surfaces, contours = contours(text),
      sourceData = sourceData.sortedWith(compareBy({ it.surface }, { it.kind })), pipeNetworks = networks,
      crossSects = crossSects(text), profiles = profiles(text), grounds = grounds(text),
    )
  }

  /** Профили земли (KoTIN-87) — по DOM: первый `ProfSurf` трассы, каждый `PntList2D` — свой кусок (пары «пикет отметка»). */
  private fun grounds(text: String): List<GroundSummary> {
    val doc = javax.xml.parsers.DocumentBuilderFactory.newInstance().apply {
      isNamespaceAware = true
      setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
    }.newDocumentBuilder().parse(org.xml.sax.InputSource(StringReader(text)))
    val all = doc.getElementsByTagNameNS("*", "Alignment").let { list -> (0 until list.length).map { list.item(it) as org.w3c.dom.Element } }
    return all.mapNotNull { alignment ->
      val surf = children(alignment, "Profile").flatMap { children(it, "ProfSurf") }.firstOrNull() ?: return@mapNotNull null
      val runs = children(surf, "PntList2D").map { numbers(it.textContent).let { n -> n.take(n.size / 2 * 2).toDoubleArray() } }
      GroundSummary(alignment.getAttribute("name"), surf.getAttribute("name"), runs.filter { it.isNotEmpty() })
    }
  }

  /**
   * Профили трасс (KoTIN-66) — по DOM, по спецификации 1.2 и задаче:
   * - пикет → расстояние: участки от `staStart` и от каждого `StaEquation@staInternal` (по возрастанию), на участке
   *   пикет идёт от `staAhead` вверх или вниз (`staIncrement="decreasing"`); длина последнего — до `staStart + length`
   *   (атрибут трассы); пикет вне участков — продолжение первого или последнего;
   * - берётся первый `ProfAlign` трассы; PVI по возрастанию расстояния, между ними — прямые; кривые у крайних PVI не строятся;
   * - парабола (`ParaCurve` — `length` пополам, `UnsymParaCurve` — `lengthIn`, `lengthOut`) — поправкой к касательной:
   *   `e·(t/l1)²` до PVI и `e·(t/l2)²` после (t — от ближнего конца кривой), `e = (g2 − g1)·l1·l2 / (2(l1 + l2))`;
   * - `CircCurve` — окружность радиуса `radius` (нет — `length / Δугла`): центр на биссектрисе угла уклонов в R / cos(Δ/2)
   *   от PVI, концы — в R·tg(Δ/2) от PVI по каждому уклону.
   */
  private fun profiles(text: String): List<ProfileSummary> {
    val doc = javax.xml.parsers.DocumentBuilderFactory.newInstance().apply {
      isNamespaceAware = true
      setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
    }.newDocumentBuilder().parse(org.xml.sax.InputSource(StringReader(text)))
    val all = doc.getElementsByTagNameNS("*", "Alignment").let { list -> (0 until list.length).map { list.item(it) as org.w3c.dom.Element } }
    val result = ArrayList<ProfileSummary>()
    for (alignment in all) {
      val profAlign = children(alignment, "Profile").flatMap { children(it, "ProfAlign") }.firstOrNull() ?: continue
      val staStart = alignment.getAttribute("staStart").toDoubleOrNull() ?: 0.0
      val length = alignment.getAttribute("length").toDouble()
      // участки пикетажа: начало (расстояние), пикет в начале, знак
      val regions = ArrayList<DoubleArray>()
      regions.add(doubleArrayOf(0.0, staStart, 1.0))
      children(alignment, "StaEquation")
        .map { doubleArrayOf(it.getAttribute("staInternal").toDouble() - staStart, it.getAttribute("staAhead").toDouble(),
          if (it.getAttribute("staIncrement") == "decreasing") -1.0 else 1.0) }
        .sortedBy { it[0] }.forEach { regions.add(it) }
      fun regionEnd(k: Int) = if (k + 1 < regions.size) regions[k + 1][0] else length
      fun distanceOf(station: Double): Double {
        for ((k, r) in regions.withIndex()) {
          val offset = (station - r[1]) * r[2]
          if (offset >= -1e-9 && offset <= regionEnd(k) - r[0] + 1e-9) return r[0] + offset
        }
        val first = (station - regions[0][1]) * regions[0][2]
        if (first < 0) return first
        val r = regions.last()
        return r[0] + (station - r[1]) * r[2]
      }
      fun stationOf(distance: Double): Double {
        val r = regions.last { it[0] <= distance + 1e-12 }
        return r[1] + r[2] * (distance - r[0])
      }
      // PVI: расстояние, отметка, элемент
      val pvis = children(profAlign).filter { it.localName in setOf("PVI", "ParaCurve", "UnsymParaCurve", "CircCurve") }
        .mapNotNull { e -> numbers(e.textContent).takeIf { it.size >= 2 }?.let { Triple(distanceOf(it[0]), it[1], e) } }
        .sortedBy { it.first }
      if (pvis.size < 2) continue
      val n = pvis.size
      val x = DoubleArray(n) { pvis[it].first }
      val z = DoubleArray(n) { pvis[it].second }
      fun grade(k: Int) = (z[k + 1] - z[k]) / (x[k + 1] - x[k])
      fun tangent(k: Int, at: Double) = z[k] + grade(k) * (at - x[k])
      // кривые: PVI, начало, конец, отметка
      class VCurve(val begin: Double, val end: Double, val elevation: (Double) -> Double)
      val curves = HashMap<Int, VCurve>()
      for (i in 1 until n - 1) {
        val e = pvis[i].third
        fun attr(name: String) = e.getAttribute(name).toDoubleOrNull() ?: Double.NaN
        val g1 = grade(i - 1)
        val g2 = grade(i)
        when (e.localName) {
          "ParaCurve", "UnsymParaCurve" -> {
            val l1 = if (e.localName == "ParaCurve") attr("length") / 2 else attr("lengthIn")
            val l2 = if (e.localName == "ParaCurve") attr("length") / 2 else attr("lengthOut")
            if (!(l1 > 0 && l2 > 0)) continue
            val offset = (g2 - g1) * l1 * l2 / (2 * (l1 + l2))
            curves[i] = VCurve(x[i] - l1, x[i] + l2) { at ->
              if (at <= x[i]) tangent(i - 1, at) + offset * ((at - (x[i] - l1)) / l1).let { it * it }
              else tangent(i, at) + offset * ((x[i] + l2 - at) / l2).let { it * it }
            }
          }
          "CircCurve" -> {
            val a1 = kotlin.math.atan(g1)
            val a2 = kotlin.math.atan(g2)
            val delta = abs(a2 - a1)
            val r = attr("radius").takeIf { it > 0 } ?: (attr("length") / delta)
            if (!(r > 0) || delta == 0.0) continue
            // биссектриса между направлением назад по первому уклону и вперёд по второму
            val bx = -kotlin.math.cos(a1) + kotlin.math.cos(a2)
            val bz = -kotlin.math.sin(a1) + kotlin.math.sin(a2)
            val bl = sqrt(bx * bx + bz * bz)
            val toCenter = r / kotlin.math.cos(delta / 2)
            val cx = x[i] + bx / bl * toCenter
            val cz = z[i] + bz / bl * toCenter
            val t = r * kotlin.math.tan(delta / 2)
            val sag = cz > z[i]
            curves[i] = VCurve(x[i] - t * kotlin.math.cos(a1), x[i] + t * kotlin.math.cos(a2)) { at ->
              val h = sqrt(r * r - (at - cx) * (at - cx))
              if (sag) cz - h else cz + h
            }
          }
        }
      }
      fun elevation(at: Double): Double {
        for ((_, c) in curves) if (at >= c.begin && at <= c.end) return c.elevation(at)
        val k = (0 until n - 1).first { at <= x[it + 1] || it == n - 2 }
        return tangent(k, at)
      }
      // ключевые точки и середины между ними — на трассе и в профиле
      val keys = (x.toList() + curves.values.flatMap { listOf(it.begin, it.end) }).sorted()
      val sample = (keys + keys.zipWithNext { a, b -> (a + b) / 2 }).sorted().distinct()
        .filter { it >= 0 && it <= length && it >= x[0] && it <= x[n - 1] }
      result.add(ProfileSummary(
        alignment.getAttribute("name"), profAlign.getAttribute("name"),
        sample.map { stationOf(it) }, sample.map { elevation(it) },
      ))
    }
    return result
  }

  /**
   * Сеть (схема 1.2: `Struct` — `name`, `elevSump`, `Invert[refPipe, elev]`; `Pipe` — `name`, `refStart`, `refEnd` — имена
   * колодцев этой же сети). Труба, у которой колодца на конце нет, не считается. Отметка конца трубы — `Invert@elev`
   * колодца с `refPipe` = имя трубы, иначе `elevSump`; нет на одном конце — берётся с другого (труба горизонтальна),
   * нет на обоих — 0 (труба в плане).
   */
  private class NetworkBuilder(val name: String, val type: String) {
    // колодец → (дно, лотки «труба → отметка»)
    val structs = LinkedHashMap<String, Pair<Double?, MutableMap<String, Double>>>()
    var last: MutableMap<String, Double>? = null
    val pipes = ArrayList<Triple<String, String, String>>()

    fun struct(name: String, sump: String?) {
      val inverts = LinkedHashMap<String, Double>()
      structs[name] = sump?.trim()?.toDouble() to inverts
      last = inverts
    }

    fun invert(pipe: String, elev: String?) {
      val z = elev?.trim()?.toDouble() ?: return
      // первый лоток трубы в колодце главнее
      last?.putIfAbsent(pipe, z)
    }

    fun pipe(name: String, start: String, end: String) {
      pipes.add(Triple(name, start, end))
    }

    fun build(): PipeNetworkSummary {
      fun elev(struct: String, pipe: String): Double? = structs[struct]!!.let { it.second[pipe] ?: it.first }
      val ends = pipes.filter { it.second in structs && it.third in structs }.map { (pipe, start, end) ->
        val s = elev(start, pipe)
        val e = elev(end, pipe)
        PipeEnds(pipe, s ?: e ?: 0.0, e ?: s ?: 0.0)
      }
      return PipeNetworkSummary(name, type, structs.size, ends)
    }
  }

  private val LINE_KINDS = setOf("Boundary", "Breakline", "Contour")

  /**
   * Ситуация и участки (KoTIN-65) — отдельным проходом по DOM: ссылки `pntRef` и `Chain` ищутся по всем `CgPoint`
   * файла. Правила (спецификация 1.2 и задача):
   * - элементы `CoordGeom` по порядку: `Line` (Start, End), `Curve` (Start, Center, End, `rot`; `crvType="chord"` — хорда),
   *   `IrregularLine` (PntList2D/3D, иначе Start–End), `Chain` (имена точек), `Spiral` — прямой Start–End;
   * - новая ломаная — где начало элемента не совпало с концом предыдущего;
   * - замкнут — одна ломаная и (участок всегда замыкается, ситуация — если конец совпал с началом);
   * - дуга без `Center` — хорда (в фикстурах центр есть всегда);
   * - площадь — Гаусс по хордам плюс сегменты дуг `r²/2·(θ − sin θ)` (θ со знаком, + против часовой) — точная;
   * - `Location` — точка из текста (не меньше двух чисел) или найденный `pntRef`.
   */
  private fun contours(text: String): List<ContourSummary> {
    val all = elements(text)
    // CgPoint: имя → (восток, север, отметка); повтор — действует последний
    val points = HashMap<String, DoubleArray>()
    for (e in all) {
      if (e.localName != "CgPoint") continue
      val n = numbers(e.textContent)
      if (n.size >= 2) points[e.getAttribute("name")] = doubleArrayOf(n[1], n[0], n.getOrElse(2) { 0.0 })
    }
    fun pointOf(e: org.w3c.dom.Element?): DoubleArray? {
      if (e == null) return null
      if (e.hasAttribute("pntRef")) return points[e.getAttribute("pntRef").trim()]
      val n = numbers(e.textContent)
      return if (n.size >= 2) doubleArrayOf(n[1], n[0]) else null
    }
    val result = ArrayList<ContourSummary>()
    for (e in all) {
      val parcel = e.localName == "Parcel"
      if (!parcel && e.localName != "PlanFeature") continue
      // отрезки и дуги: x1 y1 x2 y2 и площадь сегмента дуги (у отрезка 0)
      val pieces = ArrayList<DoubleArray>()
      for (geom in children(e, "CoordGeom")) for (item in children(geom)) {
        val start = pointOf(children(item, "Start").firstOrNull())
        val end = pointOf(children(item, "End").firstOrNull())
        when (item.localName) {
          "Line", "Spiral" -> if (start != null && end != null) pieces.add(doubleArrayOf(start[0], start[1], end[0], end[1], 0.0))
          "Curve" -> {
            if (start == null || end == null) continue
            val center = pointOf(children(item, "Center").firstOrNull())
            var segment = 0.0
            if (item.getAttribute("crvType") != "chord" && center != null) {
              val r = kotlin.math.hypot(start[0] - center[0], start[1] - center[1])
              var sweep = kotlin.math.atan2(end[1] - center[1], end[0] - center[0]) -
                kotlin.math.atan2(start[1] - center[1], start[0] - center[0])
              if (item.getAttribute("rot") == "cw") {
                while (sweep >= 0) sweep -= 2 * Math.PI
              } else {
                while (sweep <= 0) sweep += 2 * Math.PI
              }
              segment = r * r / 2 * (sweep - kotlin.math.sin(sweep))
            }
            pieces.add(doubleArrayOf(start[0], start[1], end[0], end[1], segment))
          }
          "IrregularLine", "Chain" -> {
            val list = if (item.localName == "Chain") {
              item.textContent.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.mapNotNull { points[it] }
            } else {
              val list3 = children(item, "PntList3D").firstOrNull()
              val list2 = children(item, "PntList2D").firstOrNull()
              val dim = if (list3 != null) 3 else 2
              val n = numbers((list3 ?: list2)?.textContent ?: "")
              if (n.size >= 2 * dim) (0 until n.size / dim).map { doubleArrayOf(n[it * dim + 1], n[it * dim]) }
              else listOfNotNull(start, end)
            }
            for (k in 1 until list.size) pieces.add(doubleArrayOf(list[k - 1][0], list[k - 1][1], list[k][0], list[k][1], 0.0))
          }
        }
      }
      var contours = 0
      var area = 0.0
      for ((k, p) in pieces.withIndex()) {
        if (k == 0 || !near(pieces[k - 1][2], pieces[k - 1][3], p[0], p[1])) contours++
        area += (p[0] * p[3] - p[2] * p[1]) / 2 + p[4]
      }
      val closed = contours == 1 && (parcel || near(pieces.last()[2], pieces.last()[3], pieces[0][0], pieces[0][1]))
      // замыкающий отрезок участка: конец последнего → начало первого (у сошедшегося контура он нулевой)
      if (closed) area += (pieces.last()[2] * pieces[0][1] - pieces[0][0] * pieces.last()[3]) / 2
      val locations = if (parcel) 0 else children(e, "Location").count { pointOf(it) != null }
      result.add(ContourSummary(e.localName, e.getAttribute("name").trim(), contours, closed, if (closed) area else Double.NaN, locations))
    }
    return result
  }

  /**
   * Поперечники (KoTIN-69) — проходом по DOM. Правила (спецификация 1.2 и задача):
   * - трасса — только `Line` и `Curve` (Start, Center, End, `rot`) по порядку; трасса с другими элементами не считается;
   * - пикет → расстояние: участки от `staStart` и от каждого `StaEquation` (`staInternal` — где начинается, `staAhead` —
   *   пикет там, `staIncrement="decreasing"` — пикеты убывают); пикета нет ни на одном участке — поперечник пропущен;
   * - точка: P + смещение × (ty, −tx), где (tx, ty) — единичная касательная (правая нормаль), отметка — из файла;
   * - `CrossSectSurf`: каждый `PntList2D` из двух и больше точек — ломаная земли; `DesignCrossSectSurf`: точки `CrossSectPnt`
   *   без `dataFormat="Slope Distance"` и без ссылок `alignRef`/`planFeatureRef`/`parcelRef` — одна ломаная проекта.
   */
  private fun crossSects(text: String): List<CrossSectSummary> {
    val result = ArrayList<CrossSectSummary>()
    for (alignment in elements(text).filter { it.localName == "Alignment" }) {
      if (children(alignment, "CrossSects").isEmpty()) continue
      // элементы: x1 y1 x2 y2, у дуги ещё центр и знак поворота (+1 против часовой)
      val items = ArrayList<DoubleArray>()
      var supported = true
      for (geom in children(alignment, "CoordGeom")) for (item in children(geom)) {
        fun pt(name: String) = numbers(children(item, name).first().textContent).let { doubleArrayOf(it[1], it[0]) }
        when (item.localName) {
          "Line" -> items.add(pt("Start") + pt("End"))
          "Curve" -> items.add(pt("Start") + pt("End") + pt("Center") + doubleArrayOf(if (item.getAttribute("rot") == "cw") -1.0 else 1.0))
          else -> supported = false
        }
      }
      if (!supported || items.isEmpty()) continue
      fun sweep(it: DoubleArray): Double {
        var a = kotlin.math.atan2(it[3] - it[5], it[2] - it[4]) - kotlin.math.atan2(it[1] - it[5], it[0] - it[4])
        if (it[6] > 0) { while (a <= 0) a += 2 * Math.PI } else { while (a >= 0) a -= 2 * Math.PI }
        return a
      }
      fun lengthOf(it: DoubleArray) =
        if (it.size == 4) kotlin.math.hypot(it[2] - it[0], it[3] - it[1]) else kotlin.math.hypot(it[0] - it[4], it[1] - it[5]) * abs(sweep(it))
      val total = items.sumOf { lengthOf(it) }
      val staStart = alignment.getAttribute("staStart").trim().toDouble()
      // участки пикетажа: (начало по «внутреннему» пикету, пикет в начале, знак)
      val regions = (listOf(Triple(staStart, staStart, 1.0)) + children(alignment, "StaEquation").map {
        Triple(it.getAttribute("staInternal").toDouble(), it.getAttribute("staAhead").toDouble(),
          if (it.getAttribute("staIncrement") == "decreasing") -1.0 else 1.0)
      }.sortedBy { it.first })
      fun distanceOf(sta: Double): Double? {
        for ((k, r) in regions.withIndex()) {
          val to = if (k + 1 < regions.size) regions[k + 1].first else staStart + total
          val offset = (sta - r.second) * r.third
          if (offset >= -1e-9 && offset <= to - r.first + 1e-9) return r.first - staStart + offset
        }
        return null
      }
      // точка и единичная касательная на расстоянии d
      fun at(d: Double): DoubleArray {
        var rest = d
        for ((k, it) in items.withIndex()) {
          val len = lengthOf(it)
          if (rest > len && k < items.size - 1) {
            rest -= len
            continue
          }
          if (it.size == 4) {
            val tx = (it[2] - it[0]) / len
            val ty = (it[3] - it[1]) / len
            return doubleArrayOf(it[0] + tx * rest, it[1] + ty * rest, tx, ty)
          }
          val r = kotlin.math.hypot(it[0] - it[4], it[1] - it[5])
          val a = kotlin.math.atan2(it[1] - it[5], it[0] - it[4]) + it[6] * rest / r
          // касательная — радиус-вектор, повёрнутый на 90° в сторону поворота
          val rx = kotlin.math.cos(a)
          val ry = kotlin.math.sin(a)
          return doubleArrayOf(it[4] + r * rx, it[5] + r * ry, -ry * it[6], rx * it[6])
        }
        error("пустая трасса")
      }
      val name = alignment.getAttribute("name").trim()
      for (sects in children(alignment, "CrossSects")) for (sect in children(sects, "CrossSect")) {
        val sta = sect.getAttribute("sta").trim().toDouble()
        val d = distanceOf(sta) ?: continue
        val p = at(d)
        fun place(pairs: List<Double>) = (0 until pairs.size / 2).map {
          val off = pairs[it * 2]
          doubleArrayOf(p[0] + off * p[3], p[1] - off * p[2], pairs[it * 2 + 1])
        }
        val ground = children(sect, "CrossSectSurf").flatMap { children(it, "PntList2D") }
          .map { numbers(it.textContent) }.filter { it.size >= 4 }.map { place(it) }
        val design = children(sect, "DesignCrossSectSurf").map { surf ->
          children(surf, "CrossSectPnt").filter {
            it.getAttribute("dataFormat") != "Slope Distance" &&
              listOf("alignRef", "planFeatureRef", "parcelRef").none { a -> it.hasAttribute(a) }
          }.flatMap { numbers(it.textContent).take(2) }
        }.filter { it.size >= 4 }.map { place(it) }
        if (ground.isEmpty() && design.isEmpty()) continue
        result.add(CrossSectSummary(name, sta, ground, design))
      }
    }
    return result
  }

  private fun elements(text: String): List<org.w3c.dom.Element> {
    val doc = javax.xml.parsers.DocumentBuilderFactory.newInstance().apply {
      isNamespaceAware = true
      setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
    }.newDocumentBuilder().parse(org.xml.sax.InputSource(StringReader(text)))
    return doc.getElementsByTagNameNS("*", "*").let { list -> (0 until list.length).map { list.item(it) as org.w3c.dom.Element } }
  }

  private fun children(e: org.w3c.dom.Element, name: String? = null): List<org.w3c.dom.Element> {
    val nodes = e.childNodes
    return (0 until nodes.length).map { nodes.item(it) }.filterIsInstance<org.w3c.dom.Element>()
      .filter { name == null || it.localName == name }
  }

  private fun numbers(text: String): List<Double> = text.trim().split(Regex("\\s+")).mapNotNull { it.toDoubleOrNull() }

  private fun near(x1: Double, y1: Double, x2: Double, y2: Double) = abs(x1 - x2) + abs(y1 - y2) <= 1e-6

  private fun List<String>.under(name: String) = size >= 3 && this[size - 3] == name

  // текст элемента вместе с его концом (вложенных элементов у P и F нет)
  private fun readText(xml: XMLStreamReader): String = xml.elementText

  /**
   * Текст файла: BOM → UTF-8 или UTF-16; иначе UTF-8, если байты корректны, иначе windows-1251 (выгрузка «ANSI»
   * без объявления кодировки). Объявление `encoding` в файле не смотрим — StAX получает уже готовые символы.
   */
  fun decodeXml(bytes: ByteArray): String {
    fun b(i: Int) = if (i < bytes.size) bytes[i].toInt() and 0xFF else -1
    return when {
      b(0) == 0xEF && b(1) == 0xBB && b(2) == 0xBF -> String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
      b(0) == 0xFF && b(1) == 0xFE -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
      b(0) == 0xFE && b(1) == 0xFF -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
      else -> try {
        Charsets.UTF_8.newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(bytes)).toString()
      } catch (e: CharacterCodingException) {
        String(bytes, Charset.forName("windows-1251"))
      }
    }
  }

  /**
   * Одна поверхность. Правила (спецификация 1.2):
   * - P: `id` и текст «север восток отметка»; точка без трёх чисел в счёт точек идёт, но ссылки на неё неверны;
   *   при повторе `id` действует первая точка;
   * - F: 3 `id` (TIN) или 4 (grid: два треугольника 1-2-3 и 1-3-4); `i="1"` — невидима; ссылка на несуществующую
   *   точку — грань «с неверной ссылкой», в площади не идёт, даже видимая;
   * - габариты и контрольные суммы — по точкам видимых граней (каждая один раз): только они попадают в сцену.
   */
  private class SurfaceBuilder(val name: String) {
    // id → (восток, север, отметка)
    val points = HashMap<Long, DoubleArray>()
    var pointCount = 0
    var faces = 0
    var visible = 0
    var bad = 0
    var area2D = 0.0
    var area3D = 0.0
    val used = HashSet<Long>()
    var fileArea2D: Double? = null
    var fileArea3D: Double? = null
    var fileElevMin: Double? = null
    var fileElevMax: Double? = null

    fun definition(xml: XMLStreamReader) {
      fun attr(name: String) = xml.getAttributeValue(null, name)?.trim()?.toDouble()
      fileArea2D = attr("area2DSurf")
      fileArea3D = attr("area3DSurf")
      fileElevMin = attr("elevMin")
      fileElevMax = attr("elevMax")
    }

    fun point(id: String?, text: String) {
      pointCount++
      val key = id?.trim()?.toLongOrNull() ?: return
      val numbers = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
      val n = numbers.map { it.toDoubleOrNull() }
      if (n.size != 3 || n.any { it == null }) return
      if (key in points) return
      points[key] = doubleArrayOf(n[1]!!, n[0]!!, n[2]!!)
    }

    fun face(i: String?, text: String) {
      faces++
      val ids = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.map { it.toLongOrNull() }
      // ячейка grid «нет данных»: все id нулевые
      if (ids.size >= 3 && ids.all { it == 0L }) return
      if ((ids.size != 3 && ids.size != 4) || ids.any { it == null || it !in points }) {
        bad++
        return
      }
      if (i?.trim() == "1") return
      var p = ids.map { points[it]!! }
      // ячейка с отметкой-заглушкой пропускается целиком
      if (p.size == 4 && p.any { isNoData(it[2]) }) return
      visible++
      // обход ячейки «крест-накрест»: пересекаются противоположные стороны — меняем две вершины
      if (p.size == 4) {
        if (cross(p[1], p[2], p[3], p[0])) p = listOf(p[0], p[1], p[3], p[2])
        else if (cross(p[0], p[1], p[2], p[3])) p = listOf(p[0], p[2], p[1], p[3])
      }
      addTriangle(p[0], p[1], p[2])
      if (p.size == 4) addTriangle(p[0], p[2], p[3])
      for (id in ids) used.add(id!!)
    }

    private fun isNoData(z: Double) = z in listOf(-9999.0, -99999.0, -999999.0, 99999.0) || abs(z) >= 1e30

    // отрезки a–b и c–d пересекаются в плане во внутренних точках
    private fun cross(a: DoubleArray, b: DoubleArray, c: DoubleArray, d: DoubleArray): Boolean {
      fun turn(o: DoubleArray, p: DoubleArray, q: DoubleArray) = (p[0] - o[0]) * (q[1] - o[1]) - (p[1] - o[1]) * (q[0] - o[0])
      return turn(a, b, c) * turn(a, b, d) < 0 && turn(c, d, a) * turn(c, d, b) < 0
    }

    fun addTriangle(a: DoubleArray, b: DoubleArray, c: DoubleArray) {
      val ux = b[0] - a[0]; val uy = b[1] - a[1]; val uz = b[2] - a[2]
      val vx = c[0] - a[0]; val vy = c[1] - a[1]; val vz = c[2] - a[2]
      val cx = uy * vz - uz * vy
      val cy = uz * vx - ux * vz
      val cz = ux * vy - uy * vx
      area2D += abs(cz) / 2
      area3D += sqrt(cx * cx + cy * cy + cz * cz) / 2
    }

    /**
     * Исходные данные (`SourceData`): список «N E» / «N E Z» → точки (восток, север, отметка); меньше двух точек — не
     * считается. Отрезки — между соседними точками, у границы ещё замыкающий, если последняя точка не равна первой;
     * считаются в плане: повтор точки — не отрезок, соседние, продолжающие друг друга по прямой, — один, длина — в плане (2D-линии сцена
     * сажает на поверхность и дробит по рёбрам, KoTIN-67). У горизонтали отметка — `elev` (без него горизонталь не
     * считается), у 2D-списка — 0.
     */
    var elev: Double? = null
    private val lines = LinkedHashMap<String, DoubleArray>() // вид → [списков, точек, отрезков, длина]
    private var dataLists = 0
    private var dataPoints = 0

    fun line(kind: String, dimensions: Int, text: String) {
      var p = listPoints(dimensions, text)
      if (kind == "Contour") {
        val z = elev ?: return
        p = p.map { doubleArrayOf(it[0], it[1], z) }
      }
      if (p.size < 2) return
      if (kind == "Boundary" && !p.first().contentEquals(p.last())) p = p + listOf(p.first())
      var length = 0.0
      for (k in 1 until p.size) {
        val dx = p[k][0] - p[k - 1][0]; val dy = p[k][1] - p[k - 1][1]
        length += sqrt(dx * dx + dy * dy)
      }
      // в плане: повтор точки — не отрезок; стыки по прямой — внутренние и, у замкнутой, в первой точке
      val all = p
      val q = all.filterIndexed { k, it -> k == 0 || it[0] != all[k - 1][0] || it[1] != all[k - 1][1] }
      fun segment(k: Int) = doubleArrayOf(q[k][0], q[k][1], q[k + 1][0], q[k + 1][1])
      var joints = (1 until q.size - 1).count { continuesInPlan(segment(it - 1), segment(it)) }
      if (q.size > 2 && q.first()[0] == q.last()[0] && q.first()[1] == q.last()[1] && continuesInPlan(segment(q.size - 2), segment(0))) joints++
      val acc = lines.getOrPut(kind) { DoubleArray(4) }
      acc[0] += 1.0
      acc[1] += listPoints(dimensions, text).size.toDouble()
      acc[2] += (q.size - 1 - joints).toDouble()
      acc[3] += length
    }

    fun dataPoints(dimensions: Int, text: String) {
      dataLists++
      dataPoints += listPoints(dimensions, text).size
    }

    // «N E [Z] …» → (восток, север, отметка); неполная точка в конце отбрасывается
    private fun listPoints(dimensions: Int, text: String): List<DoubleArray> {
      val n = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.map { it.toDouble() }
      return (0 until n.size / dimensions).map { k ->
        doubleArrayOf(n[k * dimensions + 1], n[k * dimensions], if (dimensions == 3) n[k * dimensions + 2] else 0.0)
      }
    }

    fun sourceData(): List<SourceDataSummary> {
      val result = lines.map { (kind, a) -> SourceDataSummary(name, kind, a[0].toInt(), a[1].toInt(), a[2].toInt(), a[3]) }
      return if (dataPoints == 0) result else result + SourceDataSummary(name, DATA_POINTS_KIND, dataLists, dataPoints, 0, 0.0)
    }

    fun build(): SurfaceSummary {
      val box = Box()
      for (id in used) box.add(points[id]!!)
      return SurfaceSummary(
        name, pointCount, faces, visible, bad, used.size, box, area2D, area3D,
        fileArea2D, fileArea3D, fileElevMin, fileElevMax,
      )
    }
  }
}
