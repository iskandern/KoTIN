package dev.iskandern.kotin.landxml

import dev.iskandern.kotin.io.ByteInput
import dev.iskandern.kotin.io.PrefixedInput
import dev.iskandern.kotin.io.codePageDecoder
import dev.iskandern.kotin.io.isValidUtf8
import dev.iskandern.kotin.io.latin1
import dev.iskandern.kotin.model.UiText

/**
 * Ошибка разбора XML; [offset] — смещение в байтах от начала файла (для UTF-16 — в перекодированном потоке).
 * [message] — ключ [UiText]; смещение дописывается последним аргументом: у строк ошибок XML в конце «(байт …)».
 */
class XmlFormatException(message: String, val offset: Long) : Exception("$message${UiText.SEPARATOR}$offset")

/**
 * Набор известных имён элементов: поиск по байтам без создания строк.
 * Номер имени — его место в списке; незнакомое имя — [XmlTokenizer.UNKNOWN].
 */
class XmlNames(vararg names: String) {
  private val bytes = Array(names.size) { names[it].encodeToByteArray() }
  private val table: IntArray
  private val mask: Int

  init {
    var size = 16
    while (size < names.size * 2) size *= 2
    table = IntArray(size)
    mask = size - 1
    for (i in bytes.indices) {
      var slot = hash(bytes[i], 0, bytes[i].size) and mask
      while (table[slot] != 0) slot = (slot + 1) and mask
      table[slot] = i + 1
    }
  }

  fun find(buf: ByteArray, start: Int, end: Int): Int {
    var slot = hash(buf, start, end) and mask
    while (true) {
      val entry = table[slot]
      if (entry == 0) return XmlTokenizer.UNKNOWN
      if (equalsRange(bytes[entry - 1], buf, start, end)) return entry - 1
      slot = (slot + 1) and mask
    }
  }

  private fun hash(buf: ByteArray, start: Int, end: Int): Int {
    var h = 0
    for (i in start until end) h = 31 * h + buf[i]
    return h xor (h ushr 16)
  }
}

/**
 * Потоковый «вытягивающий» разбор XML поверх [ByteInput] — без DOM и без строки на каждый элемент.
 *
 * События: [START] (открывающий тег), [END] (закрывающий; у `<a/>` — сразу после [START]), [TEXT], [EOF].
 * Имена сравниваются по локальному имени без префикса ([nameId] из [XmlNames]), поэтому версии LandXML
 * с разными пространствами имён читаются одинаково. Комментарии, `<?…?>`, `<!DOCTYPE …>` пропускаются;
 * текст из одних пробелов событием не считается.
 *
 * Атрибуты не разбираются, пока их не попросили ([findAttr], [attrDouble], …): читать их надо сразу после
 * [START], до чтения текста. Текст элемента — диапазон байтов ([readText]) или поток чисел ([nextNumber]):
 * длинные списки координат читаются без накопления текста.
 *
 * Разделы читают свои вложенные элементы сами:
 * ```
 * val depth = xml.depth
 * while (xml.nextChild(depth)) when (xml.nameId) { P -> …; else -> xml.skipElement() }
 * ```
 *
 * Кодировка: UTF-8 (с BOM и без), UTF-16 (с BOM или без) перекодируется в UTF-8 на лету; однобайтовая из
 * `<?xml encoding="…"?>` — при декодировании строк. Без объявления строка, которая не UTF-8, читается как
 * windows-1251 (выгрузки «ANSI»). Структура XML — ASCII, поэтому разбор по байтам от кодировки не зависит.
 */
