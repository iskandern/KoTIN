package dev.iskandern.kotin.web

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.iskandern.kotin.LoadState
import dev.iskandern.kotin.fileFormat
import dev.iskandern.kotin.groupDigits
import dev.iskandern.kotin.io.isForeignFormat
import dev.iskandern.kotin.localized
import dev.iskandern.kotin.openFileHint
import dev.iskandern.kotin.resources.Res
import dev.iskandern.kotin.resources.kilobytes
import dev.iskandern.kotin.resources.load_failed
import dev.iskandern.kotin.resources.load_failed_dismiss
import dev.iskandern.kotin.resources.load_failed_kept
import dev.iskandern.kotin.resources.load_failed_reason
import dev.iskandern.kotin.resources.loading_meta
import dev.iskandern.kotin.resources.megabytes
import dev.iskandern.kotin.resources.menu
import dev.iskandern.kotin.resources.no_file
import dev.iskandern.kotin.resources.open_file
import dev.iskandern.kotin.resources.open_hint
import dev.iskandern.kotin.resources.open_other_file
import dev.iskandern.kotin.resources.triangles
import dev.iskandern.kotin.web.dom.Div
import dev.iskandern.kotin.web.dom.Img
import dev.iskandern.kotin.web.dom.Span
import dev.iskandern.kotin.web.dom.Text
import dev.iskandern.kotin.web.dom.px
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/** Кнопка меню ≡: квадратная; пока меню открыто — тёмная. */
@Composable
fun MenuButton(open: Boolean, size: Int, onClick: () -> Unit) {
  Div({ classes("menu-button", "floating"); classIf(open, "open") }) {
    IconBox(Icon.Menu, stringResource(Res.string.menu), size, onClick, "12px", color = if (open) "var(--card)" else "var(--ink)")
  }
}

/**
 * Карточка файла рядом с ≡: имя и строка «LandXML · 2,4 МБ · 504 треугольника», значок «открыть другой файл»;
 * пока файл читается — ход чтения, не открылся — строка «Не открылся ⌄», по нажатию — причина и
 * сообщение разбора, [onDismiss] убирает ошибку. Без файла — [KotinWordmark].
 */
@Composable
fun FileCard(loadState: LoadState, shown: LoadState.Loaded?, height: Int, onOpen: () -> Unit, onDismiss: () -> Unit) {
  var progress: Float? = null
  var loading = false
  val failed = loadState as? LoadState.Failed
  // подробности раскрыты; новая ошибка приходит свёрнутой
  var expanded by remember(failed) { mutableStateOf(false) }
  val title: String?
  val meta: String
  when {
    loadState is LoadState.Loading -> {
      loading = true
      progress = loadState.progress
      title = loadState.name
      meta = stringResource(Res.string.loading_meta, formatSize(loadState.bytesRead))
    }
    failed != null -> {
      title = failed.name
      meta = stringResource(Res.string.load_failed)
    }
    shown != null -> {
      title = shown.name
      meta = fileMeta(shown)
    }
    else -> {
      title = null
      meta = stringResource(Res.string.no_file)
    }
  }
  Div({
    classes("file-card", "floating")
    style { property("min-height", height.px) }
    if (failed != null) {
      classes("ripple")
      attr("role", "button")
      onClick { expanded = !expanded }
    }
  }) {
    Div({ classes("row"); style { property("min-height", height.px) } }) {
      // имя и строку под ним можно выделить и скопировать; у ошибки нажатие раскрывает подробности
      Div({ classes("texts"); classIf(failed == null, "selectable") }) {
        if (title == null) KotinWordmark(bottom = 2) else Div({ classes("title", "body-medium", "ellipsis") }) { Text(title) }
        Div({ classes("meta-row") }) {
          Span({ classes("meta", "ellipsis"); classIf(failed != null, "error") }) { Text(meta) }
          if (failed != null) {
            IconView(Icon.ChevronDown, 14, "var(--error)", if (expanded) 180f else 0f) { style { property("margin-left", "2px") } }
          }
        }
      }
      if (shown != null || failed != null) {
        Div({ classes("open-other"); style { property("width", (height - 4).px); property("height", (height - 4).px) } }) {
          IconBox(Icon.Folder, stringResource(Res.string.open_other_file), height - 4, onOpen, iconSize = 18) {
            // нажатие на значок не раскрывает ошибку
            on("click") { it.stopPropagation() }
          }
        }
      }
    }
    if (failed != null && expanded) LoadErrorDetails(failed, shown, onDismiss)
    if (loading) ProgressBar(progress)
  }
}

/** Полоса хода чтения (LinearProgressIndicator Material 3): доля [progress]; null — бегущая. */
@Composable
private fun ProgressBar(progress: Float?) {
  Div({ classes("progress"); classIf(progress == null, "indeterminate") }) {
    if (progress != null) {
      val percent = (progress.coerceIn(0f, 1f) * 100).toString()
      Div({ classes("track"); style { property("left", "calc($percent% + 4px)") } })
      Div({ classes("bar"); style { property("width", "$percent%") } })
      Div({ classes("stop") })
    } else {
      Div({ classes("track") })
      Div({ classes("bar") })
    }
  }
}

