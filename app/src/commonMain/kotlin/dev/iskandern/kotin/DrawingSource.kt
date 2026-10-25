package dev.iskandern.kotin

import androidx.compose.runtime.Composable
import org.jetbrains.compose.resources.StringResource
import dev.iskandern.kotin.io.ByteInput

/** Выбранный файл: имя для заголовка, размер (для прогресса, если известен) и способ открыть поток. */
class DrawingSource(val name: String, val sizeBytes: Long?, val open: suspend () -> ByteInput)

/** Возвращает действие «выбрать файл»: на desktop — системный диалог, на Android — выбор документа, на web — выбор файла браузером. */
@Composable
expect fun rememberFilePicker(onPicked: (DrawingSource) -> Unit): () -> Unit

/** Подсказка пустого экрана: как ещё открыть файл на этой платформе (web — ссылкой, Android — «Открыть с помощью»). */
expect val openFileHint: StringResource
