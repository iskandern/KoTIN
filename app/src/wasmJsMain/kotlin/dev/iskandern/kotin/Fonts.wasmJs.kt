package dev.iskandern.kotin

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.Font
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.preloadFont

// web: шрифт приходит по сети; пока не загружен — null
@OptIn(ExperimentalResourceApi::class)
@Composable
actual fun rememberFont(spec: FontSpec): Font? = preloadFont(spec.resource, spec.weight, spec.style).value
