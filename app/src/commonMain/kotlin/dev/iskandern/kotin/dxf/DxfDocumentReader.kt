package dev.iskandern.kotin.dxf

import dev.iskandern.kotin.io.ByteArrayInput
import dev.iskandern.kotin.io.ByteInput
import dev.iskandern.kotin.io.PrefixedInput
import dev.iskandern.kotin.io.readAllBytes
import dev.iskandern.kotin.model.ALPHA_OPAQUE
import dev.iskandern.kotin.model.GrowingDoubleArray
import dev.iskandern.kotin.model.GrowingIntArray
import dev.iskandern.kotin.model.LayerInfo
import dev.iskandern.kotin.model.MeshBuilder
import dev.iskandern.kotin.model.UiText
import dev.iskandern.kotin.model.toDegrees
import dev.iskandern.kotin.model.toRadians
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/** `$PDMODE` в файле не задан: точки рисуются прежним крестом. */
const val POINT_MODE_UNSET = -1

/** Выравнивание по вертикали перевёрнутого текста «снизу»: строка под точкой, ниже на глубину подстрочных. */
const val TEXT_ALIGN_UNDER_DESCENT = 4

/**
 * Прочитанный DXF: таблица слоёв, сущности модели, определения блоков и сетки из 3DFACE модели.
 * Грани модели сразу складываются в [faceMeshes] (у больших TIN их миллионы — объекты не создаются).
 */
class DxfDocument(
  val layers: Map<String, LayerInfo>,
  val entities: List<DxfEntity>,
  val blocks: Map<String, DxfBlock>,
  val faceMeshes: Map<String, MeshBuilder>,
  /** Слои в порядке первого появления среди сущностей модели. */
  val layerOrder: List<String>,
  /** `$PDMODE`: вид значка POINT (0 — точка, [POINT_MODE_UNSET] — не задан, 2 — «+», 3 — «×», 4 — чёрточка вверх, +32 круг, +64 квадрат). */
  val pointMode: Int = POINT_MODE_UNSET,
  /** `$PDSIZE`: 0 — по умолчанию, > 0 — размер в единицах чертежа, < 0 — процент высоты экрана. */
  val pointSize: Double = 0.0,
  /** Типы линий по имени в верхнем регистре. */
  val lineTypes: Map<String, DxfLineType> = emptyMap(),
  /** `$LTSCALE`: общий масштаб типов линий. */
  val lineTypeScale: Double = 1.0,
  /**
   * Стили текста (таблица STYLE) по имени в верхнем регистре; применены к TEXT/MTEXT при чтении. Снаружи читаются
   * только тестами: конвейер ищет стили по handle ([textStylesByHandle]).
   */
  val textStyles: Map<String, DxfTextStyle> = emptyMap(),
  /** Объекты секции OBJECTS по handle (код 5, в верхнем регистре): MLINESTYLE и MLEADERSTYLE. */
  val objects: Map<String, DxfObject> = emptyMap(),
  /** Сохранённый вид чертежа (VPORT `*Active`); null — записи нет или высота не задана. */
  val savedView: DxfSavedView? = null,
  /** Те же стили по handle (код 5, в верхнем регистре) — для текста в типах линий. */
  val textStylesByHandle: Map<String, DxfTextStyle> = emptyMap(),
  /** Рамка WIPEOUT видна (WIPEOUTVARIABLES, код 70); объекта нет — не видна. */
  val wipeoutFrame: Boolean = false,
  /** `$LWDISPLAY` (код 290): показывать веса линий; переменной нет — да. */
  val lineWeightDisplay: Boolean = true,
  /** Листы (кроме модели) по порядку вкладок, с сущностями и видовыми экранами своего пространства. */
  val layouts: List<DxfLayout> = emptyList(),
  /** `$INSUNITS`: единицы модели (0 — не заданы, 4 — мм, 6 — м, см. [insUnitsMm]). */
  val insUnits: Int = 0,
) {
  /** Стиль MLINE: по handle, иначе по имени, иначе Standard из файла или стандартный. */
  fun mlineStyle(handle: String?, name: String): DxfMLineStyle {
    (handle?.let { objects[it.uppercase()] } as? DxfMLineStyle)?.let { return it }
    val styles = objects.values.filterIsInstance<DxfMLineStyle>()
    return styles.firstOrNull { it.name.equals(name, ignoreCase = true) }
      ?: styles.firstOrNull { it.name.equals(DxfMLineStyle.STANDARD.name, ignoreCase = true) }
      ?: DxfMLineStyle.STANDARD
  }
}

/** Вид, с которым автор сохранил чертёж: центр в мировых X, Y и высота охвата (единицы чертежа). */
class DxfSavedView(val centerX: Double, val centerY: Double, val height: Double)

/**
 * Потоковое чтение ASCII DXF: HEADER (кодовая страница, вид точек), таблица LAYER, BLOCKS и ENTITIES.
 * Поддержаны 3DFACE, LINE, XLINE, RAY, LWPOLYLINE, POLYLINE (2D, 3D, polyface), MESH, LEADER, MULTILEADER (стиль MLEADERSTYLE — из OBJECTS), CIRCLE, ARC, ELLIPSE, SPLINE, SOLID, TRACE,
 * WIPEOUT, HATCH (заливка, штриховка, градиент), MPOLYGON, MLINE (стили MLINESTYLE — из OBJECTS), POINT, TEXT, MTEXT, INSERT/MINSERT с ATTRIB,
 * TOLERANCE, а также DIMENSION,
 * ARC_DIMENSION, LARGE_RADIAL_DIMENSION и ACAD_TABLE (через их анонимные блоки), грани 3DSOLID, REGION, BODY и SURFACE (SAT, SAB).
 * Сущности листов (код 67 = 1, блоки `*Paper_Space…`) и VIEWPORT собираются в листы [DxfLayout] по объектам LAYOUT.
 * Остальное пропускается. Разбор пар — [DxfTokenizer] (ASCII) или [DxfBinaryTokenizer] (бинарный DXF).
 */
class DxfDocumentReader(input: ByteInput) {

  private val decoder = DxfStringDecoder()
  private val names = DxfNameCache(decoder)
  private val tokens: DxfTokenSource = openTokens(input)

  private val layers = LinkedHashMap<String, LayerInfo>()
  private val entities = ArrayList<DxfEntity>()
  private val blocks = HashMap<String, DxfBlock>()
  private val faceMeshes = LinkedHashMap<String, MeshBuilder>()
  private val layerOrder = LinkedHashSet<String>()
  private var entitiesSinceProgress = 0
  private var onProgress: (Long) -> Unit = {}
  private var pointMode = POINT_MODE_UNSET
  private var pointSize = 0.0
  private var lineTypeScale = 1.0
  private var lineWeightDisplay = true
  private var insUnits = 0
  // handle записи LAYER (в верхнем регистре) → имя: по нему VIEWPORT замораживает слои
  private val layerNames = HashMap<String, String>()
  // $ACADVER: AC1024 — R2010
  private var acadVersion = ""
  private val lineTypes = HashMap<String, DxfLineType>()
  private val textStyles = HashMap<String, DxfTextStyle>()
  private val textStylesByHandle = HashMap<String, DxfTextStyle>()
  private val objects = HashMap<String, DxfObject>()
  // порядок прорисовки: записи блоков (handle → имя) и SORTENTSTABLE по handle записи блока-владельца
  private val blockRecords = HashMap<Long, String>()
  private val sortTables = HashMap<Long, HashMap<Long, Long>>()
  // handle (в верхнем регистре) → имя: записи блоков и типы линий, на них ссылается MULTILEADER
  private val blockRecordNames = HashMap<String, String>()
  private val lineTypeNames = HashMap<String, String>()
  // MULTILEADER ждут стиля из OBJECTS (секция после ENTITIES): дочитываются в конце
  private val pendingLeaders = ArrayList<PendingMLeader>()
  private var savedView: DxfSavedView? = null
  private var wipeoutFrame = false
  // размерные стили (DIMTXT, DIMGAP, DIMSCALE, DIMASZ) — для TOLERANCE и стрелок LEADER; по умолчанию — из заголовка
  private val dimStyles = HashMap<String, DoubleArray>()
  private val headerDimStyle = doubleArrayOf(DEFAULT_DIMTXT, DEFAULT_DIMGAP, 1.0, DEFAULT_DIMASZ)
  // размер стрелки стилей MLEADERSTYLE по handle (код 44)
  private val mleaderArrowSizes = HashMap<String, Double>()
  // стрелки MULTILEADER без своего размера: стиль идёт в OBJECTS после сущностей, размер ставится в конце чтения
  private val pendingArrows = ArrayList<Pair<DxfEntity.Solid, String?>>()
  // тела без SAT (R2013+) по handle: их SAB лежит в секции ACDSDATA после сущностей
  private val sabBodies = HashMap<Long, DxfEntity.Body>()

  // текущая сущность модели или блока: грани модели идут прямо в сетку
  private var inModelSpace = false
  // листы: объекты LAYOUT, содержимое блоков `*Paper_Space…` по имени в верхнем регистре и сущности ENTITIES с 67 = 1
  // (активный лист); [paper] — куда идут сущности листа сейчас, null — листа нет (такие сущности отбрасываются)
  private val layoutRecords = ArrayList<DxfLayout>()
  private val paperSpaces = LinkedHashMap<String, PaperSpace>()
  private val activePaper = PaperSpace()
  private var paper: PaperSpace? = null

  fun read(onProgress: (bytesRead: Long) -> Unit = {}): DxfDocument {
    this.onProgress = onProgress
    while (tokens.next()) {
      if (tokens.code != 0) continue
      if (tokens.valueEquals(EOF)) break
      if (!tokens.valueEquals(SECTION)) continue
      advance()
      if (tokens.code != 2) continue
      when {
        tokens.valueEquals(HEADER) -> readHeader()
        tokens.valueEquals(TABLES) -> readTables()
        tokens.valueEquals(BLOCKS) -> readBlocks()
        tokens.valueEquals(ENTITIES) -> readModelEntities()
        tokens.valueEquals(OBJECTS) -> readObjects()
        tokens.valueEquals(ACDSDATA) -> if (sabBodies.isNotEmpty()) readAcdsData()
      }
    }
    for (leader in pendingLeaders) resolveMultiLeader(leader)
    for ((arrow, style) in pendingArrows) scaleArrow(arrow, style?.let { mleaderArrowSizes[it.uppercase()] } ?: DEFAULT_DIMASZ)
    applyDrawOrder()
    onProgress(tokens.bytesRead)
    return DxfDocument(
      layers, entities, blocks, faceMeshes, layerOrder.toList(), pointMode, pointSize, lineTypes, lineTypeScale, textStyles, objects,
      savedView, textStylesByHandle, wipeoutFrame, lineWeightDisplay, collectLayouts(), insUnits,
    )
  }

  private class PaperSpace {
    val entities = ArrayList<DxfEntity>()
    val viewports = ArrayList<DxfViewport>()
    val isEmpty: Boolean get() = entities.isEmpty() && viewports.isEmpty()
  }

  /**
   * Листы по объектам LAYOUT: пространство — блок записи 330. Активный лист хранит сущности в ENTITIES (код 67 = 1),
   * его блок `*Paper_Space` обычно пуст: если в ENTITIES что-то есть, берётся оно. Пространства без LAYOUT (R12)
   * с содержимым становятся листами по имени блока.
   */
  private fun collectLayouts(): List<DxfLayout> {
    if (!activePaper.isEmpty) paperSpaces[PAPER_SPACE.uppercase()] = activePaper
    val result = ArrayList<DxfLayout>()
    for (layout in layoutRecords) {
      val block = blockRecords[layout.blockRecord] ?: continue
      if (block.equals(MODEL_SPACE, ignoreCase = true)) continue
      paperSpaces.remove(block.uppercase())?.let {
        layout.entities = it.entities
        layout.viewports = it.viewports
      }
      result.add(layout)
    }
    for ((block, space) in paperSpaces) {
      if (space.isEmpty) continue
      val suffix = block.removePrefix(PAPER_SPACE.uppercase())
      result.add(
        DxfLayout(if (suffix.isEmpty()) "@sheet" else UiText.key("sheet_n", suffix), Int.MAX_VALUE).also {
          it.entities = space.entities
          it.viewports = space.viewports
        },
      )
    }
    result.sortBy { it.tabOrder }
    return result
  }

  // ---------- секции ----------

  private fun readHeader() {
    var variable = ""
    while (true) {
      advance()
      when (tokens.code) {
        0 -> if (tokens.valueEquals(ENDSEC)) return
        9 -> variable = tokens.valueAsAscii().trim()
        3 -> if (variable == "\$DWGCODEPAGE") decoder.setCodePage(tokens.valueAsAscii())
        1 -> if (variable == "\$ACADVER") acadVersion = tokens.valueAsAscii().trim()
        290 -> if (variable == "\$LWDISPLAY") lineWeightDisplay = (tokens.intOrNull() ?: 1) != 0
        70 -> when (variable) {
          "\$PDMODE" -> pointMode = tokens.intOrNull() ?: POINT_MODE_UNSET
          "\$INSUNITS" -> insUnits = tokens.intOrNull() ?: 0
        }
        40 -> when (variable) {
          "\$PDSIZE" -> pointSize = tokens.double()
          "\$LTSCALE" -> lineTypeScale = tokens.double().takeIf { it > 0 } ?: 1.0
          "\$DIMTXT" -> headerDimStyle[DIM_TXT] = tokens.double()
          "\$DIMGAP" -> headerDimStyle[DIM_GAP] = tokens.double()
          "\$DIMSCALE" -> headerDimStyle[DIM_SCALE] = tokens.double()
          "\$DIMASZ" -> headerDimStyle[DIM_ASZ] = tokens.double()
        }
      }
    }
  }

  private fun readTables() {
    advance()
    while (!isAt(ENDSEC)) {
      if (isAt(TABLE)) {
        readTable()
        continue
      }
      advance()
    }
  }

  // на выходе текущая пара — следующая после ENDTAB
  private fun readTable() {
    advance()
    val isLayerTable = tokens.code == 2 && tokens.valueEquals(LAYER)
    val isLineTypeTable = tokens.code == 2 && tokens.valueEquals(LTYPE)
    val isStyleTable = tokens.code == 2 && tokens.valueEquals(STYLE)
    val isBlockRecordTable = tokens.code == 2 && tokens.valueEquals(BLOCK_RECORD)
    val isDimStyleTable = tokens.code == 2 && tokens.valueEquals(DIMSTYLE)
    val isViewportTable = tokens.code == 2 && tokens.valueEquals(VPORT)
    while (!isAt(ENDTAB)) {
      if (isBlockRecordTable && isAt(BLOCK_RECORD)) {
        readBlockRecord()
        continue
      }
      if (isViewportTable && isAt(VPORT)) {
        readViewport()
        continue
      }
      if (isDimStyleTable && isAt(DIMSTYLE)) {
        readDimStyle()
        continue
      }
      if (isStyleTable && isAt(STYLE)) {
        val (handle, style) = readTextStyle()
        textStyles[style.name.uppercase()] = style
        if (handle.isNotEmpty()) textStylesByHandle[handle.uppercase()] = style
        continue
      }
      if (isLayerTable && isAt(LAYER)) {
        val layer = readLayer()
        layers[layer.name] = layer
        continue
      }
      if (isLineTypeTable && isAt(LTYPE)) {
        val lineType = readLineType()
        lineTypes[lineType.name.uppercase()] = lineType
        continue
      }
      advance()
    }
    advance()
  }

  private fun readLayer(): LayerInfo {
    var name = ""
    var color = DxfColors.WHITE_ACI
    var flags = 0
    var trueColor: Int? = null
    var lineType = "CONTINUOUS"
    var lineWeight = LINE_WEIGHT_DEFAULT
    var alpha = ALPHA_OPAQUE
    // прозрачность слоя — в XDATA приложения AcCmTransparency
    var transparencyApp = false
    var handle = ""
    while (true) {
      advance()
      when (tokens.code) {
        0 -> {
          if (handle.isNotEmpty()) layerNames[handle.uppercase()] = name
          return LayerInfo(name, color, flags, trueColor, lineType, lineWeight, alpha)
        }
        5 -> handle = tokens.valueAsAscii().trim()
        1001 -> transparencyApp = tokens.valueEquals(AC_CM_TRANSPARENCY)
        1071 -> if (transparencyApp) alpha = tokens.intOrNull()?.let { dxfTransparencyAlpha(it) } ?: alpha
        370 -> lineWeight = tokens.intOrNull() ?: lineWeight
        6 -> lineType = layerName()
        2 -> name = string()
        62 -> color = tokens.intOrNull() ?: color
        70 -> flags = tokens.intOrNull() ?: flags
        420 -> trueColor = tokens.intOrNull()
      }
    }
  }

