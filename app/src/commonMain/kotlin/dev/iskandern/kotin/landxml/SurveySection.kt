package dev.iskandern.kotin.landxml

import dev.iskandern.kotin.model.GrowingDoubleArray
import dev.iskandern.kotin.model.GrowingIntArray
import dev.iskandern.kotin.model.Journal
import dev.iskandern.kotin.model.PointMark
import dev.iskandern.kotin.model.SceneAttribute
import dev.iskandern.kotin.model.SceneObject
import dev.iskandern.kotin.model.UiText

/**
 * Раздел `Survey`: сырые полевые измерения. Рисуются линии «станция → цель» по координатам из файла, без уравнивания и
 * без пересчёта углов и расстояний. `InstrumentSetup[id, stationName, instrumentHeight]/InstrumentPoint[pntRef]` — станция,
 * `ObservationGroup` с `Backsight[setupID]/BacksightPoint` и `RawObservation/TargetPoint` — цели. Слой «Съёмка», объект
 * сцены — на станцию. Координаты точки берутся из первого, что есть: текст `InstrumentPoint`/`TargetPoint`/`BacksightPoint`
 * (заглушка «-1e20» — координат нет), `Survey/CgPoints/CgPoint[pntRef]`, затем `CgPoints` файла по имени.
 * Повторный замер той же цели с той же станции (второй круг) не рисуется. Ссылки разрешаются в `finish()`; имён-строк на замер нет.
 */
