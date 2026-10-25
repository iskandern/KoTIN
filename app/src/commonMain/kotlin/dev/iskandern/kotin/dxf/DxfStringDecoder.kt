package dev.iskandern.kotin.dxf

import dev.iskandern.kotin.io.codePageDecoder
import dev.iskandern.kotin.io.isValidUtf8
import dev.iskandern.kotin.io.latin1

/**
 * Декодирует строковые значения DXF. Кодировка определяется по каждой строке:
 * ASCII → как есть, корректный UTF-8 → UTF-8, иначе кодовая страница из $DWGCODEPAGE (по умолчанию cp1251).
 * Так не нужно заранее читать весь файл, чтобы угадать кодировку.
 */
class DxfStringDecoder {

  private var fallback: (ByteArray, Int, Int) -> String = WINDOWS_1251

  fun setCodePage(codePage: String) {
    fallback = decoderForCodePage(codePage) ?: return
  }

  fun decode(buf: ByteArray, start: Int, end: Int): String {
    val text = when {
      isAscii(buf, start, end) -> latin1(buf, start, end)
      isValidUtf8(buf, start, end) -> buf.decodeToString(start, end)
      else -> fallback(buf, start, end)
    }
    return unescapeUnicode(text)
  }

  private fun isAscii(buf: ByteArray, start: Int, end: Int): Boolean {
    for (i in start until end) {
      if (buf[i] < 0) return false
    }
    return true
  }

  // \U+0416 — символы, которых нет в кодовой странице файла; \M+nXXXX (MIF) — байты символа в азиатской
  // странице n; %%nnn — знак с десятичным кодом nnn в кодовой странице файла
  private fun unescapeUnicode(text: String): String {
    if (!text.contains("\\U+") && !text.contains("\\M+") && !text.contains("%%")) return text

    val result = StringBuilder(text.length)
    var i = 0
    while (i < text.length) {
      if (text.startsWith("\\U+", i) && i + 7 <= text.length) {
        val code = text.substring(i + 3, i + 7).toIntOrNull(radix = 16)
        if (code != null) {
          result.append(code.toChar())
          i += 7
          continue
        }
      }
      if (text.startsWith("\\M+", i) && i + 8 <= text.length) {
        val decoded = decodeMif(text[i + 3], text.substring(i + 4, i + 8))
        if (decoded != null) {
          result.append(decoded)
          i += 8
          continue
        }
      }
      if (text.startsWith("%%", i) && i + 2 < text.length) {
        val digits = if (i + 5 <= text.length) text.substring(i + 2, i + 5) else ""
        val code = if (digits.all { it in '0'..'9' }) digits.toIntOrNull() else null
        if (code != null && code in 1..255) {
          result.append(if (code < 128) code.toChar() else fallback(byteArrayOf(code.toByte()), 0, 1))
          i += 5
        } else {
          // %%c, %%d и т. п. копируются целиком, чтобы в «%%%100» не принять «%%1» за код
          result.append(text, i, i + 3)
          i += 3
        }
        continue
      }
      result.append(text[i])
      i++
    }
    return result.toString()
  }

  private val mifDecoders = HashMap<Char, ((ByteArray, Int, Int) -> String)?>()

  // \M+nXXXX: n — страница (1 — Shift-JIS, 2 — Big5, 3 — Wansung, 4 — Johab, 5 — GB2312), XXXX — байты в hex
  private fun decodeMif(page: Char, hex: String): String? {
    if (!hex.all { it in '0'..'9' || it in 'A'..'F' || it in 'a'..'f' }) return null
    val code = hex.toInt(radix = 16)
    val decoder = mifDecoders.getOrPut(page) {
      val names = when (page) {
        '1' -> listOf("Shift_JIS")
        '2' -> listOf("Big5")
        '3' -> listOf("x-windows-949", "EUC-KR")
        '4' -> listOf("x-Johab")
        '5' -> listOf("GBK")
        else -> emptyList()
      }
      names.firstNotNullOfOrNull { codePageDecoder(it) }
    } ?: return null
    val text = decoder(byteArrayOf((code shr 8).toByte(), code.toByte()), 0, 2)
    return text.takeIf { it.length == 1 && it[0] != '�' }
  }

  private fun decoderForCodePage(codePage: String): ((ByteArray, Int, Int) -> String)? {
    val key = codePage.trim().uppercase()
    // DOS-, ISO- и Mac-страницы: имена в JVM и TextDecoder различаются, берётся первое известное платформе;
    // страниц, которых нет в браузере (437, 850…), там нет — остаётся cp1251
    val aliases = when {
      key.startsWith("DOS") -> listOf("IBM" + key.removePrefix("DOS"))
      key.startsWith("ISO8859-") -> listOf("ISO-8859-" + key.removePrefix("ISO8859-"))
      key == "MACINTOSH" -> listOf("x-MacRoman", "macintosh")
      else -> null
    }
    if (aliases != null) return aliases.firstNotNullOfOrNull { codePageDecoder(it) }
    val name = when (key) {
      "ANSI_874" -> "x-windows-874"
      "ANSI_932" -> "Shift_JIS"
      "ANSI_936" -> "GBK"
      "ANSI_949" -> "EUC-KR"
      "ANSI_950" -> "Big5"
      "ANSI_1250" -> "windows-1250"
      "ANSI_1251" -> "windows-1251"
      "ANSI_1253" -> "windows-1253"
      "ANSI_1254" -> "windows-1254"
      "ANSI_1255" -> "windows-1255"
      "ANSI_1256" -> "windows-1256"
      "ANSI_1257" -> "windows-1257"
      "ANSI_1258" -> "windows-1258"
      // ANSI_1252 не меняет cp1251: его по умолчанию пишут и в файлы с кириллицей,
      // а прежний импорт всё, что не UTF-8, читал как cp1251
      else -> return null
    }
    return codePageDecoder(name)
  }

  private companion object {
    val WINDOWS_1251 = codePageDecoder("windows-1251") ?: { buf: ByteArray, start: Int, end: Int -> latin1(buf, start, end) }
  }
}

/**
 * Кеш строк по байтам: имя слоя повторяется в каждой сущности, и без кеша на каждый треугольник
 * создавалась бы своя String. Подряд обычно идёт один и тот же слой — он проверяется первым.
 */
internal class DxfNameCache(private val decoder: DxfStringDecoder) {

  private class Entry(val bytes: ByteArray, val value: String)

  private val entries = HashMap<Int, MutableList<Entry>>()
  private var last: Entry? = null

  fun get(buf: ByteArray, start: Int, end: Int): String {
    val cached = last
    if (cached != null && cached.bytes.contentEqualsRange(buf, start, end)) return cached.value

    val hash = hash(buf, start, end)
    val bucket = entries.getOrPut(hash) { ArrayList(1) }
    val found = bucket.firstOrNull { it.bytes.contentEqualsRange(buf, start, end) }
      ?: Entry(buf.copyOfRange(start, end), decoder.decode(buf, start, end)).also { bucket.add(it) }

    last = found
    return found.value
  }

  private fun hash(buf: ByteArray, start: Int, end: Int): Int {
    var h = 1
    for (i in start until end) h = 31 * h + buf[i]
    return h
  }

  private fun ByteArray.contentEqualsRange(buf: ByteArray, start: Int, end: Int): Boolean {
    if (size != end - start) return false
    for (i in indices) {
      if (this[i] != buf[start + i]) return false
    }
    return true
  }
}