  // запись *Active: центр 12/22 (в системе вида), направление взгляда 16/26/36, цель 17/27/37, высота 40.
  // Для вида не сверху центр переводится в мировые координаты через цель и оси вида (правило произвольной оси)
  private fun readViewport() {
    var name = ""
    var cx = 0.0
    var cy = 0.0
    var height = 0.0
    var zx = 0.0
    var zy = 0.0
    var zz = 1.0
    var tx = 0.0
    var ty = 0.0
    while (true) {
      advance()
      when (tokens.code) {
        0 -> break
        2 -> name = string()
        12 -> cx = tokens.double()
        22 -> cy = tokens.double()
        40 -> height = tokens.double()
        16 -> zx = tokens.double()
        26 -> zy = tokens.double()
        36 -> zz = tokens.double()
        17 -> tx = tokens.double()
        27 -> ty = tokens.double()
      }
    }
    if (savedView != null || !name.equals("*ACTIVE", ignoreCase = true) || !(height > 0)) return
    val len = sqrt(zx * zx + zy * zy + zz * zz)
    if (len < 1e-9) return
    zx /= len; zy /= len; zz /= len
    if (abs(zx) < 1.0 / 64 && abs(zy) < 1.0 / 64) {
      // взгляд по вертикали: оси вида совпадают с мировыми (снизу — зеркально по X); начало системы вида — цель
      savedView = if (zz > 0) DxfSavedView(tx + cx, ty + cy, height) else DxfSavedView(tx - cx, ty + cy, height)
      return
    }
    // X вида = (0,0,1) × Z, Y вида = Z × X
    val xl = sqrt(zx * zx + zy * zy)
    val xx = -zy / xl
    val xy = zx / xl
    val yx = -zz * xy
    val yy = zz * xx
    savedView = DxfSavedView(tx + cx * xx + cy * yx, ty + cx * xy + cy * yy, height)
  }

  // запись блока: handle (5) → имя (2); по handle на блок ссылаются MULTILEADER и SORTENTSTABLE
  private fun readBlockRecord() {
    var handle = ""
    var handleValue = 0L
    var name = ""
    while (true) {
      advance()
      when (tokens.code) {
        0 -> break
        5 -> {
          handle = tokens.valueAsAscii().trim()
          handleValue = handle()
        }
        2 -> name = string()
      }
    }
    if (handle.isNotEmpty()) blockRecordNames[handle.uppercase()] = name
    if (handleValue != 0L) blockRecords[handleValue] = name
  }

  // элементы паттерна — код 49; за ним 74 (1 — поворот абсолютный, 2 — текст, 4 — фигура) и поля текста/фигуры
  private fun readLineType(): DxfLineType {
    var name = ""
    var handle = ""
    val pattern = GrowingDoubleArray()
    val embedded = ArrayList<DxfLineTypeText?>()
    var hasEmbedded = false
    // поля текущего элемента
    var kind = 0
    var text = ""
    var style = ""
    var height = 0.0
    var rotation = 0.0
    var dx = 0.0
    var dy = 0.0
    fun flush() {
      if (kind and (LTYPE_TEXT or LTYPE_SHAPE) == 0 || embedded.isEmpty()) return
      embedded[embedded.size - 1] = if (kind and LTYPE_TEXT != 0) {
        DxfLineTypeText(text, style, height, rotation, kind and LTYPE_ABSOLUTE != 0, dx, dy)
      } else {
        DxfLineTypeText.SHAPE
      }
      hasEmbedded = true
    }
    while (true) {
      advance()
      when (tokens.code) {
        0 -> {
          flush()
          if (handle.isNotEmpty()) lineTypeNames[handle.uppercase()] = name
          return DxfLineType(name, pattern.toArray(), if (hasEmbedded) embedded.toTypedArray() else null)
        }
        5 -> handle = tokens.valueAsAscii().trim()
        2 -> name = string()
        49 -> {
          flush()
          pattern.add(tokens.double())
          embedded.add(null)
          kind = 0; text = ""; style = ""; height = 0.0; rotation = 0.0; dx = 0.0; dy = 0.0
        }
        74 -> kind = tokens.intOrNull() ?: 0
        9 -> text = string()
        340 -> style = tokens.valueAsAscii().trim()
        46 -> height = tokens.double()
        50 -> rotation = tokens.double()
        44 -> dx = tokens.double()
        45 -> dy = tokens.double()
      }
    }
  }

  // шрифт — файл (3) или гарнитура TTF (XDATA ACAD, 1000) → открытая замена; big font (4) не читаем
  private fun readTextStyle(): Pair<String, DxfTextStyle> {
    var handle = ""
    var name = ""
    var height = 0.0
    var width = 1.0
    var oblique = 0.0
    var file = ""
    var family = ""
    var xdataApp = ""
    while (true) {
      advance()
      when (tokens.code) {
        0 -> return handle to DxfTextStyle(name, height, if (width > 0) width else 1.0, oblique, dxfFont(file, family))
        5 -> handle = tokens.valueAsAscii().trim()
        2 -> name = string()
        3 -> file = string()
        40 -> height = tokens.double()
        41 -> width = tokens.double()
        50 -> oblique = tokens.double()
        1001 -> xdataApp = string()
        1000 -> if (xdataApp.equals("ACAD", ignoreCase = true) && family.isEmpty()) family = string()
      }
    }
  }

  // в таблице DIMSTYLE 140 — DIMTXT, 147 — DIMGAP, 40 — DIMSCALE, 41 — DIMASZ; незаданные — из заголовка
  private fun readDimStyle() {
    var name = ""
    val values = headerDimStyle.copyOf()
    while (true) {
      advance()
      when (tokens.code) {
        0 -> {
          dimStyles[name.uppercase()] = values
          return
        }
        2 -> name = string()
        140 -> values[DIM_TXT] = tokens.double()
        147 -> values[DIM_GAP] = tokens.double()
        40 -> values[DIM_SCALE] = tokens.double()
        41 -> values[DIM_ASZ] = tokens.double()
      }
    }
  }

  /** Стиль по имени (код 7); нет такого — Standard. */
  private fun textStyle(name: String?): DxfTextStyle =
    textStyles[(name ?: "STANDARD").uppercase()] ?: textStyles["STANDARD"] ?: DxfTextStyle.STANDARD

  private fun readBlocks() {
    advance()
    while (!isAt(ENDSEC)) {
      if (isAt(BLOCK)) {
        val block = readBlock()
        if (block != null) blocks[block.name.uppercase()] = block
        continue
      }
      advance()
    }
  }

  // на выходе текущая пара — первая после ENDBLK
  private fun readBlock(): DxfBlock? {
    var name = ""
    var bx = 0.0
    var by = 0.0
    var bz = 0.0
    while (true) {
      advance()
      when (tokens.code) {
        0 -> break
        2 -> name = string()
        10 -> bx = tokens.double()
        20 -> by = tokens.double()
        30 -> bz = tokens.double()
      }
    }

    inModelSpace = false
    // пространства листов — не блоки: их сущности идут в свой лист
    val isPaper = name.startsWith(PAPER_SPACE, ignoreCase = true)
    paper = if (isPaper) paperSpaces.getOrPut(name.uppercase()) { PaperSpace() } else null
    val blockEntities = paper?.entities ?: ArrayList()
    while (!isAt(ENDBLK) && !isAt(ENDSEC)) {
      if (tokens.code != 0) {
        advance()
        continue
      }
      readEntity(blockEntities)
    }
    paper = null
    if (isAt(ENDBLK)) skipEntity()
    if (isPaper) return null
    return DxfBlock(name, bx, by, bz, blockEntities)
  }

  // объекты, которые не нужны вьюеру, пропускаются целиком
  private fun readObjects() {
    advance()
    while (!isAt(ENDSEC)) {
      if (isAt(MLINESTYLE)) {
        val (handle, style) = readMLineStyle()
        if (handle.isNotEmpty()) objects[handle.uppercase()] = style
        continue
      }
      if (isAt(SORTENTSTABLE)) {
        readSortTable()
        continue
      }
      if (isAt(MLEADERSTYLE)) {
        readMLeaderStyle()
        continue
      }
      if (isAt(WIPEOUTVARIABLES)) {
        readWipeoutVariables()
        continue
      }
      if (isAt(IMAGEDEF)) {
        readImageDef()
        continue
      }
      if (isAt(LAYOUT)) {
        readLayout()
        continue
      }
      advance()
    }
  }

  // IMAGEDEF: 1 — путь к файлу картинки; IMAGE ссылается на неё по handle
  private fun readImageDef() {
    var handle = ""
    var path = ""
    while (true) {
      advance()
      when (tokens.code) {
        0 -> break
        5 -> handle = tokens.valueAsAscii().trim()
        1 -> path = string()
      }
    }
    if (handle.isNotEmpty()) objects[handle.uppercase()] = DxfImageDef(path)
  }

  // WIPEOUTVARIABLES: 70 — показывать рамку WIPEOUT (0 — нет)
  private fun readWipeoutVariables() {
    while (true) {
      advance()
      if (tokens.code == 0) break
      if (tokens.code == 70) wipeoutFrame = (tokens.intOrNull() ?: 0) != 0
    }
  }

  // LAYOUT: до маркера AcDbLayout — параметры печати (бумага, поля), после — имя, вкладка, границы и запись блока (330);
  // 1 и 330 встречаются и до маркера (имя набора параметров, владелец)
  private fun readLayout() {
    var inLayout = false
    var name = ""
    var tabOrder = 0
    var paperWidth = 0.0
    var paperHeight = 0.0
    val margins = DoubleArray(4)
    var originX = 0.0
    var originY = 0.0
    var rotation = 0
    var inches = false
    var scalePaper = 1.0
    var scaleDrawing = 1.0
    val limits = DoubleArray(4)
    var record = 0L
    while (true) {
      advance()
      val code = tokens.code
      if (code == 0) break
      if (code == 100) {
        inLayout = tokens.valueEquals(ACDB_LAYOUT)
        continue
      }
      if (inLayout) {
        when (code) {
          1 -> name = string()
          71 -> tabOrder = tokens.intOrNull() ?: 0
          10 -> limits[0] = tokens.double()
          20 -> limits[1] = tokens.double()
          11 -> limits[2] = tokens.double()
          21 -> limits[3] = tokens.double()
          330 -> record = handle()
        }
        continue
      }
      when (code) {
        in 40..43 -> margins[code - 40] = tokens.double()
        44 -> paperWidth = tokens.double()
        45 -> paperHeight = tokens.double()
        46 -> originX = tokens.double()
        47 -> originY = tokens.double()
        72 -> inches = tokens.intOrNull() == 0
        73 -> rotation = tokens.intOrNull() ?: 0
        142 -> scalePaper = tokens.double()
        143 -> scaleDrawing = tokens.double()
      }
    }
    layoutRecords.add(
      DxfLayout(
        name, tabOrder, paperWidth, paperHeight, margins, originX, originY, rotation, inches, scalePaper, scaleDrawing,
        limits, record,
      ),
    )
  }

  // MLEADERSTYLE: то, что нужно для линий выноски и блока-содержимого (коды — в [DxfMLeaderStyle]), и размер стрелки (44)
  private fun readMLeaderStyle() {
    var handle = ""
    var contentType: Int? = null
    var lineColor: Int? = null
    var lineTypeHandle: String? = null
    var lineWeight: Int? = null
    var blockHandle: String? = null
    var blockColor: Int? = null
    var size = Double.NaN
    while (true) {
      advance()
      when (tokens.code) {
        0 -> break
        5 -> handle = tokens.valueAsAscii().trim()
        170 -> contentType = tokens.intOrNull()
        91 -> lineColor = tokens.intOrNull()
        340 -> lineTypeHandle = tokens.valueAsAscii().trim().uppercase()
        92 -> lineWeight = tokens.intOrNull()
        343 -> blockHandle = tokens.valueAsAscii().trim().uppercase()
        94 -> blockColor = tokens.intOrNull()
        44 -> size = tokens.double()
      }
    }
    if (handle.isNotEmpty()) {
      objects[handle.uppercase()] = DxfMLeaderStyle(contentType, lineColor, lineTypeHandle, lineWeight, blockHandle, blockColor)
      if (!size.isNaN()) mleaderArrowSizes[handle.uppercase()] = size
    }
  }

  /** MLINESTYLE: первый 62 (до 71) — цвет заливки, после 71 — элементы: 49 смещение, 62 цвет, 6 тип линии. */
  private fun readMLineStyle(): Pair<String, DxfMLineStyle> {
    var handle = ""
    var name = ""
    var flags = 0
    var fillColor = DxfCommon.COLOR_BY_LAYER
    var inElements = false
    val offsets = GrowingDoubleArray()
    val colors = GrowingIntArray()
    val lineTypes = ArrayList<String>()
    while (true) {
      advance()
      when (tokens.code) {
        0 -> break
        5 -> handle = tokens.valueAsAscii().trim()
        2 -> name = string()
        70 -> flags = tokens.intOrNull() ?: 0
        71 -> inElements = true
        49 -> if (inElements) {
          offsets.add(tokens.double())
          colors.add(DxfCommon.COLOR_BY_LAYER)
          lineTypes.add(BY_LAYER_NAME)
        }
        62 -> {
          val color = tokens.intOrNull() ?: DxfCommon.COLOR_BY_LAYER
          if (!inElements) fillColor = color else if (offsets.size > 0) colors[colors.size - 1] = color
        }
        6 -> if (lineTypes.isNotEmpty()) lineTypes[lineTypes.size - 1] = layerName()
      }
    }
    return handle to DxfMLineStyle(name, flags, fillColor, offsets.toArray(), colors.toArray(), lineTypes)
  }

  private fun readModelEntities() {
    advance()
    inModelSpace = true
    paper = activePaper
    while (!isAt(ENDSEC)) {
      if (tokens.code != 0) {
        advance()
        continue
      }
      if (++entitiesSinceProgress >= PROGRESS_STEP) {
        entitiesSinceProgress = 0
        onProgress(tokens.bytesRead)
      }
      readEntity(entities)
    }
    inModelSpace = false
    paper = null
  }

  /**
   * SORTENTSTABLE: после маркера AcDbSortentsTable (100) — запись блока-владельца (330) и пары «handle сущности (331) —
   * ключ сортировки (5)». До маркера 5 и 330 — handle самой таблицы и словаря.
   */
  private fun readSortTable() {
    var body = false
    var owner = 0L
    var entity = 0L
    val keys = HashMap<Long, Long>()
    while (true) {
      advance()
      if (tokens.code == 0) break
      if (!body) {
        if (tokens.code == 100) body = true
        continue
      }
      when (tokens.code) {
        330 -> owner = handle()
        331 -> entity = handle()
        5 -> if (entity != 0L) {
          keys[entity] = handle()
          entity = 0L
        }
      }
    }
    if (keys.isNotEmpty()) sortTables.getOrPut(owner) { HashMap() }.putAll(keys)
  }

  /**
   * Порядок прорисовки модели: сущности из таблицы сортировки — по её ключу, остальные — по своему handle; без таблицы
   * и при равных ключах — порядок файла. Сущность без handle идёт за предыдущей.
   */
  private fun applyDrawOrder() {
    val modelRecord = blockRecords.entries.firstOrNull { it.value.equals(MODEL_SPACE, ignoreCase = true) }?.key
    // записи *Model_Space нет — единственная таблица сортировки считается таблицей модели
    val keys = (if (modelRecord != null) sortTables[modelRecord] else sortTables.values.singleOrNull()) ?: return
    val order = LongArray(entities.size)
    var previous = Long.MIN_VALUE
    for ((i, entity) in entities.withIndex()) {
      val handle = entity.common.handle
      // handle беззнаковые: сдвиг на знаковый бит сохраняет порядок
      if (handle != 0L) previous = (keys[handle] ?: handle) xor Long.MIN_VALUE
      order[i] = previous
    }
    val sorted = entities.indices.sortedBy { order[it] }.map { entities[it] }
    entities.clear()
    entities.addAll(sorted)
  }

  // ---------- сущности: каждая читается до следующего кода 0 и оставляет его текущим ----------