internal class SurveySection(
  private val log: (String) -> Unit = Journal::log,
) : LandXmlSection("Survey", NAMES) {
  private val refs = RefTable()
  // координаты точки по номеру имени: x, y, z (NaN — нет)
  private val refCoords = GrowingDoubleArray()
  // с какой станции (номер + 1) цель рисовалась последней — повторные круги пропускаются
  private val lastSetup = GrowingIntArray()

  private val setupIds = ArrayList<String>()
  private val setupNames = ArrayList<String>()
  private val setupHeights = ArrayList<String>()
  private val setupRefs = GrowingIntArray()
  private val setupObservations = GrowingIntArray()

  // замеры: станция, номер имени цели, координаты цели из замера (NaN — нет), глаз — z есть
  private val shotSetup = GrowingIntArray()
  private val shotRef = GrowingIntArray()
  private val shotCoords = GrowingDoubleArray()

  private val point = DoubleArray(3)
  private var scratch = ByteArray(64)
  private var deferred = false
  private var currentSetup = -1
  private var orphans = 0

  override fun read(xml: XmlTokenizer, scene: LandXmlSceneBuilder) {
    if (!deferred) {
      deferred = true
      scene.defer(LandXmlSceneBuilder.STAGE_REFERENCES) { build(scene) }
    }
    val depth = xml.depth
    while (xml.nextChild(depth)) {
      when (xml.nameId) {
        CG_POINTS -> readCgPoints(xml)
        INSTRUMENT_SETUP -> readSetup(xml)
        OBSERVATION_GROUP -> readGroup(xml)
        SURVEY_HEADER -> readHeader(xml, scene)
        else -> xml.skipElement()
      }
      scene.tick()
    }
  }

  // ePlan пишет вторую систему координат в шапку съёмки: <SurveyHeader><CoordinateSystem horizontalDatum="Local" …/>
  private fun readHeader(xml: XmlTokenizer, scene: LandXmlSceneBuilder) {
    val depth = xml.depth
    while (xml.nextChild(depth)) {
      if (xml.nameId == COORDINATE_SYSTEM) CoordinateSystemElement.read(xml).let { if (it.isNotEmpty()) scene.coordinateSystems += it }
      else xml.skipElement()
    }
  }

  private fun readCgPoints(xml: XmlTokenizer) {
    val depth = xml.depth
    while (xml.nextChild(depth)) {
      if (xml.nameId == CG_POINT) {
        val id = readRef(xml, PNT_REF)
        if (id >= 0) {
          if (readCoords(xml)) remember(id)
        } else {
          xml.skipElement()
        }
      } else {
        xml.skipElement()
      }
    }
  }

  private fun readSetup(xml: XmlTokenizer) {
    val id = xml.attrString(ID).orEmpty()
    val name = xml.attrString(STATION_NAME)?.trim().orEmpty()
    val height = xml.attrString(INSTRUMENT_HEIGHT)?.trim().orEmpty()
    var ref = -1
    val depth = xml.depth
    while (xml.nextChild(depth)) {
      if (xml.nameId == INSTRUMENT_POINT) {
        ref = readRef(xml, PNT_REF)
        if (ref >= 0) {
          if (readCoords(xml)) remember(ref)
        } else {
          xml.skipElement()
        }
      } else {
        xml.skipElement()
      }
    }
    setupIds.add(id)
    setupNames.add(name)
    setupHeights.add(height)
    setupRefs.add(ref)
    setupObservations.add(0)
  }

  private fun readGroup(xml: XmlTokenizer) {
    currentSetup = -1
    val depth = xml.depth
    while (xml.nextChild(depth)) {
      when (xml.nameId) {
        BACKSIGHT -> readBacksight(xml)
        RAW_OBSERVATION -> readObservation(xml)
        else -> xml.skipElement()
      }
    }
    currentSetup = -1
  }

  private fun readBacksight(xml: XmlTokenizer) {
    val setupId = xml.attrString(SETUP_ID)
    if (setupId != null) currentSetup = setupIds.indexOf(setupId)
    val depth = xml.depth
    while (xml.nextChild(depth)) {
      if (xml.nameId == BACKSIGHT_POINT) readTarget(xml) else xml.skipElement()
    }
  }

  private fun readObservation(xml: XmlTokenizer) {
    val depth = xml.depth
    while (xml.nextChild(depth)) {
      if (xml.nameId == TARGET_POINT) readTarget(xml) else xml.skipElement()
    }
  }

  // цель: имя — pntRef или name; координаты — текст, если они настоящие
  private fun readTarget(xml: XmlTokenizer) {
    var id = readRef(xml, PNT_REF)
    if (id < 0) id = readRef(xml, NAME)
    val hasCoords = readCoords(xml)
    if (id < 0) return
    if (hasCoords) remember(id)
    if (currentSetup < 0) {
      orphans++
      return
    }
    if (lastSetup[id] == currentSetup + 1) return
    lastSetup[id] = currentSetup + 1
    setupObservations[currentSetup] = setupObservations[currentSetup] + 1
    shotSetup.add(currentSetup)
    shotRef.add(id)
    for (k in 0 until 3) shotCoords.add(if (hasCoords) point[k] else Double.NaN)
  }

  // имя из атрибута [attr] в таблицу; -1 — атрибута нет или пусто
  private fun readRef(xml: XmlTokenizer, attr: ByteArray): Int {
    if (!xml.findAttr(attr)) return -1
    var start = xml.valueStart
    var end = xml.valueEnd
    var buf = xml.valueBuffer
    if (!isPlain(buf, start, end)) {
      val bytes = xml.attrString(attr).orEmpty().trim().encodeToByteArray()
      if (bytes.size > scratch.size) scratch = ByteArray(bytes.size * 2)
      bytes.copyInto(scratch)
      buf = scratch
      start = 0
      end = bytes.size
    }
    while (start < end && buf[start] <= SPACE) start++
    while (end > start && buf[end - 1] <= SPACE) end--
    if (start == end) return -1
    val id = refs.intern(buf, start, end)
    while (refCoords.size < (id + 1) * 3) {
      refCoords.add(Double.NaN)
      refCoords.add(Double.NaN)
      refCoords.add(Double.NaN)
    }
    while (lastSetup.size <= id) lastSetup.add(0)
    return id
  }

  // текст элемента в [point]; false — координат нет (пусто, одно число или заглушка «-1e20»)
  private fun readCoords(xml: XmlTokenizer): Boolean {
    val read = try {
      LandXmlCoord.readPoint(xml, point)
    } catch (e: XmlFormatException) {
      0
    }
    if (read < 2 || !valid(point[0]) || !valid(point[1])) return false
    point[2] = if (read == 3 && valid(point[2])) point[2] else Double.NaN
    return true
  }

  // точка, прочитанная в [point], — координаты имени, если их ещё не было
  private fun remember(id: Int) {
    if (!refCoords[id * 3].isNaN()) return
    refCoords[id * 3] = point[0]
    refCoords[id * 3 + 1] = point[1]
    refCoords[id * 3 + 2] = point[2]
  }

  private fun build(scene: LandXmlSceneBuilder) {
    if (setupIds.isEmpty()) return
    // имена без координат в замерах — по CgPoints файла
    var missing = 0
    for (id in 0 until refs.size) {
      if (!refCoords[id * 3].isNaN()) continue
      if (refs.find(id, scene.pointNames, point)) {
        refCoords[id * 3] = point[0]
        refCoords[id * 3 + 1] = point[1]
        refCoords[id * 3 + 2] = point[2].takeIf { it != 0.0 } ?: Double.NaN
      }
    }
    val layer = scene.layer(LAYER, LandXmlPalette.SURVEY, LandXmlGroup.POINTS)
    val color = LandXmlPalette.SURVEY
    val flat = scene.lines(layer, color, flat = true)
    val elevated = scene.lines(layer, color, flat = false)
    val objectIds = IntArray(setupIds.size) { -1 }
    var segments = 0
    var stations = 0
    for (i in 0 until shotSetup.size) {
      val s = shotSetup[i]
      val from = setupRefs[s]
      if (from < 0 || refCoords[from * 3].isNaN()) continue
      var tx = shotCoords[i * 3]
      var ty = shotCoords[i * 3 + 1]
      var tz = shotCoords[i * 3 + 2]
      if (tx.isNaN()) {
        val t = shotRef[i]
        tx = refCoords[t * 3]
        ty = refCoords[t * 3 + 1]
        tz = refCoords[t * 3 + 2]
      }
      if (tx.isNaN()) {
        missing++
        continue
      }
      if (objectIds[s] < 0) {
        objectIds[s] = station(scene, s, layer, color, from)
        stations++
      }
      val fx = refCoords[from * 3]
      val fy = refCoords[from * 3 + 1]
      val fz = refCoords[from * 3 + 2]
      // отметка есть у обоих концов — линия в пространстве, иначе плоская (её сажает посадка)
      if (fz.isNaN() || tz.isNaN()) flat.add(fx, fy, 0.0, tx, ty, 0.0, objectIds[s])
      else elevated.add(fx, fy, fz, tx, ty, tz, objectIds[s])
      segments++
    }
    log(UiText.key("log_survey", stations, setupIds.size, segments))
    if (missing > 0) log(UiText.key("log_survey_missing", missing))
    if (orphans > 0) log(UiText.key("log_survey_orphans", orphans))
  }

  // объект сцены, значок и подпись станции
  private fun station(scene: LandXmlSceneBuilder, s: Int, layer: String, color: Int, ref: Int): Int {
    val name = setupNames[s].ifEmpty { setupIds[s] }
    val attributes = ArrayList<SceneAttribute>()
    attributes.add(SceneAttribute("id", setupIds[s]))
    if (setupNames[s].isNotEmpty()) attributes.add(SceneAttribute("stationName", setupNames[s]))
    if (setupHeights[s].isNotEmpty()) attributes.add(SceneAttribute("instrumentHeight", setupHeights[s]))
    attributes.add(SceneAttribute("@attr_observations", setupObservations[s].toString()))
    val id = scene.addObject(SceneObject(KIND, name, layer, color, attributes))
    val x = refCoords[ref * 3]
    val y = refCoords[ref * 3 + 1]
    val z = refCoords[ref * 3 + 2]
    val flat = z.isNaN()
    scene.addPoint(PointMark(layer, color, x, y, if (flat) 0.0 else z, objectId = id), flat)
    scene.addPointLabel(layer, x, y, if (flat) 0.0 else z, name, hasElevation = !flat, objectId = id, flat = flat)
    return id
  }

  // обычный ASCII без сущностей: байты те же, что дала бы строка в UTF-8
  private fun isPlain(buf: ByteArray, start: Int, end: Int): Boolean {
    for (i in start until end) if (buf[i] < 0 || buf[i] == AMP) return false
    return true
  }

  private fun valid(v: Double) = v > -LIMIT && v < LIMIT

  /** Имена точек Survey без строк: пул байтов и таблица с открытой адресацией; номер имени — порядок появления. */
  private class RefTable {
    private var pool = ByteArray(256)
    private var poolSize = 0
    private val starts = GrowingIntArray()
    private var table = IntArray(64)
    val size: Int get() = starts.size

    fun intern(buf: ByteArray, start: Int, end: Int): Int {
      var mask = table.size - 1
      var slot = hash(buf, start, end) and mask
      while (true) {
        val entry = table[slot]
        if (entry == 0) break
        if (same(entry - 1, buf, start, end)) return entry - 1
        slot = (slot + 1) and mask
      }
      val id = starts.size
      if (poolSize + end - start > pool.size) pool = pool.copyOf(maxOf(pool.size * 2, poolSize + end - start))
      buf.copyInto(pool, poolSize, start, end)
      starts.add(poolSize)
      poolSize += end - start
      table[slot] = id + 1
      if ((id + 1) * 2 > table.size) {
        // вдвое больше и пересборка
        table = IntArray(table.size * 2)
        mask = table.size - 1
        for (e in 0..id) {
          var s = hash(pool, starts[e], endOf(e)) and mask
          while (table[s] != 0) s = (s + 1) and mask
          table[s] = e + 1
        }
      }
      return id
    }

    fun find(id: Int, names: LandXmlPointNames, out: DoubleArray): Boolean = names.find(pool, starts[id], endOf(id), out)

    private fun endOf(e: Int) = if (e + 1 < starts.size) starts[e + 1] else poolSize

    private fun same(e: Int, buf: ByteArray, start: Int, end: Int): Boolean {
      val from = starts[e]
      if (endOf(e) - from != end - start) return false
      for (i in 0 until end - start) if (pool[from + i] != buf[start + i]) return false
      return true
    }

    private fun hash(buf: ByteArray, start: Int, end: Int): Int {
      var h = -0x7ee3623b
      for (i in start until end) h = (h xor buf[i].toInt()) * 0x01000193
      return h xor (h ushr 15)
    }
  }

  companion object {
    const val LAYER = "@layer_survey"
    const val KIND = "InstrumentSetup"

    // координаты по модулю больше — заглушка «нет значения» (Civil 3D пишет -1e20)
    private const val LIMIT = 1e15
    private const val SPACE: Byte = 0x20
    private const val AMP: Byte = 0x26

    private val NAMES = XmlNames(
      "Survey", "CgPoints", "CgPoint", "InstrumentSetup", "InstrumentPoint", "ObservationGroup", "Backsight",
      "BacksightPoint", "RawObservation", "TargetPoint", "SurveyHeader", CoordinateSystemElement.ELEMENT,
    )
    private const val CG_POINTS = 1
    private const val CG_POINT = 2
    private const val INSTRUMENT_SETUP = 3
    private const val INSTRUMENT_POINT = 4
    private const val OBSERVATION_GROUP = 5
    private const val BACKSIGHT = 6
    private const val BACKSIGHT_POINT = 7
    private const val RAW_OBSERVATION = 8
    private const val TARGET_POINT = 9
    private const val SURVEY_HEADER = 10
    private const val COORDINATE_SYSTEM = 11

    private val ID = "id".encodeToByteArray()
    private val NAME = "name".encodeToByteArray()
    private val PNT_REF = "pntRef".encodeToByteArray()
    private val SETUP_ID = "setupID".encodeToByteArray()
    private val STATION_NAME = "stationName".encodeToByteArray()
    private val INSTRUMENT_HEIGHT = "instrumentHeight".encodeToByteArray()
  }
}