class XmlTokenizer(
  input: ByteInput,
  /** Имена для [nameId]; раздел LandXML ставит свои на время чтения своего элемента. */
  var names: XmlNames,
  initialBufferSize: Int = DEFAULT_BUFFER_SIZE,
) : AutoCloseable {
  private val source = CountingInput(input)
  private val input: ByteInput
  private var bomLength = 0

  // буфер растёт до [initialBufferSize], только если файл больше: маленький файл не платит за 256 КБ
  private val fullBufferSize = initialBufferSize

  /** Буфер с байтами документа; диапазоны [numberStart]…, [textStart]… указывают в него до следующего чтения. */
  var buffer = ByteArray(minOf(initialBufferSize, START_BUFFER_SIZE))
    private set
  private var pos = 0
  private var limit = 0
  private var eof = false
  // последнее чтение заполнило буфер до конца — данных, скорее всего, больше
  private var lastReadFull = false
  // сколько байтов уже сдвинуто из буфера — для смещений в сообщениях
  private var discarded = 0L

  /** Прочитано байтов файла — для прогресса. */
  val bytesRead: Long get() = source.count

  /** Создано строк ([localName], [attrString], [textString], …) — горячие циклы проверяют, что 0. */
  var stringsCreated = 0L
    private set

  var event = NONE
    private set

  /** Номер имени текущего элемента в [XmlNames] или [UNKNOWN]; у [END] — имя закрытого элемента. */
  var nameId = UNKNOWN
    private set

  /** Уровень текущего элемента: корень — 1. У [END] — уровень закрываемого элемента. */
  var depth = 0
    private set

  /** У текущего [START] нет содержимого (`<a/>`). */
  var isEmptyElement = false
    private set

  // стек имён открытых элементов (полные имена с префиксом) — для проверки закрывающих тегов
  private var nameStack = ByteArray(1024)
  private val nameOffsets = IntArray(MAX_DEPTH + 2)
  private val idStack = IntArray(MAX_DEPTH + 2)
  private var pendingEnd = false
  private var popPending = false
  private var inText = false

  // атрибуты текущего START: диапазон в буфере после имени, до '>' или '/>'
  private var attrsStart = 0
  private var attrsEnd = 0
  private var tagOffset = 0L
  private var attrNameStart = 0
  private var attrNameEnd = 0
  private var attrValueStart = 0
  private var attrValueEnd = 0

  /** Значение атрибута после [findAttr]: диапазон в [valueBuffer] (сущности уже раскрыты). */
  var valueBuffer: ByteArray = buffer
    private set
  var valueStart = 0
    private set
  var valueEnd = 0
    private set
  private var valueScratch = ByteArray(256)

  /** Текущее число после [nextNumber] — диапазон в [buffer]. */
  var numberStart = 0
    private set
  var numberEnd = 0
    private set
  private var numberOffset = 0L

  /** Текст после [readText] — диапазон в [textBuffer]. */
  var textBuffer: ByteArray = buffer
    private set
  var textStart = 0
    private set
  var textEnd = 0
    private set
  private var textScratch = ByteArray(1024)

  private var declaredDecoder: ((ByteArray, Int, Int) -> String)? = null
  private var declarationAllowed = true
  private var rootSeen = false

  init {
    val head = ByteArray(4)
    var n = 0
    while (n < head.size) {
      val read = source.read(head, n, head.size - n)
      if (read < 0) break
      n += read
    }
    fun at(i: Int) = if (i < n) head[i].toInt() and 0xFF else -1
    this.input = when {
      at(0) == 0xFF && at(1) == 0xFE -> Utf16ToUtf8Input(PrefixedInput(head, 2, n, source), bigEndian = false)
      at(0) == 0xFE && at(1) == 0xFF -> Utf16ToUtf8Input(PrefixedInput(head, 2, n, source), bigEndian = true)
      // UTF-16 без BOM: '<' и нулевой байт (в UTF-8 нулей не бывает)
      at(0) == 0x3C && at(1) == 0 -> Utf16ToUtf8Input(PrefixedInput(head, 0, n, source), bigEndian = false)
      at(0) == 0 && at(1) == 0x3C -> Utf16ToUtf8Input(PrefixedInput(head, 0, n, source), bigEndian = true)
      at(0) == 0xEF && at(1) == 0xBB && at(2) == 0xBF -> {
        bomLength = 3
        PrefixedInput(head, 3, n, source)
      }
      else -> PrefixedInput(head, 0, n, source)
    }
  }

  override fun close() = input.close()

  /** Следующее событие. */
  fun next(): Int {
    if (popPending) {
      popPending = false
      depth--
    }
    if (pendingEnd) {
      pendingEnd = false
      popPending = true
      event = END
      nameId = idStack[depth]
      return END
    }
    if (inText) {
      skipText()
      inText = false
    }
    while (true) {
      if (!skipSpaces()) {
        if (depth > 0) error(UiText.key("xml_unclosed", stackName(depth)), position())
        if (!rootSeen) error(UiText.key("xml_no_elements"), position())
        event = EOF
        return EOF
      }
      if (buffer[pos] != LT) {
        // текст вне корня (мусор в конце файла) пропускается
        if (depth == 0) {
          skipText()
          continue
        }
        inText = true
        event = TEXT
        return TEXT
      }
      if (!ensure(2)) error("@xml_open_tag", position())
      when (buffer[pos + 1]) {
        SLASH -> {
          readEndTag()
          return END
        }
        QUESTION -> {
          skipProcessingInstruction()
          continue
        }
        BANG -> {
          if (startsWith(CDATA_OPEN)) {
            if (depth == 0) error(UiText.key("xml_cdata_outside"), position())
            inText = true
            event = TEXT
            return TEXT
          }
          if (startsWith(COMMENT_OPEN)) {
            pos += COMMENT_OPEN.size
            skipPast(COMMENT_CLOSE)
          } else {
            skipDeclaration()
          }
          continue
        }
        else -> {
          readStartTag()
          return START
        }
      }
    }
  }

  /**
   * Следующий дочерний элемент элемента уровня [parentDepth]; false — элемент закрылся.
   * Текст и непрочитанные вложенные элементы пропускаются.
   */
  fun nextChild(parentDepth: Int): Boolean {
    while (true) {
      when (next()) {
        START -> if (depth == parentDepth + 1) return true
        END -> if (depth == parentDepth) return false
        EOF -> error("@xml_eof", position())
      }
    }
  }

  /** Пропускает текущий элемент ([START]) со всем вложенным; после вызова событие — его [END]. */
  fun skipElement() {
    check(event == START) { "@error_skip_element" }
    val level = depth
    while (true) {
      val e = next()
      if (e == END && depth == level) return
      if (e == EOF) error("@xml_eof", position())
    }
  }

  /** Локальное имя текущего элемента (создаёт строку). */
  fun localName(): String {
    val start = nameOffsets[depth - 1]
    val end = nameOffsets[depth]
    var local = start
    for (i in start until end) {
      if (nameStack[i] == COLON) local = i + 1
    }
    stringsCreated++
    return latin1(nameStack, local, end)
  }

  // --- атрибуты ---

  /** Ищет атрибут текущего [START] по имени; true — значение в [valueBuffer] от [valueStart] до [valueEnd]. */
  fun findAttr(name: ByteArray): Boolean {
    if (event != START) return false
    var i = attrsStart
    while (true) {
      i = nextAttribute(i)
      if (i < 0) return false
      if (equalsRange(name, buffer, attrNameStart, attrNameEnd)) {
        setValue()
        return true
      }
    }
  }

  /** Атрибут-число; нет атрибута — NaN, не число — исключение. */
  fun attrDouble(name: ByteArray): Double {
    if (!findAttr(name)) return Double.NaN
    val value = LandXmlNumbers.parseDouble(valueBuffer, valueStart, valueEnd)
    if (value.isNaN()) error(UiText.key("xml_attr_not_number", name.decodeToString(), latin1(valueBuffer, valueStart, valueEnd)), tagOffset)
    return value
  }

  /** Атрибут-целое; нет атрибута — [default], не целое — исключение. */
  fun attrInt(name: ByteArray, default: Int): Int {
    if (!findAttr(name)) return default
    val value = LandXmlNumbers.parseInt(valueBuffer, valueStart, valueEnd)
    if (value == LandXmlNumbers.NOT_INT) {
      error(UiText.key("xml_attr_not_int", name.decodeToString(), latin1(valueBuffer, valueStart, valueEnd)), tagOffset)
    }
    return value.toInt()
  }

  /** Значение атрибута равно ASCII-строке [expected]. */
  fun attrEquals(name: ByteArray, expected: ByteArray): Boolean =
    findAttr(name) && equalsRange(expected, valueBuffer, valueStart, valueEnd)

  fun attrString(name: ByteArray): String? = if (findAttr(name)) decode(valueBuffer, valueStart, valueEnd) else null

  fun attrString(name: String): String? = attrString(name.encodeToByteArray())

  /** Все атрибуты текущего [START], кроме `xmlns`, — для проверок и отладки, не для горячего пути. */
  fun attributes(): List<Pair<String, String>> {
    val result = ArrayList<Pair<String, String>>()
    if (event != START) return result
    var i = attrsStart
    while (true) {
      i = nextAttribute(i)
      if (i < 0) return result
      stringsCreated++
      val name = latin1(buffer, attrNameStart, attrNameEnd)
      setValue()
      result.add(name to decode(valueBuffer, valueStart, valueEnd))
    }
  }

  /** Значения `xmlns` и `xmlns:*` текущего [START] — для версии LandXML по пространству имён. */
  fun namespaces(): List<String> {
    val result = ArrayList<String>()
    if (event != START) return result
    var i = attrsStart
    while (true) {
      i = nextAttribute(i, namespaces = true)
      if (i < 0) return result
      stringsCreated++
      result.add(latin1(buffer, attrValueStart, attrValueEnd))
    }
  }

  // разбирает атрибут, начиная с [from]; -1 — атрибутов больше нет. xmlns и xmlns:* пропускаются,
  // а с [namespaces] — наоборот, отдаются только они
  private fun nextAttribute(from: Int, namespaces: Boolean = false): Int {
    val buf = buffer
    val end = attrsEnd
    var i = from
    while (true) {
      while (i < end && isXmlSpace(buf[i])) i++
      if (i >= end) return -1
      val nameStart = i
      while (i < end && buf[i] != EQ && !isXmlSpace(buf[i])) i++
      val nameEnd = i
      while (i < end && isXmlSpace(buf[i])) i++
      if (i >= end || buf[i] != EQ) error(UiText.key("xml_attr_no_value"), tagOffset)
      i++
      while (i < end && isXmlSpace(buf[i])) i++
      if (i >= end || (buf[i] != QUOTE && buf[i] != APOSTROPHE)) error(UiText.key("xml_attr_no_quotes"), tagOffset)
      val quote = buf[i]
      i++
      val valueStart = i
      while (i < end && buf[i] != quote) i++
      if (i >= end) error(UiText.key("xml_open_quote"), tagOffset)
      val valueEnd = i
      i++
      if (isXmlns(buf, nameStart, nameEnd) != namespaces) continue
      attrNameStart = nameStart
      attrNameEnd = nameEnd
      attrValueStart = valueStart
      attrValueEnd = valueEnd
      return i
    }
  }

  private fun isXmlns(buf: ByteArray, start: Int, end: Int): Boolean {
    if (end - start < 5) return false
    for (k in XMLNS.indices) {
      if (buf[start + k] != XMLNS[k]) return false
    }
    return end - start == 5 || buf[start + 5] == COLON
  }

  // значение найденного атрибута: без '&' — прямо в буфере, иначе с раскрытыми сущностями
  private fun setValue() {
    var hasEntity = false
    for (i in attrValueStart until attrValueEnd) {
      if (buffer[i] == AMP) {
        hasEntity = true
        break
      }
    }
    if (!hasEntity) {
      valueBuffer = buffer
      valueStart = attrValueStart
      valueEnd = attrValueEnd
      return
    }
    if (valueScratch.size < attrValueEnd - attrValueStart) valueScratch = ByteArray(attrValueEnd - attrValueStart)
    var out = 0
    var i = attrValueStart
    while (i < attrValueEnd) {
      val b = buffer[i]
      if (b == AMP) {
        val semicolon = indexOfSemicolon(i + 1, attrValueEnd)
        val code = if (semicolon < 0) -1 else entityCode(buffer, i + 1, semicolon)
        if (code >= 0) {
          // ссылка на символ короче своей записи (&#N; ≥ 4 байт, UTF-8 ≤ 4), так что запас не нужен
          out = writeUtf8(valueScratch, out, code)
          i = semicolon + 1
          continue
        }
      }
      valueScratch[out++] = b
      i++
    }
    valueBuffer = valueScratch
    valueStart = 0
    valueEnd = out
  }

  // --- текст ---

  /**
   * Следующее число (слово до пробела) в тексте текущего элемента; false — текст кончился.
   * Работает сразу после [START] или [TEXT]; текст не накапливается, число на границе буфера склеивается.
   * Числа внутри CDATA и сущностей так не читаются — для них [readText].
   */
  fun nextNumber(): Boolean {
    if (!inText) {
      if (event != START || isEmptyElement) return false
      inText = true
    }
    var p = pos
    while (true) {
      if (p >= limit) {
        pos = p
        if (!fill()) return false
        p = pos
      }
      if (!isXmlSpace(buffer[p])) break
      p++
    }
    pos = p
    if (buffer[p] == LT) return false
    var i = p + 1
    while (true) {
      if (i >= limit) {
        val offset = i - pos
        val more = fill()
        i = pos + offset
        if (!more) break
        continue
      }
      val b = buffer[i]
      if (b == LT || isXmlSpace(b)) break
      i++
    }
    numberStart = pos
    numberEnd = i
    numberOffset = discarded + pos + bomLength
    pos = i
    return true
  }

  /** Текущее число после [nextNumber]. */
  fun double(): Double {
    val value = LandXmlNumbers.parseDouble(buffer, numberStart, numberEnd)
    if (value.isNaN()) error(UiText.key("xml_not_number", latin1(buffer, numberStart, numberEnd)), numberOffset)
    return value
  }

  fun int(): Int {
    val value = LandXmlNumbers.parseInt(buffer, numberStart, numberEnd)
    if (value == LandXmlNumbers.NOT_INT) error(UiText.key("xml_not_int", latin1(buffer, numberStart, numberEnd)), numberOffset)
    return value.toInt()
  }

  /**
   * Читает текст до следующего тега (после [START] или [TEXT]) в [textBuffer]: сущности раскрыты,
   * CDATA подставлен, комментарии выброшены. Без них — диапазон прямо в [buffer].
   */
  fun readText() {
    if (!inText) {
      if (event != START || isEmptyElement) {
        textBuffer = buffer
        textStart = 0
        textEnd = 0
        return
      }
    }
    inText = false
    // быстрый путь: до '<' тега без '&'
    var i = pos
    while (true) {
      if (i >= limit) {
        val offset = i - pos
        if (!fill()) error(UiText.key("xml_eof_text"), position())
        i = pos + offset
        continue
      }
      val b = buffer[i]
      if (b == AMP) break
      if (b == LT) {
        if (i + 1 >= limit) {
          val offset = i - pos
          fill()
          i = pos + offset
        }
        if (i + 1 < limit && (buffer[i + 1] == BANG || buffer[i + 1] == QUESTION)) break
        textBuffer = buffer
        textStart = pos
        textEnd = i
        pos = i
        return
      }
      i++
    }
    readTextSlow()
  }

  /** Текст после [readText] строкой. */
  fun textString(): String = decode(textBuffer, textStart, textEnd)

  private fun readTextSlow() {
    var out = 0
    while (true) {
      if (pos >= limit && !fill()) break
      val b = buffer[pos]
      if (b == LT) {
        if (startsWith(CDATA_OPEN)) {
          pos += CDATA_OPEN.size
          while (true) {
            if (!ensure(CDATA_CLOSE.size)) error(UiText.key("xml_open_cdata"), position())
            if (startsWith(CDATA_CLOSE)) break
            textScratch = appendByte(textScratch, out, buffer[pos])
            out++
            pos++
          }
          pos += CDATA_CLOSE.size
          continue
        }
        if (startsWith(COMMENT_OPEN)) {
          pos += COMMENT_OPEN.size
          skipPast(COMMENT_CLOSE)
          continue
        }
        if (startsWith(PI_OPEN)) {
          pos += PI_OPEN.size
          skipPast(PI_CLOSE)
          continue
        }
        break
      }
      if (b == AMP) {
        ensureUpTo(MAX_ENTITY)
        val semicolon = indexOfSemicolon(pos + 1, minOf(limit, pos + MAX_ENTITY))
        val code = if (semicolon < 0) -1 else entityCode(buffer, pos + 1, semicolon)
        if (code >= 0) {
          if (textScratch.size < out + 4) textScratch = textScratch.copyOf(textScratch.size * 2)
          out = writeUtf8(textScratch, out, code)
          pos = semicolon + 1
          continue
        }
      }
      textScratch = appendByte(textScratch, out, b)
      out++
      pos++
    }
    textBuffer = textScratch
    textStart = 0
    textEnd = out
  }

  private fun indexOfSemicolon(from: Int, to: Int): Int {
    for (i in from until to) {
      if (buffer[i] == SEMICOLON) return i
    }
    return -1
  }

  private fun appendByte(target: ByteArray, at: Int, b: Byte): ByteArray {
    val result = if (at < target.size) target else target.copyOf(target.size * 2)
    result[at] = b
    return result
  }

  // пропускает остаток текста вместе с CDATA и комментариями; останавливается на '<' тега
  private fun skipText() {
    while (true) {
      var p = pos
      while (p < limit && buffer[p] != LT) p++
      pos = p
      if (p >= limit) {
        if (!fill()) return
        continue
      }
      when {
        startsWith(CDATA_OPEN) -> {
          pos += CDATA_OPEN.size
          skipPast(CDATA_CLOSE)
        }
        startsWith(COMMENT_OPEN) -> {
          pos += COMMENT_OPEN.size
          skipPast(COMMENT_CLOSE)
        }
        startsWith(PI_OPEN) -> {
          pos += PI_OPEN.size
          skipPast(PI_CLOSE)
        }
        else -> return
      }
    }
  }

  // --- теги ---

  private fun readStartTag() {
    val end = findTagEnd(pos + 1)
    val buf = buffer
    val nameStart = pos + 1
    var i = nameStart
    var local = nameStart
    while (i < end) {
      val b = buf[i]
      if (isXmlSpace(b) || b == SLASH) break
      if (b == COLON) local = i + 1
      i++
    }
    if (i == nameStart) error(UiText.key("xml_empty_name"), position())
    tagOffset = position()
    isEmptyElement = buf[end - 1] == SLASH
    attrsStart = i
    attrsEnd = if (isEmptyElement) end - 1 else end
    nameId = names.find(buf, local, i)
    push(buf, nameStart, i, nameId)
    pos = end + 1
    declarationAllowed = false
    rootSeen = true
    event = START
    pendingEnd = isEmptyElement
  }

  private fun readEndTag() {
    val offset = position()
    val end = findTagEnd(pos + 2)
    var nameEnd = end
    while (nameEnd > pos + 2 && isXmlSpace(buffer[nameEnd - 1])) nameEnd--
    if (depth == 0) error(UiText.key("xml_extra_close", latin1(buffer, pos + 2, nameEnd)), offset)
    val start = nameOffsets[depth - 1]
    val length = nameOffsets[depth] - start
    var same = nameEnd - (pos + 2) == length
    if (same) {
      for (k in 0 until length) {
        if (buffer[pos + 2 + k] != nameStack[start + k]) {
          same = false
          break
        }
      }
    }
    if (!same) {
      error(UiText.key("xml_close_mismatch", latin1(buffer, pos + 2, nameEnd), stackName(depth)), offset)
    }
    pos = end + 1
    event = END
    isEmptyElement = false
    nameId = idStack[depth]
    popPending = true
  }

  private fun push(buf: ByteArray, start: Int, end: Int, id: Int) {
    if (depth >= MAX_DEPTH) error(UiText.key("xml_too_deep", MAX_DEPTH), position())
    val top = nameOffsets[depth]
    val length = end - start
    if (top + length > nameStack.size) nameStack = nameStack.copyOf(maxOf(nameStack.size * 2, top + length))
    buf.copyInto(nameStack, top, start, end)
    depth++
    nameOffsets[depth] = top + length
    idStack[depth] = id
  }

  private fun stackName(level: Int): String = latin1(nameStack, nameOffsets[level - 1], nameOffsets[level])

  // индекс '>' тега, начатого в pos; кавычки учитываются; тег целиком остаётся в буфере
  private fun findTagEnd(from: Int): Int {
    var i = from
    var quote: Byte = 0
    while (true) {
      if (i >= limit) {
        val offset = i - pos
        if (!fill()) error("@xml_open_tag", position())
        i = pos + offset
        continue
      }
      val b = buffer[i]
      if (quote != 0.toByte()) {
        if (b == quote) quote = 0
      } else if (b == GT) {
        return i
      } else if (b == QUOTE || b == APOSTROPHE) {
        quote = b
      } else if (b == LT) {
        error("@xml_open_tag", position())
      }
      i++
    }
  }

  private fun skipProcessingInstruction() {
    // объявление короткое — держим целиком, чтобы прочитать encoding
    val end = indexOf(PI_CLOSE, pos + 2)
    val offset = pos
    if (declarationAllowed && end - offset >= 5 && startsWithAt(offset + 2, XML_TARGET) && isXmlSpace(buffer[offset + 5])) {
      readDeclaration(offset + 5, end)
    }
    declarationAllowed = false
    pos = end + PI_CLOSE.size
  }

  private fun readDeclaration(from: Int, to: Int) {
    attrsStart = from
    attrsEnd = to
    tagOffset = position()
    val savedEvent = event
    event = START
    val found = findAttr(ENCODING)
    event = savedEvent
    if (!found) return
    val name = latin1(valueBuffer, valueStart, valueEnd).trim()
    if (name.uppercase().startsWith("UTF")) return
    declaredDecoder = codePageDecoder(name)
  }

  // <!DOCTYPE …> с внутренним подмножеством […] и прочие <!…>
  private fun skipDeclaration() {
    var i = pos + 2
    var quote: Byte = 0
    var brackets = 0
    while (true) {
      if (i + COMMENT_OPEN.size > limit) {
        val offset = i - pos
        fill()
        i = pos + offset
        if (i >= limit) error(UiText.key("xml_open_decl"), position())
      }
      val b = buffer[i]
      when {
        quote != 0.toByte() -> if (b == quote) quote = 0
        b == QUOTE || b == APOSTROPHE -> quote = b
        b == LBRACKET -> brackets++
        b == RBRACKET -> brackets--
        b == LT && brackets > 0 && startsWithAt(i, COMMENT_OPEN) -> {
          i = indexOf(COMMENT_CLOSE, i + COMMENT_OPEN.size) + COMMENT_CLOSE.size - 1
        }
        b == GT && brackets <= 0 -> {
          pos = i + 1
          return
        }
      }
      i++
    }
  }

  // --- буфер ---

  private fun skipSpaces(): Boolean {
    while (true) {
      var p = pos
      val buf = buffer
      val l = limit
      while (p < l && isXmlSpace(buf[p])) p++
      pos = p
      if (p < l) return true
      if (!fill()) return false
    }
  }

  // пропускает до конца [pattern], не удерживая пройденное в буфере
  private fun skipPast(pattern: ByteArray) {
    while (true) {
      var i = pos
      val last = limit - pattern.size
      while (i <= last) {
        if (buffer[i] == pattern[0] && startsWithAt(i, pattern)) {
          pos = i + pattern.size
          return
        }
        i++
      }
      pos = maxOf(pos, limit - pattern.size + 1)
      if (!fill()) error(UiText.key("xml_eof_pattern", pattern.decodeToString()), position())
    }
  }

  // индекс начала [pattern] от [from]; всё от pos остаётся в буфере
  private fun indexOf(pattern: ByteArray, from: Int): Int {
    var i = from
    while (true) {
      if (i + pattern.size > limit) {
        val offset = i - pos
        if (!fill()) error(UiText.key("xml_eof_pattern", pattern.decodeToString()), position())
        i = pos + offset
        continue
      }
      if (startsWithAt(i, pattern)) return i
      i++
    }
  }

  private fun startsWith(pattern: ByteArray): Boolean = ensure(pattern.size) && startsWithAt(pos, pattern)

  private fun startsWithAt(at: Int, pattern: ByteArray): Boolean {
    if (at + pattern.size > limit) return false
    for (k in pattern.indices) {
      if (buffer[at + k] != pattern[k]) return false
    }
    return true
  }

  private fun ensure(count: Int): Boolean {
    while (limit - pos < count) {
      if (!fill()) return false
    }
    return true
  }

  private fun ensureUpTo(count: Int) {
    while (limit - pos < count && fill()) Unit
  }

  // сдвигает непрочитанное (от pos) в начало буфера и дочитывает; не помещается — буфер растёт
  private fun fill(): Boolean {
    if (eof) return false
    if (pos > 0) {
      buffer.copyInto(buffer, destinationOffset = 0, startIndex = pos, endIndex = limit)
      limit -= pos
      discarded += pos
      pos = 0
    }
    if (limit == buffer.size) {
      buffer = buffer.copyOf(buffer.size * 2)
    } else if (lastReadFull && buffer.size < fullBufferSize) {
      buffer = buffer.copyOf(minOf(buffer.size * 4, fullBufferSize))
    }
    while (true) {
      val read = input.read(buffer, limit, buffer.size - limit)
      if (read < 0) {
        eof = true
        return false
      }
      if (read > 0) {
        limit += read
        lastReadFull = limit == buffer.size
        return true
      }
    }
  }

  private fun position(): Long = discarded + pos + bomLength

  private fun error(message: String, offset: Long): Nothing = throw XmlFormatException(message, offset)

  // --- строки ---

  private fun decode(buf: ByteArray, start: Int, end: Int): String {
    stringsCreated++
    var ascii = true
    for (i in start until end) {
      if (buf[i] < 0) {
        ascii = false
        break
      }
    }
    if (ascii) return latin1(buf, start, end)
    declaredDecoder?.let { return it(buf, start, end) }
    if (isValidUtf8(buf, start, end)) return buf.decodeToString(start, end)
    return WINDOWS_1251(buf, start, end)
  }

  companion object {
    const val NONE = 0
    const val START = 1
    const val END = 2
    const val TEXT = 3
    const val EOF = 4

    const val UNKNOWN = -1
    const val MAX_DEPTH = 256

    private const val DEFAULT_BUFFER_SIZE = 256 * 1024
    private const val START_BUFFER_SIZE = 16 * 1024
    private const val MAX_ENTITY = 12

    private const val LT = '<'.code.toByte()
    private const val GT = '>'.code.toByte()
    private const val SLASH = '/'.code.toByte()
    private const val QUESTION = '?'.code.toByte()
    private const val BANG = '!'.code.toByte()
    private const val EQ = '='.code.toByte()
    private const val QUOTE = '"'.code.toByte()
    private const val APOSTROPHE = '\''.code.toByte()
    private const val AMP = '&'.code.toByte()
    private const val COLON = ':'.code.toByte()
    private const val SEMICOLON = ';'.code.toByte()
    private const val LBRACKET = '['.code.toByte()
    private const val RBRACKET = ']'.code.toByte()

    private val CDATA_OPEN = "<![CDATA[".encodeToByteArray()
    private val CDATA_CLOSE = "]]>".encodeToByteArray()
    private val COMMENT_OPEN = "<!--".encodeToByteArray()
    private val COMMENT_CLOSE = "-->".encodeToByteArray()
    private val PI_OPEN = "<?".encodeToByteArray()
    private val PI_CLOSE = "?>".encodeToByteArray()
    private val XML_TARGET = "xml".encodeToByteArray()
    private val XMLNS = "xmlns".encodeToByteArray()
    private val ENCODING = "encoding".encodeToByteArray()

    private val WINDOWS_1251 = codePageDecoder("windows-1251") ?: { buf: ByteArray, start: Int, end: Int -> latin1(buf, start, end) }
  }
}