  private fun readEntity(target: MutableList<DxfEntity>) {
    when {
      tokens.valueEquals(FACE_3D) -> read3dFace(target)
      tokens.valueEquals(LINE) -> add(target, readLine())
      tokens.valueEquals(LWPOLYLINE) -> add(target, readLwPolyline())
      tokens.valueEquals(POLYLINE) -> add(target, readPolyline())
      tokens.valueEquals(LEADER) -> addParts(target, readLeader(), "LEADER")
      tokens.valueEquals(MULTILEADER) -> addParts(target, readMultiLeader(), "MULTILEADER")
      tokens.valueEquals(MESH) -> add(target, readMesh())
      tokens.valueEquals(CIRCLE) -> add(target, readArc(isCircle = true))
      tokens.valueEquals(ARC) -> add(target, readArc(isCircle = false))
      tokens.valueEquals(ELLIPSE) -> add(target, readEllipse())
      tokens.valueEquals(SPLINE) || tokens.valueEquals(HELIX) -> add(target, readSpline())
      tokens.valueEquals(SOLID) || tokens.valueEquals(TRACE) -> add(target, readSolid())
      tokens.valueEquals(WIPEOUT) -> add(target, readWipeout())
      tokens.valueEquals(IMAGE) -> add(target, readImage())
      tokens.valueEquals(HATCH) -> add(target, readHatch())
      tokens.valueEquals(MPOLYGON) -> add(target, readHatch(mpolygon = true))
      tokens.valueEquals(MLINE) -> add(target, readMLine())
      tokens.valueEquals(POINT) -> add(target, readPoint())
      tokens.valueEquals(TEXT) -> addParts(target, readText())
      // ATTDEF вне блока — описание атрибута, не текст
      tokens.valueEquals(ATTDEF) && !inModelSpace -> addParts(target, readText(ATTDEF_KIND))
      tokens.valueEquals(MTEXT) -> addParts(target, readMText())
      tokens.valueEquals(INSERT) -> add(target, readInsert())
      tokens.valueEquals(TOLERANCE) -> addParts(target, readTolerance(), "TOLERANCE")
      tokens.valueEquals(DIMENSION) || tokens.valueEquals(ACAD_TABLE) || tokens.valueEquals(ARC_DIMENSION) ||
        tokens.valueEquals(LARGE_RADIAL_DIMENSION) -> add(target, readAnonymousBlockRef())
      tokens.valueEquals(SOLID_3D) || tokens.valueEquals(REGION) || tokens.valueEquals(BODY) -> add(target, readBody())
      SURFACES.any { tokens.valueEquals(it) } -> add(target, readBody().also { it.sourceKind = "SURFACE" })
      tokens.valueEquals(XLINE) -> add(target, readXLine(isRay = false))
      tokens.valueEquals(RAY) -> add(target, readXLine(isRay = true))
      tokens.valueEquals(ACAD_PROXY_ENTITY) -> addParts(target, readProxyGraphics(isProxyEntity = true))
      tokens.valueEquals(VIEWPORT) -> readViewportEntity()
      tokens.valueEquals(OLE2FRAME) -> addParts(target, readOle2Frame(), "OLE2FRAME")
      // сущность без класса: графика прокси может лежать после AcDbEntity
      else -> addParts(target, readProxyGraphics(isProxyEntity = false))
    }
  }

  // VIEWPORT листа (поля — в [DxfViewport]); вне листа не нужен
  private fun readViewportEntity() {
    val common = DxfCommon()
    val center = DoubleArray(3)
    val target = DoubleArray(3)
    val direction = doubleArrayOf(0.0, 0.0, 1.0)
    var width = 0.0
    var height = 0.0
    var viewX = 0.0
    var viewY = 0.0
    var viewHeight = 0.0
    var twist = 0.0
    var id = 0
    var status = 1
    var flags = 0
    val frozen = ArrayList<String>()
    while (true) {
      advance()
      val code = tokens.code
      if (code == 0) break
      if (readCommon(common)) continue
      when (code) {
        10, 20, 30 -> center[code / 10 - 1] = tokens.double()
        17, 27, 37 -> target[code / 10 - 1] = tokens.double()
        16, 26, 36 -> direction[code / 10 - 1] = tokens.double()
        40 -> width = tokens.double()
        41 -> height = tokens.double()
        12 -> viewX = tokens.double()
        22 -> viewY = tokens.double()
        45 -> viewHeight = tokens.double()
        51 -> twist = tokens.double()
        68 -> status = tokens.intOrNull() ?: status
        69 -> id = tokens.intOrNull() ?: 0
        90 -> flags = tokens.intOrNull() ?: 0
        331 -> frozen.add(tokens.valueAsAscii().trim().uppercase())
      }
    }
    // в ENTITIES листовой VIEWPORT — только с кодом 67 = 1; в блоке листа [paper] задан и без него
    val space = paper ?: return
    if (inModelSpace && !common.paperSpace) return
    val viewport = DxfViewport(
      center[0], center[1], center[2], width, height, id, viewX, viewY, target[0], target[1], target[2],
      direction[0], direction[1], direction[2], viewHeight, twist, frozen, status, flags,
    )
    viewport.common.copyFrom(common)
    // таблица LAYER идёт раньше сущностей
    viewport.frozenLayerNames = frozen.mapNotNullTo(HashSet()) { layerNames[it] }
    space.viewports.add(viewport)
  }

  private fun add(target: MutableList<DxfEntity>, entity: DxfEntity?) {
    if (entity == null) return
    if (entity.common.paperSpace) {
      paper?.entities?.add(entity)
      return
    }
    if (inModelSpace) layerOrder.add(entity.common.layer)
    target.add(entity)
  }

  /** Части одной сущности DXF: в сцене они — один объект типа [kind] (null — по классу первой части). */
  private fun addParts(target: MutableList<DxfEntity>, parts: List<DxfEntity>, kind: String? = null) {
    for ((i, part) in parts.withIndex()) {
      part.sourceKind = kind
      part.joinsPrevious = i > 0
      add(target, part)
    }
  }

  /** Общие коды; true — код разобран. */
  private fun readCommon(common: DxfCommon): Boolean {
    when (tokens.code) {
      8 -> common.layer = layerName()
      62 -> common.color = tokens.intOrNull() ?: common.color
      6 -> common.lineType = layerName()
      48 -> common.lineTypeScale = tokens.double()
      370 -> common.lineWeight = tokens.intOrNull() ?: common.lineWeight
      39 -> common.thickness = tokens.double()
      420 -> common.trueColor = tokens.intOrNull()
      440 -> common.transparency = tokens.intOrNull() ?: common.transparency
      67 -> common.paperSpace = tokens.intOrNull() == 1
      5 -> common.handle = handle()
      210 -> common.nx = tokens.double()
      220 -> common.ny = tokens.double()
      230 -> common.nz = tokens.double()
      else -> return false
    }
    return true
  }

  private fun read3dFace(target: MutableList<DxfEntity>) {
    val c = DoubleArray(12)
    val common = DxfCommon()
    var invisible = 0
    var hasFourth = false
    while (true) {
      advance()
      val code = tokens.code
      if (code == 0) break
      // handle грани не нужен: грани модели идут в сетку, а не в порядок прорисовки
      if (code == 5) continue
      if (readCommon(common)) continue
      when (code) {
        in 10..13 -> {
          c[(code - 10) * 3] = tokens.double()
          if (code == 13) hasFourth = true
        }
        in 20..23 -> c[(code - 20) * 3 + 1] = tokens.double()
        in 30..33 -> c[(code - 30) * 3 + 2] = tokens.double()
        70 -> invisible = tokens.intOrNull() ?: 0
      }
    }
    // 4-го угла в записи нет — грань треугольная
    if (!hasFourth) c.copyInto(c, 9, 6, 9)
    if (common.paperSpace) {
      paper?.entities?.add(DxfEntity.Face(c, invisible).also { it.common.copyFrom(common) })
      return
    }
    if (!inModelSpace) {
      target.add(DxfEntity.Face(c, invisible).also { it.common.copyFrom(common) })
      return
    }
    layerOrder.add(common.layer)
    faceMeshes.getOrPut(common.layer) { MeshBuilder(common.layer) }.addFace(c, invisible)
  }

  private fun readLine(): DxfEntity {
    val c = DoubleArray(6)
    val common = DxfCommon()
    while (true) {
      advance()
      val code = tokens.code
      if (code == 0) break
      if (readCommon(common)) continue
      when (code) {
        10, 11 -> c[(code - 10) * 3] = tokens.double()
        20, 21 -> c[(code - 20) * 3 + 1] = tokens.double()
        30, 31 -> c[(code - 30) * 3 + 2] = tokens.double()
      }
    }
    return DxfEntity.Line(c).also { it.common.copyFrom(common) }
  }

  /** OLE2FRAME: только рамка по углам 10 (левый верхний) и 11 (правый нижний) на Z из 10; данные 310 пропускаются. */
  private fun readOle2Frame(): List<DxfEntity> {
    val c = DoubleArray(6)
    val common = DxfCommon()
    var hasCorner = false
    while (true) {
      advance()
      val code = tokens.code
      if (code == 0) break
      if (readCommon(common)) continue
      when (code) {
        10, 11 -> {
          c[(code - 10) * 3] = tokens.double()
          if (code == 11) hasCorner = true
        }
        20, 21 -> c[(code - 20) * 3 + 1] = tokens.double()
        30 -> c[2] = tokens.double()
      }
    }
    if (!hasCorner) return emptyList()
    val z = c[2]
    val xyz = doubleArrayOf(c[0], c[1], z, c[3], c[1], z, c[3], c[4], z, c[0], c[4], z)
    return listOf(DxfEntity.Polyline(xyz, DoubleArray(4), closed = true, inOcs = false).also { it.common.copyFrom(common) })
  }

  /** XLINE / RAY: точка 10/20/30 и направление 11/21/31; нулевое направление — пропуск. */
  private fun readXLine(isRay: Boolean): DxfEntity? {
    val c = DoubleArray(6)
    val common = DxfCommon()
    while (true) {
      advance()
      val code = tokens.code
      if (code == 0) break
      if (readCommon(common)) continue
      when (code) {
        10, 11 -> c[(code - 10) * 3] = tokens.double()
        20, 21 -> c[(code - 20) * 3 + 1] = tokens.double()
        30, 31 -> c[(code - 30) * 3 + 2] = tokens.double()
      }
    }
    if (c[3] == 0.0 && c[4] == 0.0 && c[5] == 0.0) return null
    return DxfEntity.XLine(c[0], c[1], c[2], c[3], c[4], c[5], isRay).also { it.common.copyFrom(common) }
  }

  private fun readLwPolyline(): DxfEntity? {
    val common = DxfCommon()
    var flags = 0
    var elevation = 0.0
    val xy = GrowingDoubleArray()
    val bulges = GrowingDoubleArray()
    var constantWidth = 0.0
    // ширины у вершин (40/41) заводятся при первой встрече; NaN — не задана, берётся постоянная (43)
    var widths: GrowingDoubleArray? = null
    while (true) {
      advance()
      if (tokens.code == 0) break
      if (readCommon(common)) continue
      when (tokens.code) {
        70 -> flags = tokens.intOrNull() ?: 0
        38 -> elevation = tokens.double()
        43 -> constantWidth = tokens.double()
        10 -> {
          xy.add(tokens.double())
          xy.add(0.0)
          bulges.add(0.0)
          widths?.let { it.add(Double.NaN); it.add(Double.NaN) }
        }
        20 -> if (xy.size > 0) xy.setLast(tokens.double())
        42 -> if (bulges.size > 0) bulges.setLast(tokens.double())
        40, 41 -> if (xy.size > 0) {
          val w = widths ?: GrowingDoubleArray().also { created ->
            repeat(xy.size) { created.add(Double.NaN) }
            widths = created
          }
          w[w.size - 42 + tokens.code] = tokens.double()
        }
      }
    }
    val count = xy.size / 2
    if (count < 2) return null
    val xyz = DoubleArray(count * 3) { i ->
      when (i % 3) {
        0 -> xy[i / 3 * 2]
        1 -> xy[i / 3 * 2 + 1]
        else -> elevation
      }
    }
    return DxfEntity.Polyline(
      xyz, bulges.toArray(), closed = flags and CLOSED_FLAG != 0, inOcs = true,
      widths = segmentWidths(widths, count, constantWidth, constantWidth),
      continuousPattern = flags and PLINEGEN_FLAG != 0,
    ).also { it.common.copyFrom(common) }
  }

  /**
   * Ширины сегментов (начало, конец) у каждой вершины: незаданные (NaN) — по умолчанию.
   * null — все нулевые, полилиния рисуется линиями.
   */
  private fun segmentWidths(widths: GrowingDoubleArray?, count: Int, defaultStart: Double, defaultEnd: Double): DoubleArray? {
    if (widths == null && defaultStart == 0.0 && defaultEnd == 0.0) return null
    var any = false
    val result = DoubleArray(count * 2) { i ->
      val value = widths?.get(i)?.takeIf { !it.isNaN() } ?: if (i % 2 == 0) defaultStart else defaultEnd
      val width = if (value.isFinite()) abs(value) else 0.0
      if (width != 0.0) any = true
      width
    }
    return if (any) result else null
  }

  /**
   * LEADER: вершины (10/20/30, мировые) рисуются ломаной, при 72 = 1 — сплайном через них. При 71 = 1 на первой
   * вершине — стрелка вдоль первого сегмента (у сплайна — по касательной), длина DIMASZ × DIMSCALE стиля размеров 3
   * или переопределений в XDATA `DSTYLE`.
   */
  private fun readLeader(): List<DxfEntity> {
    val common = DxfCommon()
    val xyz = GrowingDoubleArray()
    var hasArrow = true
    var spline = false
    var styleName: String? = null
    val overrides = DimStyleOverrides()
    while (true) {
      advance()
      if (tokens.code == 0) break
      if (overrides.read() || readCommon(common)) continue
      when (tokens.code) {
        10 -> xyz.addPoint(tokens.double())
        20 -> xyz.setPointCoord(1, tokens.double())
        30 -> xyz.setPointCoord(2, tokens.double())
        71 -> hasArrow = tokens.intOrNull() != 0
        72 -> spline = tokens.intOrNull() == 1
        3 -> styleName = string()
      }
    }
    val count = xyz.size / 3
    if (count < 2) return emptyList()
    val v = xyz.toArray()
    val result = ArrayList<DxfEntity>(2)
    // направление стрелки: у сплайна — к первой контрольной точке после начала, у ломаной — к первой несовпадающей вершине
    val dir = DoubleArray(3)
    if (spline) {
      result += DxfEntity.Spline(3, DoubleArray(0), DoubleArray(0), v, closed = false).also { it.common.copyFrom(common) }
      interpolateFitPoints(v, null, null, closed = false)?.control?.let { c -> for (k in 0..2) dir[k] = c[3 + k] - c[k] }
    } else {
      result += DxfEntity.Polyline(v, DoubleArray(count), closed = false, inOcs = false).also { it.common.copyFrom(common) }
      val next = firstDistinctVertex(v)
      if (next > 0) for (k in 0..2) dir[k] = v[next * 3 + k] - v[k]
    }
    if (hasArrow) {
      val style = dimStyles[(styleName ?: "STANDARD").uppercase()] ?: headerDimStyle
      val scale = (overrides.values[40] ?: style[DIM_SCALE]).takeIf { it > 0 } ?: 1.0
      val size = (overrides.values[41] ?: style[DIM_ASZ]) * scale
      arrow(common, v, 0, dir, common.nx, common.ny, common.nz, size)?.let { result += it }
    }
    return result
  }

  /**
   * Стрелка Closed filled — закрашенный треугольник (SOLID в мировых координатах): остриё — точка [index] из [xyz],
   * основание — на [size] дальше по направлению [dir] (вдоль выноски), полуширина — [ARROW_HALF_WIDTH] длины,
   * плоскость — с нормалью ([nx], [ny], [nz]). null — направление нулевое или размер не положителен.
   */
  private fun arrow(
    common: DxfCommon, xyz: DoubleArray, index: Int, dir: DoubleArray, nx: Double, ny: Double, nz: Double, size: Double,
  ): DxfEntity.Solid? {
    val len = hypot(hypot(dir[0], dir[1]), dir[2])
    if (!(len > 0) || !(size > 0) || !len.isFinite() || !size.isFinite()) return null
    val u = DoubleArray(3) { dir[it] / len }
    // поперёк — нормаль × ось; ось вдоль нормали — Z × ось, затем Y × ось
    val p = doubleArrayOf(ny * u[2] - nz * u[1], nz * u[0] - nx * u[2], nx * u[1] - ny * u[0])
    if (hypot(hypot(p[0], p[1]), p[2]) < 1e-9) {
      p[0] = -u[1]; p[1] = u[0]; p[2] = 0.0
      if (hypot(p[0], p[1]) < 1e-9) {
        p[0] = u[2]; p[1] = 0.0; p[2] = -u[0]
      }
    }
    val half = size * ARROW_HALF_WIDTH / hypot(hypot(p[0], p[1]), p[2])
    val c = DoubleArray(12)
    for (k in 0..2) {
      val tip = xyz[index * 3 + k]
      val base = tip + u[k] * size
      val side = p[k] * half
      c[k] = tip
      c[3 + k] = base + side
      c[6 + k] = base - side
      c[9 + k] = base - side
    }
    return DxfEntity.Solid(c).also {
      it.common.copyFrom(common)
      it.common.nx = 0.0
      it.common.ny = 0.0
      it.common.nz = 1.0
      it.common.lineType = CONTINUOUS_NAME
    }
  }

