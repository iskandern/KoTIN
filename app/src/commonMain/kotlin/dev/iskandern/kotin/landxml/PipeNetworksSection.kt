package dev.iskandern.kotin.landxml

import dev.iskandern.kotin.model.ArcSteps
import dev.iskandern.kotin.model.GrowingDoubleArray
import dev.iskandern.kotin.model.GrowingIntArray
import dev.iskandern.kotin.model.Journal
import dev.iskandern.kotin.model.NO_OBJECT
import dev.iskandern.kotin.model.PointMark
import dev.iskandern.kotin.model.RawMeshBuilder
import dev.iskandern.kotin.model.SceneAttribute
import dev.iskandern.kotin.model.SceneMesh
import dev.iskandern.kotin.model.SceneObject
import dev.iskandern.kotin.model.UiText
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Раздел `PipeNetworks`: сети `PipeNetwork[name, pipeNetType]` — колодцы `Structs/Struct` и трубы `Pipes/Pipe`.
 *
 * Колодец — вертикальная призма от `elevSump` до `elevRim` на слое «Колодцы: <тип сети>» (цилиндр на [RING] граней у `CircStruct`,
 * прямоугольная у `RectStruct`, у прочих форм — вертикальный отрезок) и подпись с именем; без двух отметок — точка
 * (без отметок z = 0: сцена поднимет её на уровень поверхности). Призмы — одна сетка на тип сети.
 * Труба — осевая линия между лотками на слое «Трубы»; с `Center` — дуга через три точки, отметка — линейно по длине.
 * С сечением (диаметр, высота и ширина) и отметками — ещё и объём ([PipeTubes]): ось сечения выше лотка на половину
 * высоты, торцы открыты; одна сетка на тип сети (цвет сетки — цвет слоя, поэтому слой «Трубы: <тип>»).
 * Отметка лотка — `Invert@elev` колодца, где `refPipe` — имя трубы, иначе `elevSump`.
 *
 * Читается всё в компактные массивы; ссылки (`pntRef` центров, `refStart`/`refEnd` труб) разрешаются в `finish()`:
 * имена колодцев — таблица [LandXmlPointNames] на сеть (пул байтов, индекс при первом поиске), в ней x — номер колодца.
 */
