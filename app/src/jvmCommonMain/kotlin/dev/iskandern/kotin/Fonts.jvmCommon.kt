package dev.iskandern.kotin

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.Font
import org.jetbrains.compose.resources.Font

// JVM и Android читают шрифт из ресурсов сразу
@Composable
actual fun rememberFont(spec: FontSpec): Font? = Font(spec.resource, spec.weight, spec.style)