  /** Меняет длину стрелки [arrow] (остриё на месте) в [size] раз. */
  private fun scaleArrow(arrow: DxfEntity.Solid, size: Double) {
    val c = arrow.corners
    for (i in 1..3) for (k in 0..2) c[i * 3 + k] = c[k] + (c[i * 3 + k] - c[k]) * size
  }

  /**
   * MULTILEADER: из `CONTEXT_DATA{…}` берутся выноски (`LEADER{…}` → `LEADER_LINE{…}`) и текст (304, место 12/22/32).
   * Каждая линия выноски — ломаная: вершины линии, точка выноски (10 в `LEADER{`) и полка длиной 40 вдоль 11/21/31.
   * Блок-содержимое — вхождение блока (341 handle записи, место 15/25/35, масштаб 16/26/36, поворот 46 в радианах).
   * На первой вершине линии — стрелка вдоль линии в плоскости выноски (110–112), длина — 42 (после контекста),
   * без него — из стиля MLEADERSTYLE (340), иначе [DEFAULT_DIMASZ]. Все координаты мировые.
   * Цвет, тип и толщина линий выноски, блок и его цвет — из сущности (91, 341, 171, 344, 93), если её флаг
   * переопределения (90) взведён, иначе из стиля MLEADERSTYLE (340): применяются в [resolveMultiLeader].
   */
  private fun readMultiLeader(): List<DxfEntity> {
    val common = DxfCommon()
    val result = ArrayList<DxfEntity>()
    var styleHandle: String? = null
    var overrides = 0
    var contentType: Int? = null
    var lineColor: Int? = null
    var lineTypeHandle: String? = null
    var lineWeight: Int? = null
    var blockHandle: String? = null
    var blockColor: Int? = null
    var contextBlockHandle: String? = null
    var contextBlockColor: Int? = null
    var hasBlock = false
    val blockPos = DoubleArray(3)
    val blockScale = doubleArrayOf(1.0, 1.0, 1.0)
    val blockNormal = doubleArrayOf(0.0, 0.0, 1.0)
    var blockRotation = 0.0
    var depth = 0 // 0 — вне контекста или его верхний уровень, 1 — в LEADER{, 2 — в LEADER_LINE{
    var inContext = false
    var hasText = true
    var text = ""
    val textPos = DoubleArray(3)
    val textDir = DoubleArray(2)
    var textHeight = 1.0
    var spacing = 1.0
    var textWidth = 0.0 // ширина рамки текста (43); 0 — без переноса
    val leaderPoint = DoubleArray(3)
    val dogDir = DoubleArray(3)
    var dogLength = 0.0
    var hasLastPoint = true
    var hasDogleg = true
    val line = GrowingDoubleArray()
    // стрелки: остриё и направление (по 6 чисел), оси плоскости выноски, размер (NaN — не задан) и стиль
    val arrows = GrowingDoubleArray()
    val planeX = doubleArrayOf(1.0, 0.0, 0.0)
    val planeY = doubleArrayOf(0.0, 1.0, 0.0)
    var arrowSize = Double.NaN
    while (true) {
      advance()
      val code = tokens.code
      if (code == 0) break
      if (readCommon(common)) continue
      when (code) {
        300 -> if (isMarker(CONTEXT_DATA_OPEN)) inContext = true
        301 -> inContext = false
        302 -> if (inContext && isMarker(LEADER_OPEN)) {
          depth = 1
          leaderPoint.fill(0.0)
          dogDir.fill(0.0)
          dogLength = 0.0
          hasLastPoint = true
          hasDogleg = true
        }
        303 -> depth = 0
        304 -> if (inContext) {
          if (isMarker(LEADER_LINE_OPEN)) {
            if (depth == 1) {
              depth = 2
              line.clear()
            }
          } else if (depth == 0) text = string()
        }
        305 -> if (depth == 2) {
          depth = 1
          emitLeaderLine(result, common, line, leaderPoint, dogDir, dogLength, hasLastPoint, hasDogleg, arrows)
        }
        else -> if (inContext) when (depth) {
          0 -> when (code) {
            290 -> hasText = tokens.intOrNull() != 0
            12 -> textPos[0] = tokens.double()
            22 -> textPos[1] = tokens.double()
            32 -> textPos[2] = tokens.double()
            13 -> textDir[0] = tokens.double()
            23 -> textDir[1] = tokens.double()
            41 -> textHeight = tokens.double()
            45 -> spacing = tokens.double()
            43 -> textWidth = tokens.double()
            296 -> hasBlock = tokens.intOrNull() == 1
            341 -> contextBlockHandle = tokens.valueAsAscii().trim().uppercase()
            93 -> contextBlockColor = tokens.intOrNull()
            14 -> blockNormal[0] = tokens.double()
            24 -> blockNormal[1] = tokens.double()
            34 -> blockNormal[2] = tokens.double()
            15 -> blockPos[0] = tokens.double()
            25 -> blockPos[1] = tokens.double()
            35 -> blockPos[2] = tokens.double()
            16 -> blockScale[0] = tokens.double()
            26 -> blockScale[1] = tokens.double()
            36 -> blockScale[2] = tokens.double()
            46 -> blockRotation = tokens.double()
            111, 121, 131 -> planeX[code / 10 - 11] = tokens.double()
            112, 122, 132 -> planeY[code / 10 - 11] = tokens.double()
          }
          1 -> when (code) {
            290 -> hasLastPoint = tokens.intOrNull() != 0
            291 -> hasDogleg = tokens.intOrNull() != 0
            10 -> leaderPoint[0] = tokens.double()
            20 -> leaderPoint[1] = tokens.double()
            30 -> leaderPoint[2] = tokens.double()
            11 -> dogDir[0] = tokens.double()
            21 -> dogDir[1] = tokens.double()
            31 -> dogDir[2] = tokens.double()
            40 -> dogLength = tokens.double()
          }
          2 -> when (code) {
            10 -> line.addPoint(tokens.double())
            20 -> line.setPointCoord(1, tokens.double())
            30 -> line.setPointCoord(2, tokens.double())
          }
        } else when (code) {
          340 -> styleHandle = tokens.valueAsAscii().trim().uppercase()
          42 -> arrowSize = tokens.double()
          90 -> overrides = tokens.intOrNull() ?: 0
          91 -> lineColor = tokens.intOrNull()
          341 -> lineTypeHandle = tokens.valueAsAscii().trim().uppercase()
          171 -> lineWeight = tokens.intOrNull()
          172 -> contentType = tokens.intOrNull()
          344 -> blockHandle = tokens.valueAsAscii().trim().uppercase()
          93 -> blockColor = tokens.intOrNull()
        }
      }
    }
    val lines = result.filterIsInstance<DxfEntity.Polyline>()
    if (hasText && text.isNotBlank()) {
      val angle = if (textDir[0] != 0.0 || textDir[1] != 0.0) toDegrees(atan2(textDir[1], textDir[0])) else 0.0
      val frame = if (textWidth > 0) MTextFrame(textWidth, mTextPitch(textHeight, spacing), 1) else null
      result += layoutMText(common, textPos[0], textPos[1], textPos[2], textHeight, angle, 1, spacing, text, frame = frame)
    }
    // блок вставляется в системе объекта с нормалью 14/24/34: мировую точку 15/25/35 переводим в неё
    var block: DxfEntity.Insert? = null
    if (hasBlock || contextBlockHandle != null) {
      val toWorld = DxfTransform.fromExtrusion(blockNormal[0], blockNormal[1], blockNormal[2])
      fun ocs(axisX: Double, axisY: Double, axisZ: Double) =
        blockPos[0] * toWorld.dx(axisX, axisY, axisZ) + blockPos[1] * toWorld.dy(axisX, axisY, axisZ) +
          blockPos[2] * toWorld.dz(axisX, axisY, axisZ)
      block = DxfEntity.Insert(
        "", ocs(1.0, 0.0, 0.0), ocs(0.0, 1.0, 0.0), ocs(0.0, 0.0, 1.0),
        blockScale[0], blockScale[1], blockScale[2], toDegrees(blockRotation), 1, 1, 0.0, 0.0, emptyList(),
      ).also {
        it.common.copyFrom(common)
        it.common.nx = blockNormal[0]
        it.common.ny = blockNormal[1]
        it.common.nz = blockNormal[2]
      }
      result += block
    }
    if (arrows.size > 0) {
      // нормаль плоскости выноски — X × Y
      val nx = planeX[1] * planeY[2] - planeX[2] * planeY[1]
      val ny = planeX[2] * planeY[0] - planeX[0] * planeY[2]
      val nz = planeX[0] * planeY[1] - planeX[1] * planeY[0]
      val a = arrows.toArray()
      for (i in 0 until a.size / 6) {
        val dir = doubleArrayOf(a[i * 6 + 3], a[i * 6 + 4], a[i * 6 + 5])
        // размер не задан — стрелка единичная, длина ставится по стилю после чтения OBJECTS
        val arrow = arrow(common, a, i * 2, dir, nx, ny, nz, if (arrowSize.isNaN()) 1.0 else arrowSize) ?: continue
        if (arrowSize.isNaN()) pendingArrows += arrow to styleHandle
        result += arrow
      }
    }
    pendingLeaders += PendingMLeader(
      common, styleHandle, overrides, contentType, lineColor, lineTypeHandle, lineWeight,
      contextBlockHandle, blockHandle, blockColor ?: contextBlockColor, lines, block,
    )
    return result
  }

  /** Свойства MULTILEADER, которые могут прийти из стиля: известны только после секции OBJECTS. */
  private class PendingMLeader(
    val common: DxfCommon, val styleHandle: String?, val overrides: Int, val contentType: Int?,
    val lineColor: Int?, val lineTypeHandle: String?, val lineWeight: Int?,
    val contextBlockHandle: String?, val blockHandle: String?, val blockColor: Int?,
    val lines: List<DxfEntity.Polyline>, val block: DxfEntity.Insert?,
  )

  // значение сущности, если её флаг переопределения взведён или стиля нет, иначе стиля
  private fun resolveMultiLeader(p: PendingMLeader) {
    val style = p.styleHandle?.let { objects[it] } as? DxfMLeaderStyle
    fun <T> pick(flag: Int, own: T?, fromStyle: T?): T? = if (style == null || p.overrides and flag != 0) own else fromStyle
    val lineColor = pick(MLEADER_LINE_COLOR, p.lineColor, style?.lineColor)
    val lineType = pick(MLEADER_LINE_TYPE, p.lineTypeHandle, style?.lineTypeHandle)?.let { lineTypeNames[it] }
    val lineWeight = pick(MLEADER_LINE_WEIGHT, p.lineWeight, style?.lineWeight)
    for (line in p.lines) {
      if (lineColor != null) applyMLeaderColor(line.common, lineColor)
      // по блоку — как у самой выноски (common уже скопирован)
      if (lineType != null && !lineType.equals(BY_BLOCK_NAME, ignoreCase = true)) {
        line.common.lineType = if (lineType.equals(BY_LAYER_NAME, ignoreCase = true)) null else lineType
      }
      if (lineWeight != null && lineWeight != LINE_WEIGHT_BY_BLOCK) line.common.lineWeight = lineWeight
    }
    val block = p.block ?: return
    val contentType = pick(MLEADER_CONTENT_TYPE, p.contentType, style?.contentType)
    if (contentType != null && contentType != MLEADER_CONTENT_BLOCK) return
    val handle = p.contextBlockHandle ?: pick(MLEADER_BLOCK, p.blockHandle, style?.blockHandle)
    block.blockName = handle?.let { blockRecordNames[it] } ?: ""
    pick(MLEADER_BLOCK_COLOR, p.blockColor, style?.blockColor)?.let { applyMLeaderColor(block.common, it) }
  }

  // цвет MULTILEADER: старший байт C0 — по слою, C1 — по блоку (как у выноски), C2 — RGB, C3 — ACI в младшем байте
  private fun applyMLeaderColor(target: DxfCommon, raw: Int) {
    when (raw ushr 24) {
      0xC0 -> {
        target.color = DxfCommon.COLOR_BY_LAYER
        target.trueColor = null
      }
      0xC2 -> target.trueColor = raw and 0xFFFFFF
      0xC3 -> {
        target.color = raw and 0xFF
        target.trueColor = null
      }
    }
  }

  /** Линия выноски MULTILEADER ломаной; остриё и направление её стрелки (к следующей точке) — в [arrows]. */
  private fun emitLeaderLine(
    out: MutableList<DxfEntity>, common: DxfCommon, line: GrowingDoubleArray,
    leaderPoint: DoubleArray, dogDir: DoubleArray, dogLength: Double, hasLastPoint: Boolean, hasDogleg: Boolean,
    arrows: GrowingDoubleArray,
  ) {
    val xyz = GrowingDoubleArray()
    for (i in 0 until line.size / 3) {
      xyz.addPoint(line[i * 3])
      xyz.setPointCoord(1, line[i * 3 + 1])
      xyz.setPointCoord(2, line[i * 3 + 2])
    }
    if (hasLastPoint) {
      xyz.addPoint(leaderPoint[0])
      xyz.setPointCoord(1, leaderPoint[1])
      xyz.setPointCoord(2, leaderPoint[2])
      if (hasDogleg && dogLength != 0.0) {
        xyz.addPoint(leaderPoint[0] + dogDir[0] * dogLength)
        xyz.setPointCoord(1, leaderPoint[1] + dogDir[1] * dogLength)
        xyz.setPointCoord(2, leaderPoint[2] + dogDir[2] * dogLength)
      }
    }
    val count = xyz.size / 3
    if (count < 2) return
    val v = xyz.toArray()
    out += DxfEntity.Polyline(v, DoubleArray(count), closed = false, inOcs = false)
      .also { it.common.copyFrom(common) }
    // стрелка — только на вершине самой линии, не на точке полки
    if (line.size == 0) return
    val next = firstDistinctVertex(v)
    if (next < 0) return
    for (k in 0..2) arrows.add(v[k])
    for (k in 0..2) arrows.add(v[next * 3 + k] - v[k])
  }

  /** Индекс первой вершины, не совпадающей с начальной; -1 — все совпадают. */
  private fun firstDistinctVertex(xyz: DoubleArray): Int {
    for (i in 1 until xyz.size / 3) if (xyz[i * 3] != xyz[0] || xyz[i * 3 + 1] != xyz[1] || xyz[i * 3 + 2] != xyz[2]) return i
    return -1
  }

  private fun isMarker(marker: ByteArray): Boolean = tokens.valueEquals(marker)

  /** Переопределения размерного стиля в XDATA: пары 1070 (код переменной) и 1040/1070 (значение) после «DSTYLE». */
  private inner class DimStyleOverrides {
    val values = HashMap<Int, Double>()
    private var active = false
    private var key = -1

    /** Разбирает текущую пару, если это XDATA (код ≥ 1000); false — не XDATA. */
    fun read(): Boolean {
      if (tokens.code < 1000) return false
      when (tokens.code) {
        1001 -> {
          active = false
          key = -1
        }
        // строка-значение переменной (DIMPOST и т. п.) не начинает новую группу
        1000 -> if (active && key >= 0) key = -1 else active = string() == "DSTYLE"
        1070, 1040, 1071 -> if (active) {
          if (key < 0) key = tokens.intOrNull() ?: -1
          else {
            values[key] = tokens.double()
            key = -1
          }
        }
        // прочие значения (handle блока стрелки 1005 и т. п.) — пропускаются
        else -> if (key >= 0 && tokens.code != 1002) key = -1
      }
      return true
    }
  }

  /**
   * MESH (SubD-сетка): вершины 10/20/30 и список граней (код 93 — число целых в списке, затем 90: число углов и индексы).
   * Уровень подразделения 91 (до вершин) > 0 — сетка сглаживается по Catmull–Clark (`subdivideMesh`) со складками:
   * рёбра 94 (затем пары индексов кодом 90) и значения складок 95 (затем 140).
   */
  private fun readMesh(): DxfEntity? {
    val common = DxfCommon()
    val xyz = GrowingDoubleArray()
    val faces = ArrayList<IntArray>()
    var remaining = 0
    var face: IntArray? = null
    var filled = 0
    var levels = 0
    val edges = GrowingIntArray()
    var edgesRemaining = 0
    val creases = GrowingDoubleArray()
    var creasesRemaining = 0
    while (true) {
      advance()
      if (tokens.code == 0) break
      if (readCommon(common)) continue
      when (tokens.code) {
        10 -> xyz.addPoint(tokens.double())
        20 -> xyz.setPointCoord(1, tokens.double())
        30 -> xyz.setPointCoord(2, tokens.double())
        // 91 после вершин — номер подобъекта в переопределениях свойств
        91 -> if (xyz.size == 0) levels = tokens.intOrNull() ?: 0
        93 -> remaining = tokens.intOrNull() ?: 0
        94 -> edgesRemaining = (tokens.intOrNull() ?: 0) * 2
        95 -> creasesRemaining = tokens.intOrNull() ?: 0
        140 -> if (creasesRemaining > 0) {
          creases.add(tokens.double())
          creasesRemaining--
        }
        90 -> if (remaining == 0 && edgesRemaining > 0) {
          edges.add(tokens.intOrNull() ?: -1)
          edgesRemaining--
        } else if (remaining > 0) {
          val value = tokens.intOrNull() ?: 0
          remaining--
          val current = face
          if (current == null) {
            // число углов не больше оставшихся в списке чисел
            face = IntArray(value.coerceIn(0, remaining))
            filled = 0
            if (face.isEmpty()) face = null
          } else {
            current[filled++] = value
            if (filled == current.size) {
              faces.add(current)
              face = null
            }
          }
        }
      }
    }
    if (xyz.size == 0 || faces.isEmpty()) return null
    if (levels > 0) {
      val smooth = subdivideMesh(xyz.toArray(), faces, edges.toArray(), creases.toArray(), levels)
      return DxfEntity.Polyface(smooth.xyz, smooth.faces).also { it.common.copyFrom(common) }
    }
    return DxfEntity.Polyface(xyz.toArray(), faces).also { it.common.copyFrom(common) }
  }

