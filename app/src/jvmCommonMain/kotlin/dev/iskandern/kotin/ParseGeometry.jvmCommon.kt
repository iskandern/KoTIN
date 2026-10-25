package dev.iskandern.kotin

import dev.iskandern.kotin.model.SceneGeometry

actual suspend fun parseGeometry(source: DrawingSource, onProgress: (bytesRead: Long) -> Unit): SceneGeometry =
  parseGeometryInline(source, onProgress)
