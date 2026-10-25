package dev.iskandern.kotin.io

import dev.iskandern.kotin.dxf.DxfDocumentReader
import dev.iskandern.kotin.dxf.DxfFlattener
import dev.iskandern.kotin.landxml.LandXmlFormat
import dev.iskandern.kotin.landxml.LandXmlReader
import dev.iskandern.kotin.landxml.StationNotation
import dev.iskandern.kotin.model.SceneGeometry
import dev.iskandern.kotin.model.UiText

/**
 * Форматы файлов и их читатели. Формат — по началу файла (первые [HEAD_SIZE] байтов), расширение не смотрим:
 * берётся первый по порядку, чьё начало подошло; [DXF] — последний и читает всё остальное (пустой файл, чужой XML).
 */
enum class DrawingFormat {
  LANDXML {
    override fun matches(head: ByteArray, size: Int) = LandXmlFormat.isLandXml(head, size)

    override fun read(input: ByteInput, sizeBytes: Long?, notation: StationNotation, onProgress: (bytesRead: Long) -> Unit): SceneGeometry =
      input.use { LandXmlReader(it, sizeBytes, notation).read(onProgress) }
  },
  DXF {
    override fun matches(head: ByteArray, size: Int) = true

    override fun read(input: ByteInput, sizeBytes: Long?, notation: StationNotation, onProgress: (bytesRead: Long) -> Unit): SceneGeometry {
      val document = input.use { DxfDocumentReader(it).read(onProgress) }
      return DxfFlattener(document).flatten()
    }
  };

  /** Начало файла [head] (первые [size] байтов) — этого формата. */
  abstract fun matches(head: ByteArray, size: Int): Boolean

  /**
   * Читает файл с начала и закрывает его; [onProgress] вызывается и в конце чтения, перед сборкой сцены.
   * [sizeBytes] — размер файла, если известен: LandXML по нему оценивает ёмкость массивов; [notation] — запись пикетов
   * в подписях LandXML.
   */
  abstract fun read(
    input: ByteInput, sizeBytes: Long?, notation: StationNotation = StationNotation(), onProgress: (bytesRead: Long) -> Unit,
  ): SceneGeometry

  /** Формат файла и сам файл с начала: прочитанное начало отдаётся ещё раз, второй раз файл не открывается. */
  class Detected(val format: DrawingFormat, val input: ByteInput)

  companion object {
    const val HEAD_SIZE = 4096

    /** Читает начало [input] (до [HEAD_SIZE] байтов) и определяет формат. */
    fun detect(input: ByteInput): Detected {
      val head = ByteArray(HEAD_SIZE)
      var size = 0
      try {
        while (size < head.size) {
          val n = input.read(head, size, head.size - size)
          if (n < 0) break
          size += n
        }
      } catch (e: Throwable) {
        input.close()
        throw e
      }
      val foreign = foreignFormat(head, size)
      if (foreign != null) {
        input.close()
        throw ForeignFormatException(UiText.key(foreign))
      }
      return Detected(entries.first { it.matches(head, size) }, PrefixedInput(head, 0, size, input))
    }

    /** Определяет формат и читает файл; [onProgress] получает число прочитанных байтов. */
    fun read(
      input: ByteInput, sizeBytes: Long? = null, notation: StationNotation = StationNotation(), onProgress: (bytesRead: Long) -> Unit = {},
    ): SceneGeometry {
      val detected = detect(input)
      return detected.format.read(detected.input, sizeBytes, notation, onProgress)
    }
  }
}

/** Файл чужого формата (архив, DWG, PDF, картинка): сообщение — ключ UiText с причиной простыми словами. */
class ForeignFormatException(message: String) : Exception(message)

/** Сообщение ошибки — про чужой формат: на web тип исключения из Worker не доходит, узнаём по ключу. */
fun isForeignFormat(message: String): Boolean = message.startsWith("${UiText.MARK}$FOREIGN_PREFIX")

private const val FOREIGN_PREFIX = "error_format_"

/** Сигнатуры чужих форматов в начале файла и ключи причины. */
private val FOREIGN_SIGNATURES: List<Pair<ByteArray, String>> = listOf(
  "PK\u0003\u0004" to "zip",
  "Rar!" to "archive",
  "7z\u00BC\u00AF" to "archive",
  "\u001F\u008B" to "archive",
  "AC10" to "dwg",
  "%PDF" to "pdf",
  "\u0089PNG" to "image",
  "\u00FF\u00D8\u00FF" to "image",
  "GIF8" to "image",
).map { (signature, key) -> ByteArray(signature.length) { signature[it].code.toByte() } to FOREIGN_PREFIX + key }

/** Ключ причины, если начало файла [head] (первые [size] байтов) — чужой формат; null — пусть читают DXF и LandXML. */
internal fun foreignFormat(head: ByteArray, size: Int): String? =
  FOREIGN_SIGNATURES.firstOrNull { (signature, _) -> size >= signature.size && signature.indices.all { head[it] == signature[it] } }?.second