  /**
   * 3DSOLID, REGION, BODY и SURFACE: до R2010 тело — текст SAT в кодах 1 (строка) и 3 (продолжение строки), разбирается
   * [AcisSat]. В R2013+ кодов 1/3 нет: SAB лежит в секции ACDSDATA, тело ждёт его по handle ([readAcdsData]).
   * После AcDbModelerGeometry у SURFACE идут свои подклассы (матрицы, флаги, 310 профиля): их коды не читаются.
   */
  private fun readBody(): DxfEntity {
    val common = DxfCommon()
    val sat = StringBuilder()
    var lines = 0
    // подкласс после AcDbEntity и AcDbModelerGeometry (AcDb3dSolid, AcDbSurface…): ни общих кодов, ни SAT
    var tail = false
    while (true) {
      advance()
      if (tokens.code == 0) break
      if (tokens.code == 100) {
        tail = !tokens.valueEquals(ACDB_ENTITY) && !tokens.valueEquals(ACDB_MODELER_GEOMETRY)
        continue
      }
      if (tail) continue
      if (readCommon(common)) continue
      when (tokens.code) {
        1 -> {
          if (lines++ > 0) sat.append('\n')
          AcisSat.decode(tokens.buffer, tokens.valueStart, tokens.valueEnd, sat)
        }
        3 -> AcisSat.decode(tokens.buffer, tokens.valueStart, tokens.valueEnd, sat)
      }
    }
    val acis = if (lines == 0) null else try {
      AcisSat.parse(sat.toString())
    } catch (_: RuntimeException) {
      null
    }
    val body = DxfEntity.Body(acis).also { it.common.copyFrom(common) }
    if (lines == 0 && common.handle != 0L) sabBodies[common.handle] = body
    return body
  }

  /**
   * ACDSDATA: записи ACDSRECORD — «2 AcDbDs::ID, 320 handle сущности» и «2 ASM_Data, 94 размер, 310 (в бинарном DXF и
   * 1004) куски данных». SAB достаётся телу из [sabBodies]; разбирается он ([AcisSat.parseSab]), только если тело рисуется.
   */
  private fun readAcdsData() {
    var field = ""
    var handle = 0L
    var data: ByteArray? = null
    var size = 0
    fun finish() {
      val body = sabBodies[handle]
      val bytes = data
      if (body != null && bytes != null && size > 0) {
        body.sab = if (size == bytes.size) bytes else bytes.copyOf(size)
      }
      handle = 0L
      data = null
      size = 0
      field = ""
    }
    while (true) {
      advance()
      when (tokens.code) {
        0 -> {
          finish()
          if (tokens.valueEquals(ENDSEC)) return
        }
        101 -> finish()
        2 -> field = string()
        320 -> if (field == "AcDbDs::ID") handle = handle()
        94 -> if (field == "ASM_Data" && sabBodies.containsKey(handle)) {
          // размер из файла — только начальная ёмкость
          data = ByteArray((tokens.intOrNull() ?: 0).coerceIn(0, MAX_PROXY_BYTES))
          size = 0
        }
        310, 1004 -> {
          val chunk = data ?: continue
          if (size + MAX_CHUNK_BYTES > chunk.size) {
            if (chunk.size >= MAX_PROXY_BYTES) continue
            data = chunk.copyOf(maxOf(chunk.size * 2, size + MAX_CHUNK_BYTES))
          }
          size += readChunk(data!!, size)
        }
      }
    }
  }

  private fun readPolyline(): DxfEntity? {
    val common = DxfCommon()
    var flags = 0
    var elevation = 0.0
    var meshM = 0
    var meshN = 0
    // ширины по умолчанию для вершин без своих 40/41
    var startWidth = 0.0
    var endWidth = 0.0
    while (true) {
      advance()
      if (tokens.code == 0) break
      if (readCommon(common)) continue
      when (tokens.code) {
        70 -> flags = tokens.intOrNull() ?: 0
        30 -> elevation = tokens.double()
        71 -> meshM = tokens.intOrNull() ?: 0
        72 -> meshN = tokens.intOrNull() ?: 0
        40 -> startWidth = tokens.double()
        41 -> endWidth = tokens.double()
      }
    }

    val xyz = GrowingDoubleArray()
    val bulges = GrowingDoubleArray()
    val faces = ArrayList<IntArray>()
    var widths: GrowingDoubleArray? = null
    while (tokens.valueEquals(VERTEX)) {
      widths = readVertex(xyz, bulges, faces, widths)
    }
    if (tokens.valueEquals(SEQEND)) skipEntity()

    if (flags and POLYFACE_FLAG != 0) {
      return DxfEntity.Polyface(xyz.toArray(), faces).also { it.common.copyFrom(common) }
    }
    if (flags and POLYGON_MESH_FLAG != 0) {
      val count = xyz.size / 3
      // сглаженная сеть (75) рисуется по исходным вершинам; строк не больше, чем хватает вершин
      val n = meshN
      val m = if (n > 0) minOf(meshM, count / n) else 0
      if (m < 2 || n < 2) return null
      val closedM = flags and CLOSED_FLAG != 0
      val closedN = flags and MESH_CLOSED_N_FLAG != 0
      // вершины идут строками M по N; четырёхугольник (i, j)–(i, j+1)–(i+1, j+1)–(i+1, j), замыкание — переход к 0
      for (i in 0 until if (closedM) m else m - 1) {
        val i1 = (i + 1) % m
        for (j in 0 until if (closedN) n else n - 1) {
          val j1 = (j + 1) % n
          faces.add(intArrayOf(i * n + j, i * n + j1, i1 * n + j1, i1 * n + j))
        }
      }
      return DxfEntity.Polyface(xyz.toArray(), faces).also { it.common.copyFrom(common) }
    }
    // 3D-полилиния — в мировых координатах, плоская — в OCS с отметкой elevation
    val isPlane = flags and POLYLINE_3D_FLAG == 0
    val points = xyz.toArray()
    if (points.size < 6) return null
    if (isPlane) {
      for (i in 2 until points.size step 3) points[i] = elevation
    }
    // ширина есть только у плоской полилинии
    val segmentWidths = if (isPlane) segmentWidths(widths, points.size / 3, startWidth, endWidth) else null
    return DxfEntity.Polyline(points, bulges.toArray(), closed = flags and CLOSED_FLAG != 0, inOcs = isPlane, widths = segmentWidths,
      continuousPattern = flags and PLINEGEN_FLAG != 0,
    ).also { it.common.copyFrom(common) }
  }

  /**
   * Вершина polyface с флагом 128 без 64 — это грань: индексы вершин (с 1) в кодах 71..74, минус — невидимое ребро.
   * Ширины вершины (40/41) пишутся в [widths] (NaN — не заданы); массив заводится при первой заданной и возвращается.
   */
  private fun readVertex(
    xyz: GrowingDoubleArray, bulges: GrowingDoubleArray, faces: MutableList<IntArray>, widths: GrowingDoubleArray?,
  ): GrowingDoubleArray? {
    var x = 0.0
    var y = 0.0
    var z = 0.0
    var bulge = 0.0
    var startWidth = Double.NaN
    var endWidth = Double.NaN
    var flags = 0
    val face = IntArray(4)
    while (true) {
      advance()
      when (tokens.code) {
        0 -> break
        10 -> x = tokens.double()
        20 -> y = tokens.double()
        30 -> z = tokens.double()
        40 -> startWidth = tokens.double()
        41 -> endWidth = tokens.double()
        42 -> bulge = tokens.double()
        70 -> flags = tokens.intOrNull() ?: 0
        in 71..74 -> face[tokens.code - 71] = abs(tokens.intOrNull() ?: 0) - 1
      }
    }
    val isFaceRecord = flags and FACE_RECORD_FLAG != 0 && flags and FACE_VERTEX_FLAG == 0
    if (isFaceRecord) {
      faces.add(face)
      return widths
    }
    // рамка сплайнового сглаживания: рисуется кривая (вершины с флагом 8), а не рамка
    if (flags and SPLINE_FRAME_FLAG != 0) return widths
    var result = widths
    if (result == null && !(startWidth.isNaN() && endWidth.isNaN())) {
      val created = GrowingDoubleArray()
      repeat(xyz.size / 3 * 2) { created.add(Double.NaN) }
      result = created
    }
    xyz.add(x)
    xyz.add(y)
    xyz.add(z)
    bulges.add(bulge)
    result?.add(startWidth)
    result?.add(endWidth)
    return result
  }

  private fun readArc(isCircle: Boolean): DxfEntity {
    val common = DxfCommon()
    var cx = 0.0
    var cy = 0.0
    var cz = 0.0
    var radius = 0.0
    var start = 0.0
    var end = 360.0
    while (true) {
      advance()
      if (tokens.code == 0) break
      if (readCommon(common)) continue
      when (tokens.code) {
        10 -> cx = tokens.double()
        20 -> cy = tokens.double()
        30 -> cz = tokens.double()
        40 -> radius = tokens.double()
        50 -> start = tokens.double()
        51 -> end = tokens.double()
      }
    }
    if (isCircle) {
      start = 0.0
      end = 360.0
    }
    return DxfEntity.Arc(cx, cy, cz, radius, start, end, isCircle).also { it.common.copyFrom(common) }
  }

  private fun readEllipse(): DxfEntity {
    val common = DxfCommon()
    val v = DoubleArray(6)
    var ratio = 1.0
    var start = 0.0
    var end = 2 * PI
    while (true) {
      advance()
      val code = tokens.code
      if (code == 0) break
      if (readCommon(common)) continue
      when (code) {
        10, 11 -> v[(code - 10) * 3] = tokens.double()
        20, 21 -> v[(code - 20) * 3 + 1] = tokens.double()
        30, 31 -> v[(code - 30) * 3 + 2] = tokens.double()
        40 -> ratio = tokens.double()
        41 -> start = tokens.double()
        42 -> end = tokens.double()
      }
    }
    return DxfEntity.Ellipse(v[0], v[1], v[2], v[3], v[4], v[5], ratio, start, end).also { it.common.copyFrom(common) }
  }

  private fun readSpline(): DxfEntity? {
    val common = DxfCommon()
    var flags = 0
    var degree = 3
    val knots = GrowingDoubleArray()
    val control = GrowingDoubleArray()
    val fit = GrowingDoubleArray()
    val weights = GrowingDoubleArray()
    var startTangent: DoubleArray? = null
    var endTangent: DoubleArray? = null
    while (true) {
      advance()
      val code = tokens.code
      if (code == 0) break
      if (readCommon(common)) continue
      when (code) {
        12 -> startTangent = doubleArrayOf(tokens.double(), 0.0, 0.0)
        22 -> startTangent?.set(1, tokens.double())
        32 -> startTangent?.set(2, tokens.double())
        13 -> endTangent = doubleArrayOf(tokens.double(), 0.0, 0.0)
        23 -> endTangent?.set(1, tokens.double())
        33 -> endTangent?.set(2, tokens.double())
        70 -> flags = tokens.intOrNull() ?: 0
        71 -> degree = tokens.intOrNull() ?: degree
        40 -> knots.add(tokens.double())
        41 -> weights.add(tokens.double())
        10 -> control.addPoint(tokens.double())
        20 -> control.setPointCoord(1, tokens.double())
        30 -> control.setPointCoord(2, tokens.double())
        11 -> fit.addPoint(tokens.double())
        21 -> fit.setPointCoord(1, tokens.double())
        31 -> fit.setPointCoord(2, tokens.double())
        // HELIX: дальше AcDbHelix (ось, радиус, витки) — коды 10–12, 40–42 совпадают со сплайновыми, не читаем
        100 -> if (tokens.valueEquals(ACDB_HELIX)) {
          while (tokens.code != 0) advance()
          break
        }
      }
    }
    if (control.size < 6 && fit.size < 6) return null
    // веса учитываем, только если они заданы на каждую контрольную точку, положительны и не все равны 1
    val w = weights.toArray()
    val rational = w.size == control.size / 3 && w.any { it != 1.0 } && w.all { it > 0.0 }
    return DxfEntity.Spline(
      degree, knots.toArray(), control.toArray(), fit.toArray(), closed = flags and CLOSED_FLAG != 0,
      weights = if (rational) w else null, startTangent = startTangent, endTangent = endTangent,
    )
      .also { it.common.copyFrom(common) }
  }

  private fun readSolid(): DxfEntity {
    val common = DxfCommon()
    val c = DoubleArray(12)
    var hasFourth = false
    while (true) {
      advance()
      val code = tokens.code
      if (code == 0) break
      if (readCommon(common)) continue
      when (code) {
        in 10..13 -> c[(code - 10) * 3] = tokens.double()
        in 20..23 -> c[(code - 20) * 3 + 1] = tokens.double()
        in 30..33 -> c[(code - 30) * 3 + 2] = tokens.double()
      }
      if (code in 13..33 && code % 10 == 3) hasFourth = true
    }
    // четвёртый угол не задан — совпадает с третьим (треугольник)
    if (!hasFourth) for (k in 0..2) c[9 + k] = c[6 + k]
    return DxfEntity.Solid(c).also { it.common.copyFrom(common) }
  }

  /**
   * WIPEOUT — как IMAGE: точка вставки (10), векторы пикселя U (11) и V (12), размер в пикселях (13/23), контур
   * (71: 1 — прямоугольник по двум углам, 2 — многоугольник; 14/24 — вершины). Вершины — в пикселях от левого
   * верхнего угла картинки, ось y вниз; в мир — как `boundary_path_wcs()` в ezdxf. Контура нет — вся картинка.
   */
  private fun readWipeout(): DxfEntity? {
    val common = DxfCommon()
    // вставка, U, V (по 3) и размер (2)
    val p = DoubleArray(11)
    val vertices = GrowingDoubleArray()
    while (true) {
      advance()
      val code = tokens.code
      if (code == 0) break
      if (readCommon(common)) continue
      when (code) {
        in 10..12 -> p[(code - 10) * 3] = tokens.double()
        in 20..22 -> p[(code - 20) * 3 + 1] = tokens.double()
        in 30..32 -> p[(code - 30) * 3 + 2] = tokens.double()
        13 -> p[9] = tokens.double()
        23 -> p[10] = tokens.double()
        14, 24 -> vertices.add(tokens.double())
      }
    }
    val xyz = clipToWorld(p, vertices.toArray()) ?: return null
    return DxfEntity.Wipeout(xyz).also { it.common.copyFrom(common) }
  }

  /**
   * Контур картинки в мире: [p] — вставка, U, V (по 3) и размер в пикселях (2), [clip] — вершины в пикселях (14/24).
   * Контура нет — вся картинка, два угла (прямоугольник) разворачиваются в четыре. Меньше трёх вершин — null.
   */
  private fun clipToWorld(p: DoubleArray, clip: DoubleArray): DoubleArray? {
    var uv = clip
    if (uv.size < 4) uv = doubleArrayOf(-0.5, -0.5, p[9] - 0.5, p[10] - 0.5)
    // прямоугольник (71 = 1) — два угла: четыре вершины
    if (uv.size == 4) uv = doubleArrayOf(uv[0], uv[1], uv[2], uv[1], uv[2], uv[3], uv[0], uv[3])
    var n = uv.size / 2
    // повтор первой вершины в конце не нужен
    if (n > 3 && uv[0] == uv[n * 2 - 2] && uv[1] == uv[n * 2 - 1]) n--
    if (n < 3) return null
    // вершина (x, y) → вставка + U × (x + 1/2) + V × (высота − 1/2 − y): y картинки идёт вниз
    val xyz = DoubleArray(n * 3)
    for (i in 0 until n) {
      val u = uv[i * 2] + 0.5
      val v = p[10] - 0.5 - uv[i * 2 + 1]
      for (k in 0..2) xyz[i * 3 + k] = p[k] + p[3 + k] * u + p[6 + k] * v
    }
    return xyz
  }

