package dev.iskandern.kotin

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import dev.iskandern.kotin.io.isForeignFormat
import dev.iskandern.kotin.resources.Res
import dev.iskandern.kotin.resources.kilobytes
import dev.iskandern.kotin.resources.kotin_mark
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


/** Кнопка меню ≡: квадратная; пока меню открыто — тёмная. */
@Composable
fun MenuButton(open: Boolean, size: Dp, onClick: () -> Unit) {
  val shape = RoundedCornerShape(12.dp)
  Box(
    Modifier.uiTag("menu-button").floatingCard(shape).then(if (open) Modifier.background(KotinColors.Ink, shape) else Modifier),
  ) {
    IconBox(KotinIcons.Menu, stringResource(Res.string.menu), size, onClick, shape, tint = if (open) KotinColors.Card else KotinColors.Ink)
  }
}

/**
 * Карточка файла рядом с ≡: имя и строка «LandXML · 2,4 МБ · 504 треугольника», значок «открыть другой файл»;
 * пока файл читается — ход чтения, не открылся — строка «Не открылся ⌄», по нажатию — причина и
 * сообщение разбора, [onDismiss] убирает ошибку. Без файла — [KotinWordmark].
 */
@Composable
fun FileCard(loadState: LoadState, shown: LoadState.Loaded?, height: Dp, onOpen: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
  val shape = RoundedCornerShape(12.dp)
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
  Box(
    modifier.uiTag("file-card").heightIn(min = height).floatingCard(shape).clip(shape)
      .then(if (failed != null) Modifier.clickable(role = Role.Button) { expanded = !expanded } else Modifier),
  ) {
    Column {
      Row(Modifier.heightIn(min = height), verticalAlignment = Alignment.CenterVertically) {
        // имя и строку под ним можно выделить и скопировать; у ошибки нажатие раскрывает подробности — выделение
        // забирало бы его, сообщение разбора выделяется в подробностях
        val texts = @Composable {
          Column(Modifier.uiTag("file-texts").padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)) {
            if (title == null) KotinWordmark(Modifier.padding(bottom = 2.dp)) else Text(
              title, color = KotinColors.Ink, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
              maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
              Text(
                meta, Modifier.weight(1f, fill = false), color = if (failed != null) KotinColors.Error else KotinColors.Muted,
                fontSize = 12.sp, lineHeight = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
              )
              if (failed != null) {
                Icon(KotinIcons.ChevronDown, null, Modifier.padding(start = 2.dp).size(14.dp).rotate(if (expanded) 180f else 0f), tint = KotinColors.Error)
              }
            }
          }
        }
        if (failed != null) Box(Modifier.weight(1f)) { texts() } else SelectionContainer(Modifier.weight(1f)) { texts() }
        if (shown != null || failed != null) {
          Box(Modifier.padding(end = 2.dp).uiTag("file-open-other").size(height - 4.dp).clip(RoundedCornerShape(10.dp)).background(KotinColors.Subtle)) {
            IconBox(KotinIcons.Folder, stringResource(Res.string.open_other_file), height - 4.dp, onOpen, iconSize = 18.dp)
          }
        }
      }
      if (failed != null && expanded) LoadErrorDetails(failed, shown, onDismiss)
    }
    if (loading) {
      val mod = Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp)
      val p = progress
      if (p != null) LinearProgressIndicator(progress = { p }, modifier = mod) else LinearProgressIndicator(mod)
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
  Column(Modifier.uiTag("load-error-details").padding(start = 12.dp, end = 12.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
    Text(
      if (known) localized(failed.message) else stringResource(Res.string.load_failed_reason),
      color = KotinColors.Ink, fontSize = 13.sp, lineHeight = 17.sp,
    )
    if (shown != null) Text(stringResource(Res.string.load_failed_kept, shown.name), color = KotinColors.Muted, fontSize = 12.sp, lineHeight = 15.sp)
    if (!known) SelectionContainer {
      Text(
        localized(failed.message), color = KotinColors.Muted, fontFamily = MonoFamily, fontSize = 11.sp, lineHeight = 14.sp,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(KotinColors.Subtle).padding(8.dp),
      )
    }
    Text(
      stringResource(Res.string.load_failed_dismiss), color = KotinColors.Accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
      modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(role = Role.Button, onClick = onDismiss).padding(vertical = 4.dp),
    )
  }
}

/** Пустой экран: файла ещё нет — по центру приглашение открыть и подсказка платформы. */
@Composable
fun EmptyState(onOpen: () -> Unit, modifier: Modifier = Modifier) {
  Column(
    modifier.uiTag("empty-state").widthIn(max = 420.dp).floatingCard(RoundedCornerShape(20.dp), elevation = 8.dp).padding(horizontal = 28.dp, vertical = 32.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    Image(painterResource(Res.drawable.kotin_mark), null, Modifier.uiTag("empty-mark").size(80.dp, 60.dp))
    Text(
      stringResource(Res.string.open_hint), color = KotinColors.Ink, fontSize = 20.sp, lineHeight = 26.sp,
      fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
    )
    Button(onClick = onOpen, modifier = Modifier.uiTag("empty-open").height(48.dp), shape = RoundedCornerShape(12.dp)) {
      Text(stringResource(Res.string.open_file), fontWeight = FontWeight.SemiBold)
    }
    Text(stringResource(openFileHint), color = KotinColors.Muted, fontSize = 13.sp, lineHeight = 18.sp, textAlign = TextAlign.Center)
  }
}

/** Имя файла и строка «LandXML · 2,4 МБ · …» — первая строка бокового меню телефона, где карточки файла нет. */
@Composable
fun FileTitle(loaded: LoadState.Loaded) {
  SelectionContainer { Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
    Text(loaded.name, color = KotinColors.Ink, fontSize = 16.sp, lineHeight = 21.sp, fontWeight = FontWeight.SemiBold)
    Text(fileMeta(loaded), color = KotinColors.Muted, fontSize = 13.sp, lineHeight = 18.sp)
  } }
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