private class CountingInput(private val source: ByteInput) : ByteInput {
  var count = 0L
    private set

  override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
    val n = source.read(buffer, offset, length)
    if (n > 0) count += n
    return n
  }

  override fun close() = source.close()
}

private fun equalsRange(expected: ByteArray, buf: ByteArray, start: Int, end: Int): Boolean {
  if (end - start != expected.size) return false
  for (k in expected.indices) {
    if (buf[start + k] != expected[k]) return false
  }
  return true
}

// код символа сущности (amp, lt, gt, quot, apos, #N, #xH) по имени между '&' и ';'; -1 — неизвестная
private fun entityCode(buf: ByteArray, start: Int, end: Int): Int {
  val length = end - start
  if (length <= 0) return -1
  if (buf[start] == '#'.code.toByte()) {
    val hex = length > 1 && (buf[start + 1] == 'x'.code.toByte() || buf[start + 1] == 'X'.code.toByte())
    var i = if (hex) start + 2 else start + 1
    if (i >= end) return -1
    var code = 0
    while (i < end) {
      val c = buf[i].toInt().toChar()
      val digit = if (hex) c.digitToIntOrNull(16) else c.digitToIntOrNull(10)
      if (digit == null) return -1
      code = code * (if (hex) 16 else 10) + digit
      if (code > 0x10FFFF) return -1
      i++
    }
    return code
  }
  return when {
    equalsRange(AMP_NAME, buf, start, end) -> '&'.code
    equalsRange(LT_NAME, buf, start, end) -> '<'.code
    equalsRange(GT_NAME, buf, start, end) -> '>'.code
    equalsRange(QUOT_NAME, buf, start, end) -> '"'.code
    equalsRange(APOS_NAME, buf, start, end) -> '\''.code
    else -> -1
  }
}

private val AMP_NAME = "amp".encodeToByteArray()
private val LT_NAME = "lt".encodeToByteArray()
private val GT_NAME = "gt".encodeToByteArray()
private val QUOT_NAME = "quot".encodeToByteArray()
private val APOS_NAME = "apos".encodeToByteArray()

private fun writeUtf8(target: ByteArray, at: Int, code: Int): Int {
  var i = at
  when {
    code < 0x80 -> target[i++] = code.toByte()
    code < 0x800 -> {
      target[i++] = (0xC0 or (code shr 6)).toByte()
      target[i++] = (0x80 or (code and 0x3F)).toByte()
    }
    code < 0x10000 -> {
      target[i++] = (0xE0 or (code shr 12)).toByte()
      target[i++] = (0x80 or ((code shr 6) and 0x3F)).toByte()
      target[i++] = (0x80 or (code and 0x3F)).toByte()
    }
    else -> {
      target[i++] = (0xF0 or (code shr 18)).toByte()
      target[i++] = (0x80 or ((code shr 12) and 0x3F)).toByte()
      target[i++] = (0x80 or ((code shr 6) and 0x3F)).toByte()
      target[i++] = (0x80 or (code and 0x3F)).toByte()
    }
  }
  return i
}