  /**
   * IMAGE: вставка (10), U (11) и V (12), размер в пикселях (13/23), ссылка на IMAGEDEF (340), контур (14/24;
   * 280 — обрезка включена, кода нет — считаем включённой). Картинка не грузится: сцена получает рамку по контуру
   * и подпись с путём из IMAGEDEF в центре габарита контура.
   */
  private fun readImage(): DxfEntity? {
    val common = DxfCommon()
    val p = DoubleArray(11)
    val vertices = GrowingDoubleArray()
    var defHandle: String? = null
    var clipOn = true
    while (true) {
      advance()
      val code = tokens.code
      if (code == 0) break
      if (readCommon(common)) continue
      when (code) {
        in 10..12 -> p[(code - 10) * 3] = tokens.double()
        in 20..22 -> p[(code - 20) * 3 + 1] = tokens.double()
        in 30..32 -> p[(code - 30) * 3 + 2] = tokens.double()
        13 -> p[9] = tokens.double()
        23 -> p[10] = tokens.double()
        14, 24 -> vertices.add(tokens.double())
        340 -> defHandle = tokens.valueAsAscii().trim()
        280 -> clipOn = (tokens.intOrNull() ?: 1) != 0
      }
    }
    val xyz = clipToWorld(p, if (clipOn) vertices.toArray() else DoubleArray(0)) ?: return null
    // габарит контура вдоль единичных U и V: вершины проецируются на оси картинки
    val uLen = sqrt(p[3] * p[3] + p[4] * p[4] + p[5] * p[5])
    val vLen = sqrt(p[6] * p[6] + p[7] * p[7] + p[8] * p[8])
    if (uLen == 0.0 || vLen == 0.0) return null
    var minU = Double.MAX_VALUE; var maxU = -Double.MAX_VALUE
    var minV = Double.MAX_VALUE; var maxV = -Double.MAX_VALUE
    for (i in 0 until xyz.size / 3) {
      val dx = xyz[i * 3] - p[0]; val dy = xyz[i * 3 + 1] - p[1]; val dz = xyz[i * 3 + 2] - p[2]
      val u = (dx * p[3] + dy * p[4] + dz * p[5]) / uLen
      val v = (dx * p[6] + dy * p[7] + dz * p[8]) / vLen
      if (u < minU) minU = u
      if (u > maxU) maxU = u
      if (v < minV) minV = v
      if (v > maxV) maxV = v
    }
    val cu = (minU + maxU) / 2 / uLen
    val cv = (minV + maxV) / 2 / vLen
    return DxfEntity.Image(
      xyz, defHandle,
      p[0] + p[3] * cu + p[6] * cv, p[1] + p[4] * cu + p[7] * cv, p[2] + p[5] * cu + p[8] * cv,
      atan2(p[4], p[3]) * 180 / PI, maxU - minU, maxV - minV,
    ).also { it.common.copyFrom(common) }
  }

  /**
   * HATCH: до контуров — имя паттерна (2) и флаг сплошной заливки (70); границы (91 — число контуров; 92 — флаги контура,
   * бит 2 — ломаная, иначе набор рёбер 72: 1 LINE, 2 ARC, 3 ELLIPSE, 4 SPLINE); после 75 — поворот (52) и масштаб (41)
   * паттерна, его линии (53, 43/44, 45/46, 49), затравки (их 10/20 пропускаются) и градиент (450–470).
   * MPOLYGON ([mpolygon]) — тот же формат с отличиями: 70 — версия, флаг сплошной заливки — 71, после контуров вместо
   * 75 сразу 76, цвет заливки — 63 до градиента; контуры рисуются линией.
   */
  private fun readHatch(mpolygon: Boolean = false): DxfEntity? {
    val common = DxfCommon()
    var elevation = 0.0
    val loops = ArrayList<HatchLoop>()
    var inPaths = false
    var done = false
    // стиль заливки островов (код 75): 0 — по чётности, 1 — внешний, 2 — игнорировать вложенные
    var style = 0
    var patternName = ""
    var solidFill = false
    var patternAngle = 0.0
    var patternScale = 1.0
    val patternLines = ArrayList<HatchPatternLine>()
    // линия паттерна: p[0] — 53, p[1..2] — 43/44, p[3..4] — 45/46
    var hasLine = false
    val p = DoubleArray(5)
    var dashes = GrowingDoubleArray()
    var gradient = false
    var gradientName = ""
    var gradientAngle = 0.0
    var oneColor = false
    var tint = 0.0
    // цвета градиента: по слоту на цвет (463 начинает слот), ACI (63) и true color (421); -1 — не задан
    var slot = -1
    val gradientAci = intArrayOf(-1, -1)
    val gradientRgb = intArrayOf(-1, -1)
    // цвет заливки MPOLYGON (63 до кодов градиента)
    var fillColor: Int? = null
    var inGradient = false

    fun finishPatternLine() {
      if (hasLine) patternLines.add(HatchPatternLine(p[0], p[1], p[2], p[3], p[4], dashes.toArray()))
      hasLine = false
      p.fill(0.0)
      dashes = GrowingDoubleArray()
    }

    fun gradientColor(code: Int, value: Int) {
      val target = if (code == 63) gradientAci else gradientRgb
      // цвет без 463 перед ним — следующий слот; нужны только первые два
      if (slot < 0 || (slot < 2 && target[slot] >= 0)) slot++
      if (slot < 2) target[slot] = value
    }
    var polyline = false
    var edges = ArrayList<HatchEdge>()
    var hasLoop = false
    // ломаная границы
    var poly = GrowingDoubleArray()
    var bulges = GrowingDoubleArray()
    var closed = false
    // ребро: v[0..1] — 10/20, v[2..3] — 11/21, v[4] — 40, v[5..6] — 50/51
    var edgeType = 0
    val v = DoubleArray(7)
    var ccw = true
    var degree = 3
    var rational = false
    var knots = GrowingDoubleArray()
    var control = GrowingDoubleArray()
    var weights = GrowingDoubleArray()
    var fit = GrowingDoubleArray()
    var startTangent: DoubleArray? = null
    var endTangent: DoubleArray? = null

    fun finishEdge() {
      when (edgeType) {
        1 -> edges.add(HatchEdge.Segments(doubleArrayOf(v[0], v[1], v[2], v[3]), DoubleArray(2), closed = false))
        2 -> if (v[4] > 0.0) edges.add(HatchEdge.Arc(v[0], v[1], v[4], v[5], v[6], ccw))
        3 -> if (v[4] > 0.0) edges.add(HatchEdge.Ellipse(v[0], v[1], v[2], v[3], v[4], v[5], v[6], ccw))
        4 -> {
          val w = weights.toArray()
          val usable = rational && w.size == control.size / 2 && w.all { it > 0.0 } && w.any { it != 1.0 }
          if (control.size >= 4 || fit.size >= 4) {
            edges.add(
              HatchEdge.Spline(degree, knots.toArray(), control.toArray(), if (usable) w else null, fit.toArray(), startTangent, endTangent)
            )
          }
        }
      }
      edgeType = 0
      v.fill(0.0)
      ccw = true
      degree = 3
      rational = false
      knots = GrowingDoubleArray(); control = GrowingDoubleArray(); weights = GrowingDoubleArray(); fit = GrowingDoubleArray()
      startTangent = null; endTangent = null
    }

    fun finishLoop() {
      if (!hasLoop) return
      finishEdge()
      if (polyline) {
        if (poly.size >= 4) edges.add(HatchEdge.Segments(poly.toArray(), bulges.toArray(), closed))
      }
      if (edges.isNotEmpty()) loops.add(HatchLoop(edges))
      edges = ArrayList()
      poly = GrowingDoubleArray()
      bulges = GrowingDoubleArray()
      closed = false
      hasLoop = false
    }

    while (true) {
      advance()
      val code = tokens.code
      if (code == 0) break
      if (readCommon(common)) continue
      if (done) {
        when (code) {
          52 -> patternAngle = tokens.double()
          41 -> patternScale = tokens.double()
          53 -> { finishPatternLine(); hasLine = true; p[0] = tokens.double() }
          43 -> p[1] = tokens.double()
          44 -> p[2] = tokens.double()
          45 -> p[3] = tokens.double()
          46 -> p[4] = tokens.double()
          49 -> if (hasLine) dashes.add(tokens.double())
          450 -> { gradient = tokens.intOrNull() == 1; inGradient = true }
          452 -> oneColor = tokens.intOrNull() == 1
          460 -> gradientAngle = tokens.double()
          462 -> tint = tokens.double()
          463 -> slot = minOf(slot + 1, 2)
          63 -> if (mpolygon && !inGradient) fillColor = tokens.intOrNull() else tokens.intOrNull()?.let { gradientColor(code, it) }
          421 -> tokens.intOrNull()?.let { gradientColor(code, it) }
          470 -> gradientName = tokens.valueAsAscii().trim()
        }
        continue
      }
      if (!inPaths) {
        when (code) {
          2 -> patternName = layerName()
          30 -> elevation = tokens.double()
          70 -> if (!mpolygon) solidFill = tokens.intOrNull() == 1
          71 -> if (mpolygon) solidFill = tokens.intOrNull() == 1
          91 -> inPaths = true
        }
        continue
      }
      when (code) {
        75 -> { finishLoop(); done = true; style = (tokens.intOrNull() ?: 0).coerceIn(0, 2) }
        76 -> { finishLoop(); done = true }
        92 -> {
          finishLoop()
          hasLoop = true
          polyline = (tokens.intOrNull() ?: 0) and 2 != 0
        }
        72 -> if (!polyline) {
          finishEdge()
          edgeType = tokens.intOrNull() ?: 0
        }
        73 -> if (polyline) closed = tokens.intOrNull() == 1 else if (edgeType == 4) rational = tokens.intOrNull() == 1 else ccw = tokens.intOrNull() != 0
        94 -> degree = tokens.intOrNull() ?: degree
        10 -> if (polyline) { poly.add(tokens.double()); poly.add(0.0); bulges.add(0.0) } else if (edgeType == 4) { control.add(tokens.double()); control.add(0.0) } else v[0] = tokens.double()
        20 -> if (polyline) { if (poly.size > 0) poly.setLast(tokens.double()) } else if (edgeType == 4) { if (control.size > 0) control.setLast(tokens.double()) } else v[1] = tokens.double()
        11 -> if (edgeType == 4) { fit.add(tokens.double()); fit.add(0.0) } else v[2] = tokens.double()
        21 -> if (edgeType == 4) { if (fit.size > 0) fit.setLast(tokens.double()) } else v[3] = tokens.double()
        12 -> if (edgeType == 4) startTangent = doubleArrayOf(tokens.double(), 0.0)
        22 -> if (edgeType == 4) startTangent?.set(1, tokens.double())
        13 -> if (edgeType == 4) endTangent = doubleArrayOf(tokens.double(), 0.0)
        23 -> if (edgeType == 4) endTangent?.set(1, tokens.double())
        40 -> if (edgeType == 4) knots.add(tokens.double()) else v[4] = tokens.double()
        42 -> if (polyline) { if (bulges.size > 0) bulges.setLast(tokens.double()) } else weights.add(tokens.double())
        50 -> v[5] = tokens.double()
        51 -> v[6] = tokens.double()
      }
    }
    finishLoop()
    finishPatternLine()
    if (loops.isEmpty()) return null
    fun color(i: Int) = when {
      gradientRgb[i] >= 0 -> gradientRgb[i] and 0xFFFFFF
      gradientAci[i] >= 0 -> DxfColors.aciToRgb(gradientAci[i])
      else -> -1
    }
    val first = color(0)
    val hatchGradient = if (gradient && first >= 0) {
      // одноцветный градиент идёт от цвета к серому по оттенку (462), как в ezdxf
      val gray = (tint.coerceIn(0.0, 1.0) * 255 + 0.5).toInt()
      val second = if (oneColor || color(1) < 0) (gray shl 16) or (gray shl 8) or gray else color(1)
      HatchGradient(gradientName.uppercase(), first, second, gradientAngle)
    } else {
      null
    }
    val solid = solidFill || patternName.equals(SOLID_PATTERN, ignoreCase = true)
    val pattern = if (solid || hatchGradient != null) null else HatchPattern(patternName, patternAngle, patternScale, patternLines)
    return DxfEntity.Hatch(loops, elevation, pattern, hatchGradient, outline = mpolygon, fillColor = fillColor, style = style)
      .also { it.common.copyFrom(common) }
  }

  /**
   * MLINE: заголовок (2 имя стиля, 340 его handle, 40 масштаб, 71 флаги, 73 число элементов), затем вершины:
   * 11 точка, 12 направление сегмента, 13 направление скоса и на каждый элемент 74 + 41… (первый 41 — смещение
   * вдоль скоса), 75 + 42… (параметры заливки, не нужны). Координаты мировые.
   */
  private fun readMLine(): DxfEntity? {
    val common = DxfCommon()
    var styleName = ""
    var styleHandle: String? = null
    var scale = 1.0
    var flags = 0
    var elementCount = 0
    val xyz = GrowingDoubleArray()
    val directions = GrowingDoubleArray()
    val miters = GrowingDoubleArray()
    val offsets = GrowingDoubleArray()
    // элемент текущей вершины (по 74) и ждём ли его первый параметр 41
    var element = -1
    var firstParam = false
    while (true) {
      advance()
      val code = tokens.code
      if (code == 0) break
      if (readCommon(common)) continue
      when (code) {
        2 -> styleName = string()
        340 -> styleHandle = tokens.valueAsAscii().trim()
        40 -> scale = tokens.double()
        71 -> flags = tokens.intOrNull() ?: 0
        73 -> elementCount = (tokens.intOrNull() ?: 0).coerceAtLeast(0)
        11 -> {
          xyz.addPoint(tokens.double())
          directions.addPoint(0.0)
          miters.addPoint(0.0)
          repeat(elementCount) { offsets.add(Double.NaN) }
          element = -1
          firstParam = false
        }
        21 -> xyz.setPointCoord(1, tokens.double())
        31 -> xyz.setPointCoord(2, tokens.double())
        12 -> directions.setPointCoord(0, tokens.double())
        22 -> directions.setPointCoord(1, tokens.double())
        32 -> directions.setPointCoord(2, tokens.double())
        13 -> miters.setPointCoord(0, tokens.double())
        23 -> miters.setPointCoord(1, tokens.double())
        33 -> miters.setPointCoord(2, tokens.double())
        74 -> if (xyz.size > 0) {
          element++
          firstParam = (tokens.intOrNull() ?: 0) > 0
        }
        41 -> if (firstParam) {
          firstParam = false
          if (element in 0 until elementCount) offsets[offsets.size - elementCount + element] = tokens.double()
        }
      }
    }
    if (xyz.size < 6) return null
    return DxfEntity.MLine(
      xyz.toArray(), directions.toArray(), miters.toArray(), offsets.toArray(), elementCount, scale,
      closed = flags and MLINE_CLOSED_FLAG != 0,
      suppressStartCaps = flags and MLINE_NO_START_CAPS_FLAG != 0, suppressEndCaps = flags and MLINE_NO_END_CAPS_FLAG != 0,
      styleHandle = styleHandle, styleName = styleName,
    ).also { it.common.copyFrom(common) }
  }

  private fun readPoint(): DxfEntity {
    val common = DxfCommon()
    var x = 0.0
    var y = 0.0
    var z = 0.0
    var angle = 0.0
    while (true) {
      advance()
      if (tokens.code == 0) break
      if (readCommon(common)) continue
      when (tokens.code) {
        10 -> x = tokens.double()
        20 -> y = tokens.double()
        30 -> z = tokens.double()
        50 -> angle = tokens.double()
      }
    }
    return DxfEntity.Point(x, y, z, angle).also { it.common.copyFrom(common) }
  }

