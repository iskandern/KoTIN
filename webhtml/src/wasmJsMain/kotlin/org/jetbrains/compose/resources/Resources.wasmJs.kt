package org.jetbrains.compose.resources

import dev.iskandern.kotin.io.JsBytesInput
import dev.iskandern.kotin.io.readAllBytes
import kotlinx.coroutines.await
import kotlin.js.Promise

@JsFun("() => navigator.language || ''")
private external fun navigatorLanguage(): String

actual fun resourceLanguage(): String = navigatorLanguage().lowercase()

@JsFun("(url) => fetch(url).then((r) => { if (!r.ok) throw new Error(r.status + ' ' + url); return r.arrayBuffer(); }).then((b) => new Int8Array(b))")
private external fun fetchBytes(url: String): Promise<JsAny>

actual suspend fun readResourceBytes(path: String): ByteArray {
  val input = JsBytesInput(fetchBytes("composeResources/dev.iskandern.kotin.resources/$path").await())
  return input.readAllBytes()
}
