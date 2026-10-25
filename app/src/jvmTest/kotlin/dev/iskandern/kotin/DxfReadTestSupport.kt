package dev.iskandern.kotin

import dev.iskandern.kotin.dxf.DxfDocumentReader
import dev.iskandern.kotin.dxf.DxfFlattener
import dev.iskandern.kotin.model.SceneGeometry
import java.io.File
import dev.iskandern.kotin.io.asByteInput
import java.io.InputStream

fun readGeometry(input: InputStream): SceneGeometry = DxfFlattener(DxfDocumentReader(input.asByteInput()).read()).flatten()

fun readGeometry(file: File): SceneGeometry = file.inputStream().use { readGeometry(it) }

fun readGeometry(vararg pairs: String): SceneGeometry = readGeometry(pairs.joinToString("\n", postfix = "\n").byteInputStream())

/** Тестовые DXF — в src/jvmTest/resources/dxf. */
val testDxfDir: File get() = File(object {}.javaClass.getResource("/dxf")!!.toURI())