internal class PipeNetworksSection(
  private val log: (String) -> Unit = Journal::log,
) : LandXmlSection("PipeNetworks", NAMES) {
  private val point = DoubleArray(3)
  // ось текущей трубы: x, y, z подряд
  private val axis = GrowingDoubleArray()
  private var deferred = false
  // размер сечения → линейные единицы файла; NaN — из Units не определить, работает эвристика [meters]
  private var scale = Double.NaN

  // сети
  private val networkNames = ArrayList<String>()
  private val networkTypes = ArrayList<String>()
  private val structTables = ArrayList<LandXmlPointNames>()

  // колодцы: числа по STRUCT_SIZE подряд, сеть, имя, первый лоток
  private val structs = GrowingDoubleArray()
  private val structNetwork = GrowingIntArray()
  private val structNames = ArrayList<String>()
  private val invertFrom = GrowingIntArray()

  // лотки всех колодцев подряд: отметка, труба, направление
  private val invertElev = GrowingDoubleArray()
  private val invertPipe = ArrayList<String>()
  private val invertDir = ArrayList<String>()

  // трубы: числа по PIPE_SIZE подряд, сеть, имя, концы
  private val pipes = GrowingDoubleArray()
  private val pipeNetwork = GrowingIntArray()
  private val pipeNames = ArrayList<String>()
  private val pipeStart = ArrayList<String>()
  private val pipeEnd = ArrayList<String>()

  // центры по ссылке pntRef: место x в массиве колодцев (≥ 0) или труб (−1 − место) и имя точки; редки
  private val centerRefs = ArrayList<Pair<Int, String>>()

  val structCount: Int get() = structNames.size
  val pipeCount: Int get() = pipeNames.size

  override fun read(xml: XmlTokenizer, scene: LandXmlSceneBuilder) {
    if (!deferred) {
      deferred = true
      scene.defer(LandXmlSceneBuilder.STAGE_REFERENCES) { resolve(scene) }
      scene.defer(LandXmlSceneBuilder.STAGE_CURVES) { build(scene) }
    }
    val depth = xml.depth
    while (xml.nextChild(depth)) {
      if (xml.nameId == PIPE_NETWORK) readNetwork(xml, scene) else xml.skipElement()
    }
  }

  private fun readNetwork(xml: XmlTokenizer, scene: LandXmlSceneBuilder) {
    val network = networkNames.size
    networkNames.add(xml.attrString(NAME).orEmpty())
    networkTypes.add(xml.attrString(PIPE_NET_TYPE).orEmpty())
    structTables.add(LandXmlPointNames())
    val depth = xml.depth
    while (xml.nextChild(depth)) {
      val list = xml.nameId
      if (list != STRUCTS && list != PIPES) {
        xml.skipElement()
        continue
      }
      val listDepth = xml.depth
      while (xml.nextChild(listDepth)) {
        when {
          list == STRUCTS && xml.nameId == STRUCT -> readStruct(xml, scene, network)
          list == PIPES && xml.nameId == PIPE -> readPipe(xml, network)
          else -> xml.skipElement()
        }
        scene.tick()
      }
    }
  }

  private fun readStruct(xml: XmlTokenizer, scene: LandXmlSceneBuilder, network: Int) {
    val index = structNames.size
    val name = xml.attrString(NAME).orEmpty()
    val at = structs.size
    repeat(STRUCT_SIZE) { structs.add(Double.NaN) }
    structs[at + S_RIM] = xml.attrDouble(ELEV_RIM)
    structs[at + S_SUMP] = xml.attrDouble(ELEV_SUMP)
    structs[at + S_SHAPE] = 0.0
    structNames.add(name)
    structNetwork.add(network)
    invertFrom.add(invertElev.size)
    structTables[network].add(name, index.toDouble(), 0.0, 0.0)
    val depth = xml.depth
    while (xml.nextChild(depth)) {
      when (val id = xml.nameId) {
        CENTER -> readCenter(xml, scene, at)
        CIRC_STRUCT -> {
          structs[at + S_SHAPE] = SHAPE_CIRC.toDouble()
          structs[at + S_D1] = xml.attrDouble(DIAMETER)
          xml.skipElement()
        }
        RECT_STRUCT -> {
          structs[at + S_SHAPE] = SHAPE_RECT.toDouble()
          structs[at + S_D1] = xml.attrDouble(LENGTH)
          structs[at + S_D2] = xml.attrDouble(WIDTH)
          structs[at + S_DIR] = xml.attrDouble(LENGTH_DIR)
          xml.skipElement()
        }
        INLET_STRUCT, OUTLET_STRUCT, CONNECTION -> {
          structs[at + S_SHAPE] = (SHAPE_INLET + id - INLET_STRUCT).toDouble()
          xml.skipElement()
        }
        INVERT -> {
          invertElev.add(xml.attrDouble(ELEV))
          invertPipe.add(xml.attrString(REF_PIPE).orEmpty())
          invertDir.add(xml.attrString(FLOW_DIR).orEmpty())
          xml.skipElement()
        }
        else -> xml.skipElement()
      }
    }
  }

  private fun readPipe(xml: XmlTokenizer, network: Int) {
    pipeNames.add(xml.attrString(NAME).orEmpty())
    pipeStart.add(xml.attrString(REF_START).orEmpty())
    pipeEnd.add(xml.attrString(REF_END).orEmpty())
    pipeNetwork.add(network)
    val at = pipes.size
    repeat(PIPE_SIZE) { pipes.add(Double.NaN) }
    pipes[at + P_SHAPE] = 0.0
    pipes[at + P_LENGTH] = xml.attrDouble(LENGTH)
    pipes[at + P_SLOPE] = xml.attrDouble(SLOPE)
    val depth = xml.depth
    while (xml.nextChild(depth)) {
      when (val id = xml.nameId) {
        CIRC_PIPE -> {
          pipes[at + P_SHAPE] = SHAPE_CIRC.toDouble()
          pipes[at + P_D1] = xml.attrDouble(DIAMETER)
          xml.skipElement()
        }
        EGG_PIPE, ELLI_PIPE -> {
          pipes[at + P_SHAPE] = (if (id == EGG_PIPE) SHAPE_EGG else SHAPE_ELLI).toDouble()
          pipes[at + P_D1] = xml.attrDouble(HEIGHT)
          pipes[at + P_D2] = xml.attrDouble(SPAN)
          xml.skipElement()
        }
        RECT_PIPE -> {
          pipes[at + P_SHAPE] = SHAPE_RECT.toDouble()
          pipes[at + P_D1] = xml.attrDouble(HEIGHT)
          pipes[at + P_D2] = xml.attrDouble(WIDTH)
          xml.skipElement()
        }
        CHANNEL -> {
          pipes[at + P_SHAPE] = SHAPE_CHANNEL.toDouble()
          pipes[at + P_D1] = xml.attrDouble(HEIGHT)
          pipes[at + P_D2] = xml.attrDouble(WIDTH_TOP)
          pipes[at + P_D3] = xml.attrDouble(WIDTH_BOTTOM)
          xml.skipElement()
        }
        CENTER -> readCenter(xml, null, -1 - (at + P_CX))
        else -> xml.skipElement()
      }
    }
  }

  // Center: «север восток [отметка]» (отметка не нужна) или pntRef; [place] — как в [centerRefs]
  private fun readCenter(xml: XmlTokenizer, scene: LandXmlSceneBuilder?, place: Int) {
    val ref = xml.attrString(PNT_REF)
    if (ref != null) {
      centerRefs.add(place to ref.trim())
      return
    }
    if (LandXmlCoord.readPoint(xml, point) < 2) return
    setCenter(place, point[0], point[1])
    scene?.include(point[0], point[1], 0.0)
  }

  private fun setCenter(place: Int, x: Double, y: Double) {
    val target = if (place >= 0) structs else pipes
    val at = if (place >= 0) place else -1 - place
    target[at] = x
    target[at + 1] = y
  }

  private fun resolve(scene: LandXmlSceneBuilder) {
    var missing = 0
    for ((place, name) in centerRefs) {
      if (scene.pointNames.find(name, point)) {
        setCenter(place, point[0], point[1])
        if (place >= 0) scene.include(point[0], point[1], 0.0)
      } else {
        missing++
      }
    }
    if (missing > 0) log(UiText.key("log_networks_missing", missing))
    centerRefs.clear()
  }

  private fun build(scene: LandXmlSceneBuilder) {
    if (networkNames.isEmpty()) return
    scale = scene.sectionScale
    val pipeLayer = scene.layer(PIPE_LAYER, LandXmlPalette.PIPE, LandXmlGroup.NETWORKS)
    // колодцы — слой и сетка на тип сети: цвет сетки один, а у каждого типа свой
    val types = networkTypes.map { structKey(it) }.distinct()
    var structsShown = 0
    for (type in types) {
      val layer = scene.layer(structLayer(type), LandXmlPalette.struct(type), LandXmlGroup.NETWORKS)
      val prisms = Prisms()
      for (s in 0 until structCount) {
        if (structKey(networkTypes[structNetwork[s]]) == type && buildStruct(scene, s, layer, prisms) != NO_OBJECT) structsShown++
      }
      if (prisms.triangleCount > 0) scene.addMesh(prisms.build(layer))
    }

    val sceneSize = CoordGeom.sceneSize(scene)
    val tubes = LinkedHashMap<String, PipeTubes>()
    var pipesShown = 0
    for (p in 0 until pipeCount) if (buildPipe(scene, p, pipeLayer, sceneSize, tubes)) pipesShown++
    for ((layer, mesh) in tubes) if (mesh.triangleCount > 0) scene.addMesh(mesh.build(layer))
    // в меню колодцы и трубы одной сети рядом
    for (type in types.asReversed()) scene.moveLayerAfter(tubeLayer(type), structLayer(type))
    log(UiText.key("log_networks", networkNames.size, structsShown, structCount, pipesShown, pipeCount))
  }

  // колодец → объект, призма или отрезок или точка, подпись; NO_OBJECT — нет центра
  private fun buildStruct(scene: LandXmlSceneBuilder, s: Int, layer: String, mesh: Prisms): Int {
    val at = s * STRUCT_SIZE
    val name = structNames[s]
    val x = structs[at + S_X]
    val y = structs[at + S_Y]
    if (x.isNaN() || y.isNaN()) {
      log(UiText.key("log_struct_no_center", name))
      return NO_OBJECT
    }
    val network = structNetwork[s]
    val color = LandXmlPalette.struct(networkTypes[network])
    var rim = structs[at + S_RIM]
    var sump = structs[at + S_SUMP]
    val shape = structs[at + S_SHAPE].toInt()
    val d1 = structs[at + S_D1]
    val d2 = structs[at + S_D2]

    val attributes = ArrayList<SceneAttribute>()
    addNetwork(attributes, network)
    attributes.add(SceneAttribute("@attr_shape", STRUCT_SHAPES[shape]))
    when (shape) {
      SHAPE_CIRC -> number(attributes, "@attr_diameter", d1)
      SHAPE_RECT -> {
        number(attributes, "@attr_length", d1)
        number(attributes, "@attr_width", d2)
        number(attributes, "@attr_length_direction", structs[at + S_DIR])
      }
    }
    number(attributes, "@attr_rim_elev", rim)
    number(attributes, "@attr_sump_elev", sump)
    attributes.add(SceneAttribute("X", AlignmentsSection.format(x)))
    attributes.add(SceneAttribute("Y", AlignmentsSection.format(y)))
    for (i in invertFrom[s] until invertEnd(s)) {
      val dir = invertDir[i]
      attributes.add(SceneAttribute(UiText.key("attr_invert", invertPipe[i]), AlignmentsSection.format(invertElev[i]) + if (dir.isEmpty()) "" else " ($dir)"))
    }
    val id = scene.addObject(SceneObject(STRUCT_KIND, name, layer, color, attributes))

    if (rim.isNaN() || sump.isNaN()) {
      // одна отметка — точка на ней, ни одной — на поверхности
      val z = if (!rim.isNaN()) rim else if (!sump.isNaN()) sump else 0.0
      scene.addPoint(PointMark(layer, color, x, y, z, objectId = id))
      if (name.isNotEmpty()) scene.addLabel(layer, LandXmlPalette.LABEL, x, y, z, name, objectId = id)
      return id
    }
    if (rim < sump) rim = sump.also { sump = rim }
    val size1 = if (shape == SHAPE_CIRC) diameter(d1) else meters(d1)
    val size2 = meters(d2)
    when {
      shape == SHAPE_CIRC && size1 > 0 -> mesh.cylinder(x, y, sump, rim, size1 / 2, id)
      shape == SHAPE_RECT && size1 > 0 && size2 > 0 -> {
        // направление длины — от севера по часовой стрелке, в градусах; нет — длина вдоль востока
        val dir = structs[at + S_DIR]
        val a = if (dir.isNaN()) PI / 2 else dir * PI / 180
        mesh.box(x, y, sump, rim, sin(a), cos(a), size1 / 2, size2 / 2, id)
      }
      else -> scene.lines(layer, color, flat = false).add(x, y, sump, x, y, rim, id)
    }
    if (name.isNotEmpty()) scene.addLabel(layer, LandXmlPalette.LABEL, x, y, rim, name, objectId = id)
    return id
  }

  // труба → осевая линия между лотками; false — нет колодца на конце
  private fun buildPipe(
    scene: LandXmlSceneBuilder, p: Int, layer: String, sceneSize: Double, tubes: MutableMap<String, PipeTubes>,
  ): Boolean {
    val name = pipeNames[p]
    val network = pipeNetwork[p]
    val start = findStruct(network, pipeStart[p])
    val end = findStruct(network, pipeEnd[p])
    if (start < 0 || end < 0) {
      val missing = if (start < 0) pipeStart[p] else pipeEnd[p]
      log(UiText.key("log_pipe_no_struct", name, networkNames[network], missing))
      return false
    }
    val sx = structs[start * STRUCT_SIZE + S_X]
    val sy = structs[start * STRUCT_SIZE + S_Y]
    val ex = structs[end * STRUCT_SIZE + S_X]
    val ey = structs[end * STRUCT_SIZE + S_Y]
    if (sx.isNaN() || sy.isNaN() || ex.isNaN() || ey.isNaN()) {
      log(UiText.key("log_pipe_struct_no_center", name, networkNames[network]))
      return false
    }
    val startElev = invert(start, name)
    val endElev = invert(end, name)
    // нет одной отметки — труба горизонтальна на другой; нет обеих — в плане, на уровне поверхности
    val flat = startElev.isNaN() && endElev.isNaN()
    val zs = if (flat) 0.0 else if (startElev.isNaN()) endElev else startElev
    val ze = if (flat) 0.0 else if (endElev.isNaN()) startElev else endElev
    val color = LandXmlPalette.pipe(networkTypes[network])

    val at = p * PIPE_SIZE
    val attributes = ArrayList<SceneAttribute>()
    addNetwork(attributes, network)
    val shape = pipes[at + P_SHAPE].toInt()
    attributes.add(SceneAttribute("@attr_shape", PIPE_SHAPES[shape]))
    when (shape) {
      SHAPE_CIRC -> number(attributes, "@attr_diameter", pipes[at + P_D1])
      SHAPE_EGG, SHAPE_ELLI -> {
        number(attributes, "@attr_height", pipes[at + P_D1])
        number(attributes, "@attr_span", pipes[at + P_D2])
      }
      SHAPE_RECT -> {
        number(attributes, "@attr_height", pipes[at + P_D1])
        number(attributes, "@attr_width", pipes[at + P_D2])
      }
      SHAPE_CHANNEL -> {
        number(attributes, "@attr_height", pipes[at + P_D1])
        number(attributes, "@attr_top_width", pipes[at + P_D2])
        number(attributes, "@attr_bottom_width", pipes[at + P_D3])
      }
    }
    attributes.add(SceneAttribute("@attr_start", pipeStart[p]))
    attributes.add(SceneAttribute("@attr_end", pipeEnd[p]))
    number(attributes, "@attr_start_invert", startElev)
    number(attributes, "@attr_end_invert", endElev)
    val id = scene.addObject(SceneObject(PIPE_KIND, name, layer, color, attributes))

    val cx = pipes[at + P_CX]
    val cy = pipes[at + P_CY]
    axis.clear()
    val length = if (cx.isNaN() || cy.isNaN()) {
      axisPoint(sx, sy, zs)
      axisPoint(ex, ey, ze)
      hypot(ex - sx, ey - sy)
    } else {
      arc(sx, sy, zs, cx, cy, ex, ey, ze, sceneSize)
    }
    val lines = scene.lines(layer, color, flat)
    val points = axis.size / 3
    for (i in 0 until points - 1) {
      lines.add(axis[i * 3], axis[i * 3 + 1], axis[i * 3 + 2], axis[i * 3 + 3], axis[i * 3 + 4], axis[i * 3 + 5], id)
    }
    // объём — только в 3D и пока сцена не упёрлась в лимит отрезков
    val profile = if (flat || scene.truncated) null else profile(at, sceneSize)
    if (profile != null) {
      val half = profile.v.max()
      for (i in 0 until points) axis[i * 3 + 2] += half
      val tubeLayer = scene.layer(tubeLayer(networkTypes[network]), color, LandXmlGroup.NETWORKS)
      tubes.getOrPut(tubeLayer) { PipeTubes() }.add(profile, axis, points, id)
    }
    // длина известна после построения — дописываем в атрибуты уже заведённого объекта
    attributes.add(SceneAttribute("@attr_plan_length_m", AlignmentsSection.format(length)))
    if (!startElev.isNaN() && !endElev.isNaN() && length > 0) {
      attributes.add(SceneAttribute("@attr_slope", AlignmentsSection.format((startElev - endElev) / length * 1000) + " ‰"))
    }
    number(attributes, "@attr_file_length", pipes[at + P_LENGTH])
    number(attributes, "@attr_file_slope", pipes[at + P_SLOPE])
    if (!cx.isNaN() && !cy.isNaN()) attributes.add(SceneAttribute("@attr_curved", "@value_yes"))
    return true
  }

  // Диаметр круглых колодцев и труб — в diameterUnit, остальные размеры форм (длина, ширина, высота) — в линейных
  // единицах файла, для них только эвристика [meters]. Масштаб берётся в build(): Units могут стоять после сетей.
  private fun diameter(size: Double): Double = if (scale.isNaN()) meters(size) else size * scale

  /**
   * Сечение трубы в метрах по форме и размерам; null — формы нет или размер не больше нуля (труба — только осевой линией).
   * Круг и эллипс — граней по допуску от размера сцены ([tubeFaces]); яйцевидное — эллипсом по высоте и пролёту.
   */
  private fun profile(at: Int, sceneSize: Double): PipeProfile? {
    val shape = pipes[at + P_SHAPE].toInt()
    val d1 = if (shape == SHAPE_CIRC) diameter(pipes[at + P_D1]) else meters(pipes[at + P_D1])
    val d2 = meters(pipes[at + P_D2])
    val d3 = meters(pipes[at + P_D3])
    // NaN — размера нет
    return when (shape) {
      SHAPE_CIRC -> if (d1 > 0) PipeProfile.ellipse(tubeFaces(d1, sceneSize), d1 / 2, d1 / 2) else null
      SHAPE_EGG, SHAPE_ELLI ->
        if (d1 > 0 && d2 > 0) PipeProfile.ellipse(tubeFaces(maxOf(d1, d2), sceneSize), d2 / 2, d1 / 2) else null
      SHAPE_RECT -> if (d1 > 0 && d2 > 0) PipeProfile.rect(d1, d2) else null
      SHAPE_CHANNEL ->
        if (d1 > 0 && (d2 > 0 || d3 > 0)) PipeProfile.channel(d1, if (d2 > 0) d2 else 0.0, if (d3 > 0) d3 else 0.0) else null
      else -> null
    }
  }

  private fun axisPoint(x: Double, y: Double, z: Double) {
    axis.add(x)
    axis.add(y)
    axis.add(z)
  }

  /**
   * Дуга в плане от S через M к E (M — точка на дуге, не центр) — точками в [axis]; отметка — линейно по длине
   * от [zs] до [ze]. Три точки на одной прямой — ломаная S–M–E. Возвращает длину в плане.
   */
  private fun arc(
    sx: Double, sy: Double, zs: Double, mx: Double, my: Double,
    ex: Double, ey: Double, ze: Double, sceneSize: Double,
  ): Double {
    // от S: большие координаты в квадратах теряют точность
    val bx = mx - sx
    val by = my - sy
    val cx = ex - sx
    val cy = ey - sy
    val d = 2 * (bx * cy - by * cx)
    val chord = hypot(cx, cy)
    if (abs(d) <= 1e-9 * hypot(bx, by) * chord || chord == 0.0) {
      val first = hypot(bx, by)
      val total = first + hypot(ex - mx, ey - my)
      val zm = if (total > 0) zs + (ze - zs) * first / total else zs
      axisPoint(sx, sy, zs)
      axisPoint(mx, my, zm)
      axisPoint(ex, ey, ze)
      return total
    }
    val b2 = bx * bx + by * by
    val c2 = cx * cx + cy * cy
    val ux = (cy * b2 - by * c2) / d
    val uy = (bx * c2 - cx * b2) / d
    val radius = hypot(ux, uy)
    val a0 = atan2(-uy, -ux)
    val sweepCcw = positive(atan2(cy - uy, cx - ux) - a0)
    val throughCcw = positive(atan2(by - uy, bx - ux) - a0)
    val sweep = if (throughCcw < sweepCcw) sweepCcw else sweepCcw - 2 * PI
    val steps = CoordGeom.arcSteps(sweep, radius, sceneSize)
    val c = cos(sweep / steps)
    val s = sin(sweep / steps)
    var vx = -ux
    var vy = -uy
    axisPoint(sx, sy, zs)
    for (k in 1 until steps) {
      val nx = vx * c - vy * s
      vy = vx * s + vy * c
      vx = nx
      axisPoint(sx + ux + vx, sy + uy + vy, zs + (ze - zs) * k / steps)
    }
    axisPoint(ex, ey, ze)
    return radius * abs(sweep)
  }

  private fun positive(angle: Double): Double {
    val a = angle % (2 * PI)
    return if (a < 0) a + 2 * PI else a
  }

  private fun findStruct(network: Int, name: String): Int =
    if (structTables[network].find(name, point)) point[0].toInt() else -1

  private fun invertEnd(s: Int): Int = if (s + 1 < invertFrom.size) invertFrom[s + 1] else invertElev.size

  // отметка лотка трубы [pipe] в колодце [s]: Invert с refPipe, иначе дно колодца
  private fun invert(s: Int, pipe: String): Double {
    for (i in invertFrom[s] until invertEnd(s)) {
      if (invertPipe[i] == pipe && !invertElev[i].isNaN()) return invertElev[i]
    }
    return structs[s * STRUCT_SIZE + S_SUMP]
  }

  private fun addNetwork(attributes: ArrayList<SceneAttribute>, network: Int) {
    attributes.add(SceneAttribute("@attr_network", networkNames[network]))
    if (networkTypes[network].isNotEmpty()) attributes.add(SceneAttribute("@attr_network_type", networkTypes[network]))
  }

  private fun number(attributes: ArrayList<SceneAttribute>, name: String, value: Double) {
    if (!value.isNaN()) attributes.add(SceneAttribute(name, AlignmentsSection.format(value)))
  }

  /**
   * Призмы колодцев одной сеткой: вершины и треугольники в растущих массивах, объект треугольника — сериями.
   * Каркас: у цилиндра — кольца верха и низа, у прямоугольной — рёбра граней без диагоналей.
   */
  private class Prisms {
    private val mesh = RawMeshBuilder()

    val triangleCount: Int get() = mesh.triangleCount

    fun cylinder(x: Double, y: Double, bottom: Double, top: Double, radius: Double, id: Int) {
      mesh.markObject(id)
      // кольца низа и верха: вершины боковой поверхности общие у соседних граней — цилиндр гладкий
      val first = mesh.vertexCount
      for (k in 0 until RING) mesh.vertex(x + radius * RING_COS[k], y + radius * RING_SIN[k], bottom)
      for (k in 0 until RING) mesh.vertex(x + radius * RING_COS[k], y + radius * RING_SIN[k], top)
      for (k in 0 until RING) {
        val b0 = first + k
        val b1 = first + (k + 1) % RING
        mesh.triangle(b0, b1, b1 + RING, EDGE_AB)
        mesh.triangle(b0, b1 + RING, b0 + RING, EDGE_BC)
      }
      // крышка — свои вершины (своя нормаль), рёбра крышки в каркас не идут: кольцо уже есть
      val center = mesh.vertexCount
      mesh.vertex(x, y, top)
      for (k in 0 until RING) mesh.vertex(x + radius * RING_COS[k], y + radius * RING_SIN[k], top)
      for (k in 0 until RING) mesh.triangle(center, center + 1 + k, center + 1 + (k + 1) % RING, 0)
    }

    /** Прямоугольная призма: длина вдоль ([ux], [uy]) — единичный вектор, ширина поперёк. */
    fun box(
      x: Double, y: Double, bottom: Double, top: Double, ux: Double, uy: Double,
      halfLength: Double, halfWidth: Double, id: Int,
    ) {
      mesh.markObject(id)
      val cornerX = DoubleArray(4)
      val cornerY = DoubleArray(4)
      for (k in 0 until 4) {
        val l = if (k == 0 || k == 3) -halfLength else halfLength
        val w = if (k < 2) -halfWidth else halfWidth
        cornerX[k] = x + ux * l + uy * w
        cornerY[k] = y + uy * l - ux * w
      }
      // стены и крышка — у каждой грани свои вершины: грани плоские
      for (k in 0 until 4) {
        val n = (k + 1) % 4
        quad(
          cornerX[k], cornerY[k], bottom, cornerX[n], cornerY[n], bottom,
          cornerX[n], cornerY[n], top, cornerX[k], cornerY[k], top,
        )
      }
      quad(
        cornerX[0], cornerY[0], top, cornerX[1], cornerY[1], top,
        cornerX[2], cornerY[2], top, cornerX[3], cornerY[3], top,
      )
    }

    private fun quad(
      x0: Double, y0: Double, z0: Double, x1: Double, y1: Double, z1: Double,
      x2: Double, y2: Double, z2: Double, x3: Double, y3: Double, z3: Double,
    ) {
      val first = mesh.vertexCount
      mesh.vertex(x0, y0, z0)
      mesh.vertex(x1, y1, z1)
      mesh.vertex(x2, y2, z2)
      mesh.vertex(x3, y3, z3)
      mesh.triangle(first, first + 1, first + 2, EDGE_AB or EDGE_BC)
      mesh.triangle(first, first + 2, first + 3, EDGE_BC or EDGE_CA)
    }

    fun build(layer: String): SceneMesh = mesh.build(layer, structure = true)
  }

  companion object {
    const val PIPE_LAYER = "@layer_pipes"
    const val STRUCT_KIND = "Struct"
    const val PIPE_KIND = "Pipe"

    /** Граней у круглой и эллиптической трубы: по допуску дуг от размера сцены, в этих пределах. */
    const val TUBE_MIN = 8
    const val TUBE_MAX = 24

    internal fun tubeFaces(size: Double, sceneSize: Double): Int =
      ArcSteps.perTurn(size / 2, sceneSize, TUBE_MIN, TUBE_MAX, fallback = TUBE_MAX)

    /** Слой колодцев или объёма труб — не поверхность. */
    fun isNetworkLayer(layer: String): Boolean = layer.startsWith(STRUCT_LAYER_PREFIX) || layer.startsWith(TUBE_LAYER_PREFIX)

    private const val STRUCT_LAYER_PREFIX = "@layer_structs_"
    private const val TUBE_LAYER_PREFIX = "@layer_tubes_"

    /** Тип сети для колодцев: неизвестный и пустой — ливнёвка, как в [LandXmlPalette.struct]. */
    internal fun structKey(type: String): String = when (type) {
      "sanitary", "water", "other" -> type
      else -> "storm"
    }

    /** Слой колодцев сети типа `pipeNetType`: цвет сетки — цвет слоя, у каждого типа свой. */
    fun structLayer(type: String): String = STRUCT_LAYER_PREFIX + structKey(type)

    /** Слой объёма труб сети типа `pipeNetType`: цвет сетки — цвет слоя, а у каждого типа свой цвет. */
    fun tubeLayer(type: String): String = TUBE_LAYER_PREFIX + structKey(type)

    /** Граней у цилиндра колодца. */
    const val RING = 16
    // кольцо вершин цилиндра — шаблон на все колодцы
    private val RING_COS = DoubleArray(RING) { cos(2 * PI * it / RING) }
    private val RING_SIN = DoubleArray(RING) { sin(2 * PI * it / RING) }

    private const val EDGE_AB = 1
    private const val EDGE_BC = 2
    private const val EDGE_CA = 4

    /**
     * Запасная эвристика, если в `Units` нет `diameterUnit` или единица неизвестна: размер больше этого — в
     * миллиметрах, колодцев шире 20 м не бывает. В атрибутах — как в файле.
     */
    private const val MILLIMETERS_FROM = 20.0

    internal fun meters(size: Double): Double = if (size > MILLIMETERS_FROM) size / 1000 else size

    private val NAMES = XmlNames(
      "PipeNetwork", "Structs", "Struct", "Pipes", "Pipe", "Center", "Invert",
      "CircStruct", "RectStruct", "InletStruct", "OutletStruct", "Connection",
      "CircPipe", "EggPipe", "ElliPipe", "RectPipe", "Channel",
    )
    private const val PIPE_NETWORK = 0
    private const val STRUCTS = 1
    private const val STRUCT = 2
    private const val PIPES = 3
    private const val PIPE = 4
    private const val CENTER = 5
    private const val INVERT = 6
    private const val CIRC_STRUCT = 7
    private const val RECT_STRUCT = 8
    private const val INLET_STRUCT = 9
    private const val OUTLET_STRUCT = 10
    private const val CONNECTION = 11
    private const val CIRC_PIPE = 12
    private const val EGG_PIPE = 13
    private const val ELLI_PIPE = 14
    private const val RECT_PIPE = 15
    private const val CHANNEL = 16

    // формы: 0 — не указана
    private const val SHAPE_CIRC = 1
    private const val SHAPE_RECT = 2
    private const val SHAPE_INLET = 3
    private const val SHAPE_EGG = 3
    private const val SHAPE_ELLI = 4
    private const val SHAPE_CHANNEL = 5
    private val STRUCT_SHAPES = arrayOf("@value_unspecified", "CircStruct", "RectStruct", "InletStruct", "OutletStruct", "Connection")
    private val PIPE_SHAPES = arrayOf("@value_unspecified", "CircPipe", "RectPipe", "EggPipe", "ElliPipe", "Channel")

    // раскладка колодца: центр, верх, дно, форма, размеры, направление длины
    private const val S_X = 0
    private const val S_Y = 1
    private const val S_RIM = 2
    private const val S_SUMP = 3
    private const val S_SHAPE = 4
    private const val S_D1 = 5
    private const val S_D2 = 6
    private const val S_DIR = 7
    private const val STRUCT_SIZE = 8

    // раскладка трубы: точка дуги (Center), форма, размеры, длина и уклон из файла
    private const val P_CX = 0
    private const val P_CY = 1
    private const val P_SHAPE = 2
    private const val P_D1 = 3
    private const val P_D2 = 4
    private const val P_D3 = 5
    private const val P_LENGTH = 6
    private const val P_SLOPE = 7
    private const val PIPE_SIZE = 8

    private val NAME = "name".encodeToByteArray()
    private val PIPE_NET_TYPE = "pipeNetType".encodeToByteArray()
    private val ELEV_RIM = "elevRim".encodeToByteArray()
    private val ELEV_SUMP = "elevSump".encodeToByteArray()
    private val ELEV = "elev".encodeToByteArray()
    private val REF_PIPE = "refPipe".encodeToByteArray()
    private val FLOW_DIR = "flowDir".encodeToByteArray()
    private val REF_START = "refStart".encodeToByteArray()
    private val REF_END = "refEnd".encodeToByteArray()
    private val PNT_REF = "pntRef".encodeToByteArray()
    private val DIAMETER = "diameter".encodeToByteArray()
    private val LENGTH = "length".encodeToByteArray()
    private val WIDTH = "width".encodeToByteArray()
    private val LENGTH_DIR = "lengthDir".encodeToByteArray()
    private val HEIGHT = "height".encodeToByteArray()
    private val SPAN = "span".encodeToByteArray()
    private val WIDTH_TOP = "widthTop".encodeToByteArray()
    private val WIDTH_BOTTOM = "widthBottom".encodeToByteArray()
    private val SLOPE = "slope".encodeToByteArray()
  }
}
