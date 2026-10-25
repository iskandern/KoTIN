package dev.iskandern.kotin

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.painterResource
import dev.iskandern.kotin.resources.Res
import dev.iskandern.kotin.resources.kotin_icon_small

/** Имя приложения: заголовок окна. Android и web берут своё из ресурсов (`app_name`, `index.html`). */
const val APP_NAME = "KoTIN"

/** Словесный знак: плитка-знак и «Ko» цветом текста, «TIN» — акцентом. */
@Composable
fun KotinWordmark(modifier: Modifier = Modifier, iconSize: Dp = 20.dp) {
  Row(modifier, verticalAlignment = Alignment.CenterVertically) {
    Image(painterResource(Res.drawable.kotin_icon_small), null, Modifier.size(iconSize))
    Text(
      buildAnnotatedString {
        append("Ko")
        withStyle(SpanStyle(color = KotinColors.Accent)) { append("TIN") }
      },
      Modifier.padding(start = 6.dp), color = KotinColors.Ink, style = MaterialTheme.typography.bodyMedium,
      fontWeight = FontWeight.SemiBold, maxLines = 1,
    )
  }
}