/**
 * Подробности ошибки открытия: причина простыми словами, какой файл остался на экране и сообщение разбора как есть —
 * его можно выделить и скопировать. Чужой формат (ZIP, DWG…) узнаётся по началу файла, тогда причина и есть сообщение.
 */
@Composable
private fun LoadErrorDetails(failed: LoadState.Failed, shown: LoadState.Loaded?, onDismiss: () -> Unit) {
  val known = isForeignFormat(failed.message)
  Div({ classes("details") }) {
    Div({ style { property("color", "var(--ink)"); property("font-size", "13px"); property("line-height", "17px") } }) {
      Text(if (known) localized(failed.message) else stringResource(Res.string.load_failed_reason))
    }
    if (shown != null) Div({ style { property("color", "var(--muted)"); property("font-size", "12px"); property("line-height", "15px") } }) {
      Text(stringResource(Res.string.load_failed_kept, shown.name))
    }
    if (!known) Div({
      classes("selectable", "mono")
      style {
        property("color", "var(--muted)"); property("font-size", "11px"); property("line-height", "14px")
        property("border-radius", "8px"); property("background", "var(--subtle)"); property("padding", "8px")
        property("overflow-wrap", "anywhere")
      }
      on("click") { it.stopPropagation() }
    }) { Text(localized(failed.message)) }
    Div({
      classes("ripple")
      attr("role", "button")
      style {
        property("align-self", "flex-start"); property("color", "var(--accent)"); property("font-size", "13px")
        property("font-weight", "600"); property("border-radius", "6px"); property("padding", "4px 0")
      }
      onClick { it.stopPropagation(); onDismiss() }
    }) { Text(stringResource(Res.string.load_failed_dismiss)) }
  }
}

/** Словесный знак: плитка-знак и «Ko» цветом текста, «TIN» — акцентом. */
@Composable
fun KotinWordmark(iconSize: Int = 20, bottom: Int = 0) {
  Div({ classes("wordmark"); if (bottom > 0) style { property("padding-bottom", bottom.px) } }) {
    Img("kotin_icon_small.svg") { style { property("width", iconSize.px); property("height", iconSize.px) } }
    Span({ classes("name", "body-medium") }) {
      Text("Ko")
      Span({ classes("accent") }) { Text("TIN") }
    }
  }
}

/** Пустой экран: файла ещё нет — по центру приглашение открыть и подсказка платформы. */
@Composable
fun EmptyState(onOpen: () -> Unit) {
  Div({ classes("empty-state", "floating") }) {
    Img("kotin_mark.svg") { style { property("width", "80px"); property("height", "60px") } }
    Div({ classes("hint-title") }) { Text(stringResource(Res.string.open_hint)) }
    Div({ classes("m3-button", "ripple"); attr("role", "button"); onClick { onOpen() } }) { Text(stringResource(Res.string.open_file)) }
    Div({ classes("hint") }) { Text(stringResource(openFileHint)) }
  }
}

/** Имя файла и строка «LandXML · 2,4 МБ · …» — первая строка бокового меню телефона, где карточки файла нет. */
@Composable
fun FileTitle(loaded: LoadState.Loaded) {
  Div({ classes("selectable"); style { property("display", "flex"); property("flex-direction", "column"); property("gap", "2px") } }) {
    Div({ style { property("color", "var(--ink)"); property("font-size", "16px"); property("line-height", "21px"); property("font-weight", "600"); property("overflow-wrap", "anywhere") } }) {
      Text(loaded.name)
    }
    Div({ style { property("color", "var(--muted)"); property("font-size", "13px"); property("line-height", "18px") } }) { Text(fileMeta(loaded)) }
  }
}

/** «LandXML · 2,4 МБ · 504 треугольника»: размер и треугольники — если известны. */
@Composable
private fun fileMeta(loaded: LoadState.Loaded): String {
  val parts = mutableListOf(fileFormat(loaded.name))
  loaded.sizeBytes?.let { parts += formatSize(it) }
  val triangles = loaded.scene.surfaces.sumOf { it.triangleCount }
  if (triangles > 0) parts += pluralStringResource(Res.plurals.triangles, triangles, groupDigits(triangles.toLong()))
  return parts.joinToString(" · ")
}

/** Размер файла: меньше мегабайта — в КБ, иначе в МБ с десятыми. */
@Composable
fun formatSize(bytes: Long): String {
  if (bytes < 1_000_000) return stringResource(Res.string.kilobytes, ((bytes + 999) / 1000).toInt())
  val tenths = bytes * 10 / 1_000_000
  return stringResource(Res.string.megabytes, tenths / 10, tenths % 10)
}