  /**
   * TEXT, ATTRIB и ATTDEF. У выровненного текста (коды 72/73/74 не 0) позиция — вторая точка (11/21/31).
   * Отражённый и перевёрнутый текст (код 71) сводится к выравниванию.
   * ATTDEF в блоке виден, только если он константный (70 & 2): у вставок для него нет ATTRIB, показывается значение
   * по умолчанию (код 1). Многострочный атрибут (R2018+, после кода 101 — вложенный MTEXT) раскладывается как MTEXT.
   */
  private fun readText(kind: Int = TEXT_KIND): List<DxfEntity.Text> {
    val isAttribute = kind != TEXT_KIND
    val common = DxfCommon()
    val p = DoubleArray(6)
    var hasSecond = false
    var height = Double.NaN
    var angle = 0.0
    var value = ""
    var styleName: String? = null
    var widthFactor = Double.NaN
    var oblique = Double.NaN
    var alignH = 0
    var alignV = 0
    var flags = 0
    var generation = 0
    // у атрибута после кода 101 идёт вложенный MTEXT со своими координатами — читаем отдельно
    var embedded: EmbeddedMText? = null
    while (true) {
      advance()
      val code = tokens.code
      if (code == 0) break
      if (code == 101 && isAttribute) embedded = EmbeddedMText()
      if (embedded != null) {
        embedded.read(code)
        continue
      }
      if (readCommon(common)) continue
      when (code) {
        10, 11 -> {
          p[(code - 10) * 3] = tokens.double()
          if (code == 11) hasSecond = true
        }
        20, 21 -> p[(code - 20) * 3 + 1] = tokens.double()
        30, 31 -> p[(code - 30) * 3 + 2] = tokens.double()
        1 -> value = string()
        7 -> styleName = layerName()
        40 -> height = tokens.double()
        41 -> widthFactor = tokens.double()
        50 -> angle = tokens.double()
        51 -> oblique = tokens.double()
        72 -> alignH = tokens.intOrNull() ?: 0
        73 -> if (!isAttribute) alignV = tokens.intOrNull() ?: 0
        74 -> if (isAttribute) alignV = tokens.intOrNull() ?: 0
        70 -> if (isAttribute) flags = tokens.intOrNull() ?: 0
        71 -> generation = tokens.intOrNull() ?: 0
      }
    }
    if (isAttribute && flags and INVISIBLE_FLAG != 0) return emptyList()
    if (kind == ATTDEF_KIND && flags and CONSTANT_FLAG == 0) return emptyList()
    if (embedded != null && embedded.value.isNotEmpty()) {
      // стиль и высота вложенного MTEXT — свои, если заданы, иначе атрибута
      val style = textStyle(embedded.styleName ?: styleName)
      val h = when {
        !embedded.height.isNaN() && embedded.height > 0 -> embedded.height
        !height.isNaN() -> height
        style.height > 0 -> style.height
        else -> 1.0
      }
      val a = if (embedded.dirX != 0.0 || embedded.dirY != 0.0) toDegrees(atan2(embedded.dirY, embedded.dirX))
        else if (!embedded.angle.isNaN()) embedded.angle else angle
      return layoutMText(
        common, embedded.x, embedded.y, embedded.z, h, a, embedded.attachment, embedded.spacing,
        embedded.value.toString(), style,
      )
    }
    val plain = replaceSpecialCodes(value)
    if (plain.isBlank()) return emptyList()
    // ширина, наклон и высота — из сущности, если заданы, иначе из стиля (код 7)
    val style = textStyle(styleName)
    if (height.isNaN()) height = if (style.height > 0) style.height else 1.0
    if (widthFactor.isNaN() || widthFactor <= 0) widthFactor = style.widthFactor
    if (oblique.isNaN()) oblique = style.obliqueDeg
    // «по ширине» (3) и «вписанный» (5) идут от первой точки до второй: направление строки — от первой ко второй.
    // Остальное выравнивание — относительно второй точки (точки выравнивания)
    val fitLength = if (hasSecond) hypot(p[3] - p[0], p[4] - p[1]) else 0.0
    val stretch = alignH == 3 || alignH == 5
    val stretched = stretch && fitLength > 0
    val aligned = hasSecond && !stretch && (alignH != 0 || alignV != 0)
    val o = if (aligned) 3 else 0
    var h = if (stretch && !stretched) 0 else alignH
    var v = if (stretch) 0 else if (h == 4) 2 else alignV
    if (stretched) angle = toDegrees(atan2(p[4] - p[1], p[3] - p[0]))
    // код 71: 2 — отражён по X, 4 — перевёрнут. Буквы остаются читаемыми, строка отражается вокруг точки
    // выравнивания: по X меняются местами «слева» и «справа», по Y — «база» и «сверху». Строке между двумя
    // точками отражение по X не важно
    if (generation and 2 != 0 && !stretched) h = when (h) {
      0 -> 2
      2 -> 0
      else -> h
    }
    if (generation and 4 != 0) v = when (v) {
      0 -> 3
      3 -> 0
      1 -> TEXT_ALIGN_UNDER_DESCENT
      else -> v
    }
    return listOf(
      DxfEntity.Text(
        p[o], p[o + 1], p[o + 2], height, angle, plain, inOcs = true, alignH = h, alignV = v,
        widthFactor = widthFactor, obliqueDeg = oblique, fitLength = if (stretched) fitLength else 0.0, font = style.font,
      ).also { it.common.copyFrom(common) }
    )
  }

  /** Вложенный MTEXT многострочного атрибута (после кода 101): коды как у MTEXT, координаты мировые. */
  private inner class EmbeddedMText {
    var x = 0.0
    var y = 0.0
    var z = 0.0
    var height = Double.NaN
    var angle = Double.NaN
    var styleName: String? = null
    var attachment = 1
    var spacing = 1.0
    var dirX = 0.0
    var dirY = 0.0
    val value = StringBuilder()

    // 41 — ширина рамки: у атрибута переноса по ширине нет
    fun read(code: Int) {
      when (code) {
        10 -> x = tokens.double()
        20 -> y = tokens.double()
        30 -> z = tokens.double()
        1, 3 -> value.append(string())
        7 -> styleName = layerName()
        40 -> height = tokens.double()
        44 -> spacing = tokens.double()
        50 -> angle = toDegrees(tokens.double())
        71 -> attachment = tokens.intOrNull() ?: 1
        11 -> dirX = tokens.double()
        21 -> dirY = tokens.double()
      }
    }
  }

  /**
   * MTEXT: форматирование снимается, каждая строка — отдельный текст. Точка вставки и направление — мировые.
   * Точка вставки — угол или середина рамки (код 71): базовая линия каждой строки считается здесь, выравнивание
   * по горизонтали — при раскладке символов. Ширина и наклон букв — из стиля (код 7).
   */
  private fun readMText(): List<DxfEntity> {
    val common = DxfCommon()
    var x = 0.0
    var y = 0.0
    var z = 0.0
    var height = Double.NaN
    var angle = 0.0
    var styleName: String? = null
    var attachment = 1
    var spacing = 1.0
    var dirX = 0.0
    var dirY = 0.0
    val value = StringBuilder()
    // рамка и колонки: R2018 пишет колонки в «Embedded Object» после кода 101, R2007–R2013 — в XDATA
    var width = 0.0
    var definedHeight = 0.0
    var columnType = 0
    var columnWidth = 0.0
    var gutter = 0.0
    val columnHeights = ArrayList<Double>()
    // плашка: флаги 90, цвет 63 / 421, коэффициент поля 45
    var fillFlags = 0
    var fillColor = TEXT_COLOR_ENTITY
    var fillTrueColor: Int? = null
    var fillScale = 1.5
    var embedded = false
    var xdataColumns = false
    var xdataKey = -1
    var xdataHeights = 0
    while (true) {
      advance()
      val code = tokens.code
      if (code == 0) break
      if (code == 101) embedded = true
      if (embedded) {
        when (code) {
          41 -> definedHeight = tokens.double()
          71 -> columnType = tokens.intOrNull() ?: 0
          44 -> columnWidth = tokens.double()
          45 -> gutter = tokens.double()
          46 -> columnHeights.add(tokens.double())
        }
        continue
      }
      if (code >= 1000) {
        // пары «код / значение» в 1070/1040: 75 тип, 48 ширина, 49 промежуток, 50 число высот и сами высоты
        when (code) {
          1000 -> xdataColumns = string() == "ACAD_MTEXT_COLUMN_INFO_BEGIN"
          1070, 1040 -> if (xdataColumns) {
            when {
              xdataHeights > 0 -> { columnHeights.add(tokens.double()); xdataHeights-- }
              xdataKey < 0 -> xdataKey = tokens.intOrNull() ?: -1
              else -> {
                when (xdataKey) {
                  75 -> columnType = tokens.intOrNull() ?: 0
                  48 -> columnWidth = tokens.double()
                  49 -> gutter = tokens.double()
                  50 -> xdataHeights = tokens.intOrNull() ?: 0
                }
                xdataKey = -1
              }
            }
          }
        }
        continue
      }
      if (readCommon(common)) continue
      when (code) {
        10 -> x = tokens.double()
        20 -> y = tokens.double()
        30 -> z = tokens.double()
        1, 3 -> value.append(string())
        7 -> styleName = layerName()
        40 -> height = tokens.double()
        41 -> width = tokens.double()
        44 -> spacing = tokens.double()
        46 -> definedHeight = tokens.double()
        // у MTEXT угол в радианах
        50 -> angle = toDegrees(tokens.double())
        71 -> attachment = tokens.intOrNull() ?: 1
        11 -> dirX = tokens.double()
        21 -> dirY = tokens.double()
        90 -> fillFlags = tokens.intOrNull() ?: 0
        63 -> fillColor = tokens.intOrNull()?.takeIf { it in 1..255 }?.let { DxfColors.aciToRgb(it) } ?: TEXT_COLOR_ENTITY
        421 -> fillTrueColor = tokens.intOrNull()?.let { it and 0xFFFFFF }
        45 -> fillScale = tokens.double()
      }
    }
    if (dirX != 0.0 || dirY != 0.0) angle = toDegrees(atan2(dirY, dirX))
    val style = textStyle(styleName)
    if (height.isNaN()) height = if (style.height > 0) style.height else 1.0
    val columns = columnType != 0 && (columnWidth > 0 || width > 0)
    // плашка: 1 — свой цвет (421 или 63), 2 — цвет окна; поле вокруг текста — (45 − 1) высоты, ширина — рамки
    val background = when {
      fillFlags and 2 != 0 -> TEXT_BACKGROUND_WINDOW
      fillFlags and 1 != 0 -> fillTrueColor ?: fillColor
      else -> null
    }?.let {
      val boxWidth = if (columns && columnWidth > 0) columnWidth else width
      DxfTextBackground(it, (fillScale - 1).coerceIn(0.0, 4.0), maxOf(boxWidth, 0.0))
    }
    val frame = when {
      columns -> MTextFrame(
        if (columnWidth > 0) columnWidth else width, mTextPitch(height, spacing), attachment,
        columnWidth = if (columnWidth > 0) columnWidth else width, gutter = gutter, height = definedHeight,
        columnHeights = columnHeights.toDoubleArray(),
      )
      width > 0 -> MTextFrame(width, mTextPitch(height, spacing), attachment)
      else -> null
    }
    return layoutMText(common, x, y, z, height, angle, attachment, spacing, value.toString(), style, frame, background)
  }

  private fun mTextPitch(height: Double, spacing: Double) = height * MTEXT_LINE_PITCH * (if (spacing > 0) spacing else 1.0)

  /**
   * Раскладывает текст MTEXT (и MULTILEADER) по строкам: у каждой строки своя базовая линия в мировых координатах.
   * Высота строки — наибольшая у её фрагментов (\H), шаг до строки — по её высоте.
   */
  private fun layoutMText(
    common: DxfCommon, x: Double, y: Double, z: Double, height: Double, angle: Double,
    attachment: Int, spacing: Double, raw: String, style: DxfTextStyle = DxfTextStyle.STANDARD,
    frame: MTextFrame? = null, background: DxfTextBackground? = null,
  ): List<DxfEntity.Text> {
    val content = parseMText(raw, height)
    val text = content.text
    if (frame != null) {
      // строки по ширине рамки раскладываются при построении сцены: там известны метрики шрифта
      if (text.all { it.isWhitespace() || it == MTEXT_NBSP || it == MTEXT_COLUMN_BREAK }) return emptyList()
      return listOf(
        DxfEntity.Text(
          x, y, z, height, angle, text, inOcs = false, alignH = (attachment.coerceIn(1, 9) - 1) % 3,
          widthFactor = style.widthFactor, obliqueDeg = style.obliqueDeg, frame = frame,
          runs = content.runs, background = background, font = style.font,
        ).also { it.common.copyFrom(common) },
      )
    }
    // строки — отрезки текста [начало, конец) без пробелов в конце; разрыв колонки без рамки — перенос строки
    val starts = ArrayList<Int>()
    val ends = ArrayList<Int>()
    var start = 0
    var i = 0
    while (i <= text.length) {
      val c = if (i < text.length) text[i] else '\n'
      if (c == '\n' || c == '\r' || c == MTEXT_COLUMN_BREAK) {
        var end = i
        while (end > start && text[end - 1].isWhitespace()) end--
        starts.add(start)
        ends.add(end)
        if (c == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
        start = i + 1
      }
      i++
    }
    if (starts.indices.all { starts[it] == ends[it] }) return emptyList()

    val lines = starts.indices.map { mTextLine(content.runs, starts[it], ends[it]) }
    val pitch = mTextPitch(height, spacing)
    val depth = DoubleArray(lines.size)
    for (index in lines.indices) {
      depth[index] = if (index == 0) height * lines[0].scale else depth[index - 1] + pitch * lines[index].scale
    }
    val blockHeight = depth.last()
    val anchor = attachment.coerceIn(1, 9) - 1
    val top = when (anchor / 3) {
      0 -> 0.0
      1 -> blockHeight / 2
      else -> blockHeight
    }
    val radians = toRadians(angle)
    return lines.indices.mapNotNull { index ->
      if (starts[index] == ends[index]) return@mapNotNull null
      // по горизонтали строка выравнивается при раскладке по настоящей ширине символов (слева / по центру / справа)
      val dy = top - depth[index]
      val line = lines[index]
      DxfEntity.Text(
        x - dy * sin(radians),
        y + dy * cos(radians),
        z, height * line.scale, angle, text.substring(starts[index], ends[index]).replace(MTEXT_NBSP, ' '),
        inOcs = false, alignH = anchor % 3,
        widthFactor = style.widthFactor, obliqueDeg = style.obliqueDeg,
        runs = line.runs, background = background, font = style.font,
      ).also { it.common.copyFrom(common) }
    }
  }

  /**
   * TOLERANCE (рамка допуска): точка вставки 10 — середина левого края первой строки, направление X 11, нормаль 210.
   * Высота текста и зазор — DIMTXT и DIMGAP (× DIMSCALE) стиля размеров 3 или переопределения в XDATA `DSTYLE`.
   * Высота строки рамки — текст плюс два зазора, ячейка — по оценке ширины текста, не уже высоты строки.
   * Строки рамки — замкнутые ломаные, перегородки ячеек — отрезки, тексты — подписи по центру ячеек. Всё мировое.
   */
  private fun readTolerance(): List<DxfEntity> {
    val common = DxfCommon()
    val p = DoubleArray(3)
    val dir = doubleArrayOf(1.0, 0.0, 0.0)
    var value = ""
    var styleName: String? = null
    val dimOverrides = DimStyleOverrides()
    val overrides = dimOverrides.values
    while (true) {
      advance()
      val code = tokens.code
      if (code == 0) break
      if (dimOverrides.read()) continue
      if (readCommon(common)) continue
      when (code) {
        10, 20, 30 -> p[code / 10 - 1] = tokens.double()
        11, 21, 31 -> dir[code / 10 - 1] = tokens.double()
        1 -> value = string()
        3 -> styleName = string()
      }
    }
    val rows = parseTolerance(value)
    if (rows.isEmpty()) return emptyList()

    val style = dimStyles[(styleName ?: "STANDARD").uppercase()] ?: headerDimStyle
    val scale = (overrides[40] ?: style[DIM_SCALE]).takeIf { it > 0 } ?: 1.0
    val height = ((overrides[140] ?: style[DIM_TXT]).takeIf { it > 0 } ?: DEFAULT_DIMTXT) * scale
    val gap = abs(overrides[147] ?: style[DIM_GAP]) * scale
    val rowHeight = height + 2 * gap

    // ось X — по 11/21/31, ось Y — нормаль × X
    var xLen = hypot(hypot(dir[0], dir[1]), dir[2])
    if (xLen == 0.0) {
      dir[0] = 1.0
      xLen = 1.0
    }
    for (i in 0..2) dir[i] /= xLen
    val up = doubleArrayOf(
      common.ny * dir[2] - common.nz * dir[1],
      common.nz * dir[0] - common.nx * dir[2],
      common.nx * dir[1] - common.ny * dir[0],
    )
    val upLen = hypot(hypot(up[0], up[1]), up[2])
    if (upLen == 0.0) {
      up[0] = -dir[1]
      up[1] = dir[0]
      up[2] = 0.0
    } else {
      for (i in 0..2) up[i] /= upLen
    }
    val angle = toDegrees(atan2(dir[1], dir[0]))
    fun at(u: Double, v: Double, axis: Int) = p[axis] + dir[axis] * u + up[axis] * v

    val result = ArrayList<DxfEntity>()
    rows.forEachIndexed { row, cells ->
      val top = rowHeight / 2 - row * rowHeight
      val bottom = top - rowHeight
      val middle = top - rowHeight / 2
      var u = 0.0
      for ((index, cell) in cells.withIndex()) {
        val width = maxOf(rowHeight, estimateTextWidth(cell, height) + 2 * gap)
        if (index > 0) {
          result += DxfEntity.Line(DoubleArray(6) { at(u, if (it < 3) top else bottom, it % 3) })
            .also { it.common.copyFrom(common) }
        }
        val c = u + width / 2
        result += DxfEntity.Text(
          at(c, middle, 0), at(c, middle, 1), at(c, middle, 2),
          height, angle, cell, inOcs = false, alignH = 1, alignV = 2,
        ).also { it.common.copyFrom(common) }
        u += width
      }
      val corners = doubleArrayOf(0.0, top, u, top, u, bottom, 0.0, bottom)
      val xyz = DoubleArray(12) { at(corners[it / 3 * 2], corners[it / 3 * 2 + 1], it % 3) }
      result += DxfEntity.Polyline(xyz, DoubleArray(4), closed = true, inOcs = false)
        .also { it.common.copyFrom(common) }
    }
    return result
  }

  /** Оценка ширины строки без метрик шрифта: точки и пробелы узкие, остальное — 0,8 высоты букв. */
  private fun estimateTextWidth(text: String, height: Double): Double =
    text.sumOf { if (it == '.' || it == ',' || it == ' ') 0.35 else 0.8 } * height

  private fun readInsert(): DxfEntity? {
    val common = DxfCommon()
    var name = ""
    val p = DoubleArray(3)
    var sx = 1.0
    var sy = 1.0
    var sz = 1.0
    var angle = 0.0
    var columns = 1
    var rows = 1
    var columnSpacing = 0.0
    var rowSpacing = 0.0
    while (true) {
      advance()
      if (tokens.code == 0) break
      if (readCommon(common)) continue
      when (tokens.code) {
        2 -> name = string()
        10 -> p[0] = tokens.double()
        20 -> p[1] = tokens.double()
        30 -> p[2] = tokens.double()
        41 -> sx = tokens.double()
        42 -> sy = tokens.double()
        43 -> sz = tokens.double()
        50 -> angle = tokens.double()
        70 -> columns = tokens.intOrNull() ?: 1
        71 -> rows = tokens.intOrNull() ?: 1
        44 -> columnSpacing = tokens.double()
        45 -> rowSpacing = tokens.double()
      }
    }

    // атрибуты вхождения — отдельными сущностями ATTRIB до SEQEND, координаты — уже на месте вхождения
    val attributes = ArrayList<DxfEntity.Text>()
    while (tokens.valueEquals(ATTRIB)) attributes += readText(ATTRIB_KIND)
    if (tokens.valueEquals(SEQEND)) skipEntity()
    if (name.isEmpty()) return null
    return DxfEntity.Insert(name, p[0], p[1], p[2], sx, sy, sz, angle, columns, rows, columnSpacing, rowSpacing, attributes)
      .also { it.common.copyFrom(common) }
  }

  // геометрия размеров и таблиц лежит в анонимном блоке (*D…, *T…) уже в мировых координатах
  private fun readAnonymousBlockRef(): DxfEntity? {
    val common = DxfCommon()
    var name = ""
    while (true) {
      advance()
      if (tokens.code == 0) break
      if (tokens.code == 2) name = string()
      // нормаль у размеров не про блок: не применяем
      if (tokens.code in 210..230) continue
      readCommon(common)
    }
    if (name.isEmpty()) return null
    return DxfEntity.Insert(name, 0.0, 0.0, 0.0, 1.0, 1.0, 1.0, 0.0, 1, 1, 0.0, 0.0, emptyList())
      .also { it.common.copyFrom(common) }
  }

  private fun skipEntity() {
    do advance() while (tokens.code != 0)
  }

  /**
   * Графика прокси ACAD_PROXY_ENTITY ([isProxyEntity]) или неизвестной сущности: размер (92, в R2013+ 160) и данные (310).
   * У прокси за графикой идут данные сущности (93 и свои 310) — они не нужны. У неизвестной сущности графику ищем
   * только в разделе AcDbEntity. Нет графики или она не разобралась — сущность пропускается.
   */
  private fun readProxyGraphics(isProxyEntity: Boolean): List<DxfEntity> {
    val common = DxfCommon()
    var graphics: ByteArray? = null
    var size = 0
    var subclass = ""
    var collecting = false
    while (true) {
      advance()
      val code = tokens.code
      if (code == 0) break
      if (readCommon(common)) continue
      when (code) {
        100 -> {
          subclass = string()
          if (!isProxyEntity) collecting = false
        }
        92, 160 -> if (isProxyEntity || subclass == "AcDbEntity") {
          collecting = true
          if (graphics == null) graphics = ByteArray(4096)
        }
        93 -> if (isProxyEntity) collecting = false
        310 -> if (collecting && size < MAX_PROXY_BYTES) {
          val chunk = graphics ?: continue
          if (size + MAX_CHUNK_BYTES > chunk.size) graphics = chunk.copyOf(maxOf(chunk.size * 2, size + MAX_CHUNK_BYTES))
          size += readChunk(graphics, size)
        }
      }
    }
    val data = graphics ?: return emptyList()
    if (size < 16) return emptyList()
    return decodeProxyGraphics(data.copyOf(size), common, { layers.keys.toList() }, acadVersion >= "AC1024", decoder::decode)
  }

  /** Байты блока 310 в [out] с позиции [at]: в ASCII DXF — шестнадцатеричная строка, в бинарном — сами байты. Возвращает их число. */
  private fun readChunk(out: ByteArray, at: Int): Int {
    val buf = tokens.buffer
    var i = tokens.valueStart
    val end = tokens.valueEnd
    var n = 0
    if (tokens is DxfBinaryTokenizer) {
      n = minOf(end - i, MAX_CHUNK_BYTES)
      buf.copyInto(out, at, i, i + n)
      return n
    }
    var high = -1
    while (i < end && n < MAX_CHUNK_BYTES) {
      val digit = hexDigit(buf[i++].toInt())
      if (digit < 0) continue
      if (high < 0) high = digit else {
        out[at + n++] = ((high shl 4) or digit).toByte()
        high = -1
      }
    }
    return n
  }

  private fun hexDigit(c: Int): Int = when (c) {
    in '0'.code..'9'.code -> c - '0'.code
    in 'A'.code..'F'.code -> c - 'A'.code + 10
    in 'a'.code..'f'.code -> c - 'a'.code + 10
    else -> -1
  }

  private fun layerName(): String = names.get(tokens.buffer, tokens.valueStart, tokens.valueEnd)

  // handle — шестнадцатеричное число; не число — 0 (нет)
  private fun handle(): Long {
    var value = 0L
    for (i in tokens.valueStart until tokens.valueEnd) {
      val c = tokens.buffer[i].toInt()
      val digit = when (c) {
        in '0'.code..'9'.code -> c - '0'.code
        in 'A'.code..'F'.code -> c - 'A'.code + 10
        in 'a'.code..'f'.code -> c - 'a'.code + 10
        ' '.code, '\t'.code, '\r'.code -> continue
        else -> return 0L
      }
      value = (value shl 4) or digit.toLong()
    }
    return value
  }

  private fun string(): String = decoder.decode(tokens.buffer, tokens.valueStart, tokens.valueEnd)

  private fun isAt(marker: ByteArray): Boolean = tokens.code == 0 && tokens.valueEquals(marker)

  private fun advance() {
    if (!tokens.next()) throw DxfFormatException(UiText.key("error_dxf_eof", tokens.lineNumber))
  }

  private companion object {
    const val PROGRESS_STEP = 4096
    // потолок графики прокси одной сущности и блока 310 (в ASCII — 127 байт, в бинарном — до 255)
    const val MAX_PROXY_BYTES = 64 * 1024 * 1024
    const val MAX_CHUNK_BYTES = 255
    const val CLOSED_FLAG = 1
    const val PLINEGEN_FLAG = 128
    // код 74 элемента LTYPE
    const val LTYPE_ABSOLUTE = 1
    const val LTYPE_TEXT = 2
    const val LTYPE_SHAPE = 4
    const val POLYLINE_3D_FLAG = 8
    const val POLYGON_MESH_FLAG = 16
    const val MESH_CLOSED_N_FLAG = 32
    const val POLYFACE_FLAG = 64
    const val SPLINE_FRAME_FLAG = 16
    const val FACE_VERTEX_FLAG = 64
    const val FACE_RECORD_FLAG = 128
    const val INVISIBLE_FLAG = 1
    const val MLINE_CLOSED_FLAG = 2
    const val MLINE_NO_START_CAPS_FLAG = 4
    const val MLINE_NO_END_CAPS_FLAG = 8
    const val BY_LAYER_NAME = "BYLAYER"
    const val BY_BLOCK_NAME = "BYBLOCK"
    // флаги переопределения MULTILEADER (код 90): свойство взято из сущности, а не из стиля
    const val MLEADER_LINE_COLOR = 0x2
    const val MLEADER_LINE_TYPE = 0x4
    const val MLEADER_LINE_WEIGHT = 0x8
    const val MLEADER_CONTENT_TYPE = 0x400
    const val MLEADER_BLOCK = 0x40000
    const val MLEADER_BLOCK_COLOR = 0x80000
    const val MLEADER_CONTENT_BLOCK = 1
    const val CONTINUOUS_NAME = "CONTINUOUS"
    // константный ATTDEF (70 & 2): у вставок для него нет ATTRIB
    const val CONSTANT_FLAG = 2
    const val TEXT_KIND = 0
    const val ATTRIB_KIND = 1
    const val ATTDEF_KIND = 2
    const val HEAD_SIZE = 32
    // имя паттерна сплошной заливки HATCH
    const val SOLID_PATTERN = "SOLID"
    const val MODEL_SPACE = "*Model_Space"

    // межстрочный интервал MTEXT по умолчанию — 5/3 высоты букв
    const val MTEXT_LINE_PITCH = 5.0 / 3.0

    // DIMTXT, DIMGAP, DIMSCALE в массиве стиля размеров; по умолчанию — как в AutoCAD
    const val DIM_TXT = 0
    const val DIM_GAP = 1
    const val DIM_SCALE = 2
    const val DIM_ASZ = 3
    const val DEFAULT_DIMTXT = 0.18
    const val DEFAULT_DIMGAP = 0.09
    const val DEFAULT_DIMASZ = 0.18
    // полуширина стрелки Closed filled — шестая часть её длины
    const val ARROW_HALF_WIDTH = 1.0 / 6

    val EOF = "EOF".ascii()
    val SECTION = "SECTION".ascii()
    val ENDSEC = "ENDSEC".ascii()
    val HEADER = "HEADER".ascii()
    val TABLES = "TABLES".ascii()
    val TABLE = "TABLE".ascii()
    val ENDTAB = "ENDTAB".ascii()
    val LAYER = "LAYER".ascii()
    val AC_CM_TRANSPARENCY = "AcCmTransparency".ascii()
    val LTYPE = "LTYPE".ascii()
    val VPORT = "VPORT".ascii()
    val VIEWPORT = "VIEWPORT".ascii()
    val LAYOUT = "LAYOUT".ascii()
    val ACDB_LAYOUT = "AcDbLayout".ascii()
    const val PAPER_SPACE = "*Paper_Space"
    val STYLE = "STYLE".ascii()
    val BLOCKS = "BLOCKS".ascii()
    val BLOCK = "BLOCK".ascii()
    val ENDBLK = "ENDBLK".ascii()
    val ENTITIES = "ENTITIES".ascii()
    val OBJECTS = "OBJECTS".ascii()
    val ACDSDATA = "ACDSDATA".ascii()
    val SORTENTSTABLE = "SORTENTSTABLE".ascii()
    val WIPEOUT = "WIPEOUT".ascii()
    val WIPEOUTVARIABLES = "WIPEOUTVARIABLES".ascii()
    val IMAGE = "IMAGE".ascii()
    val IMAGEDEF = "IMAGEDEF".ascii()
    val BLOCK_RECORD = "BLOCK_RECORD".ascii()
    val FACE_3D = "3DFACE".ascii()
    val LINE = "LINE".ascii()
    val ACAD_PROXY_ENTITY = "ACAD_PROXY_ENTITY".ascii()
    val XLINE = "XLINE".ascii()
    val RAY = "RAY".ascii()
    val OLE2FRAME = "OLE2FRAME".ascii()
    val LWPOLYLINE = "LWPOLYLINE".ascii()
    val POLYLINE = "POLYLINE".ascii()
    val LEADER = "LEADER".ascii()
    val MULTILEADER = "MULTILEADER".ascii()
    val CONTEXT_DATA_OPEN = "CONTEXT_DATA{".ascii()
    val LEADER_OPEN = "LEADER{".ascii()
    val LEADER_LINE_OPEN = "LEADER_LINE{".ascii()
    val MESH = "MESH".ascii()
    val VERTEX = "VERTEX".ascii()
    val SEQEND = "SEQEND".ascii()
    val CIRCLE = "CIRCLE".ascii()
    val ARC = "ARC".ascii()
    val ELLIPSE = "ELLIPSE".ascii()
    val SPLINE = "SPLINE".ascii()
    val HELIX = "HELIX".ascii()
    val ACDB_HELIX = "AcDbHelix".ascii()
    val SOLID = "SOLID".ascii()
    val HATCH = "HATCH".ascii()
    val MPOLYGON = "MPOLYGON".ascii()
    val TRACE = "TRACE".ascii()
    val MLINE = "MLINE".ascii()
    val MLINESTYLE = "MLINESTYLE".ascii()
    val MLEADERSTYLE = "MLEADERSTYLE".ascii()
    val POINT = "POINT".ascii()
    val TEXT = "TEXT".ascii()
    val MTEXT = "MTEXT".ascii()
    val INSERT = "INSERT".ascii()
    val TOLERANCE = "TOLERANCE".ascii()
    val SOLID_3D = "3DSOLID".ascii()
    val REGION = "REGION".ascii()
    val BODY = "BODY".ascii()
    val SURFACES = listOf("SURFACE", "PLANESURFACE", "EXTRUDEDSURFACE", "LOFTEDSURFACE", "REVOLVEDSURFACE",
      "SWEPTSURFACE", "NURBSSURFACE").map { it.ascii() }
    val ACDB_ENTITY = "AcDbEntity".ascii()
    val ACDB_MODELER_GEOMETRY = "AcDbModelerGeometry".ascii()
    val DIMSTYLE = "DIMSTYLE".ascii()
    val ATTRIB = "ATTRIB".ascii()
    val ATTDEF = "ATTDEF".ascii()
    val DIMENSION = "DIMENSION".ascii()
    val ACAD_TABLE = "ACAD_TABLE".ascii()
    val ARC_DIMENSION = "ARC_DIMENSION".ascii()
    val LARGE_RADIAL_DIMENSION = "LARGE_RADIAL_DIMENSION".ascii()
    val BINARY_SIGNATURE = "AutoCAD Binary DXF".ascii()

    fun String.ascii(): ByteArray = encodeToByteArray()

    // бинарный DXF выбирается по сигнатуре; ASCII: пропускает UTF-8 BOM, UTF-16 перекодирует в UTF-8
    // (служебной части DXF нужна ASCII-совместимая кодировка)
    fun openTokens(input: ByteInput): DxfTokenSource {
      val head = ByteArray(HEAD_SIZE)
      var headSize = 0
      while (headSize < head.size) {
        val n = input.read(head, headSize, head.size - headSize)
        if (n < 0) break
        headSize += n
      }

      val isBinary = headSize >= BINARY_SIGNATURE.size && BINARY_SIGNATURE.indices.all { head[it] == BINARY_SIGNATURE[it] }
      if (isBinary) return DxfBinaryTokenizer(PrefixedInput(head, 0, headSize, input))

      val b0 = head[0].toInt() and 0xFF
      val b1 = head[1].toInt() and 0xFF
      val b2 = head[2].toInt() and 0xFF
      return DxfTokenizer(
        when {
          headSize >= 3 && b0 == 0xEF && b1 == 0xBB && b2 == 0xBF -> PrefixedInput(head, 3, headSize, input)
          headSize >= 2 && b0 == 0xFF && b1 == 0xFE -> transcode(head, headSize, input, bigEndian = false)
          headSize >= 2 && b0 == 0xFE && b1 == 0xFF -> transcode(head, headSize, input, bigEndian = true)
          else -> PrefixedInput(head, 0, headSize, input)
        }
      )
    }

    fun transcode(head: ByteArray, headSize: Int, input: ByteInput, bigEndian: Boolean): ByteInput {
      val bytes = PrefixedInput(head, 0, headSize, input).readAllBytes()
      return ByteArrayInput(decodeUtf16(bytes, start = 2, bigEndian).encodeToByteArray())
    }
  }
}

