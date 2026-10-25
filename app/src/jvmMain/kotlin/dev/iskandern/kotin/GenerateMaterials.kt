package dev.iskandern.kotin

import io.github.erkko68.filament.VertexBuffer.VertexAttribute
import io.github.erkko68.filament.Engine
import io.github.erkko68.filament.filamat.MaterialBuilder
import io.github.erkko68.filament.filamat.MaterialPackage
import dev.iskandern.kotin.scene.DEPTH_BIAS_VERTEX
import dev.iskandern.kotin.scene.FILL_MATERIAL_PATH
import dev.iskandern.kotin.scene.FILL_VERTEX
import dev.iskandern.kotin.scene.LINE_FRAGMENT
import dev.iskandern.kotin.scene.LINE_MATERIAL_PATH
import dev.iskandern.kotin.scene.RIBBON_CAPSULE_FRAGMENT
import dev.iskandern.kotin.scene.RIBBON_CAPSULE_MATERIAL_PATH
import dev.iskandern.kotin.scene.RIBBON_CAPSULE_VERTEX
import dev.iskandern.kotin.scene.RIBBON_MATERIAL_PATH
import dev.iskandern.kotin.scene.RIBBON_VERTEX
import dev.iskandern.kotin.scene.SURFACE_FRAGMENT
import dev.iskandern.kotin.scene.TEXT_FRAGMENT
import dev.iskandern.kotin.scene.TEXT_MATERIAL_PATH
import dev.iskandern.kotin.scene.TEXT_VERTEX
import dev.iskandern.kotin.scene.SURFACE_MATERIAL_PATH
import dev.iskandern.kotin.scene.TRANSPARENT_FILL_FRAGMENT
import dev.iskandern.kotin.scene.TRANSPARENT_FILL_MATERIAL_PATH
import java.io.File

/** Компилирует материалы вьюера в .filamat для всех бэкендов, включая OpenGL ES 2.0. */
fun main(args: Array<String>) {
  val outDir = File(args.single()).apply { mkdirs() }
  MaterialBuilder.init()

  val surface = MaterialBuilder()
    .name("DxfSurface")
    .shading(MaterialBuilder.Shading.UNLIT)
    .require(VertexAttribute.COLOR)
    .parameter("depthBias", MaterialBuilder.UniformType.FLOAT2)
    .parameter("tint", MaterialBuilder.UniformType.FLOAT4)
    .materialVertex(DEPTH_BIAS_VERTEX)
    .material(SURFACE_FRAGMENT)
    .culling(MaterialBuilder.CullingMode.NONE)
    .forAllBackends()
    .build()
  write(outDir, SURFACE_MATERIAL_PATH, surface)

  // заливки чертежа: как поверхность, плюс ступень порядка прорисовки на вершину (FILL_VERTEX)
  val fill = MaterialBuilder()
    .name("DxfFill")
    .shading(MaterialBuilder.Shading.UNLIT)
    .require(VertexAttribute.COLOR)
    .require(VertexAttribute.CUSTOM0)
    .parameter("depthBias", MaterialBuilder.UniformType.FLOAT2)
    .parameter("stepBias", MaterialBuilder.UniformType.FLOAT2)
    .parameter("tint", MaterialBuilder.UniformType.FLOAT4)
    .materialVertex(FILL_VERTEX)
    .material(SURFACE_FRAGMENT)
    .culling(MaterialBuilder.CullingMode.NONE)
    .forAllBackends()
    .build()
  write(outDir, FILL_MATERIAL_PATH, fill)

  // полупрозрачные заливки: как заливки (со ступенями порядка), но со смешиванием и без записи глубины
  val transparentFill = MaterialBuilder()
    .name("DxfTransparentFill")
    .shading(MaterialBuilder.Shading.UNLIT)
    .require(VertexAttribute.COLOR)
    .require(VertexAttribute.CUSTOM0)
    .parameter("depthBias", MaterialBuilder.UniformType.FLOAT2)
    .parameter("stepBias", MaterialBuilder.UniformType.FLOAT2)
    .parameter("tint", MaterialBuilder.UniformType.FLOAT4)
    .materialVertex(FILL_VERTEX)
    .material(TRANSPARENT_FILL_FRAGMENT)
    .blending(MaterialBuilder.BlendingMode.TRANSPARENT)
    .depthWrite(false)
    .culling(MaterialBuilder.CullingMode.NONE)
    .forAllBackends()
    .build()
  write(outDir, TRANSPARENT_FILL_MATERIAL_PATH, transparentFill)

  val line = MaterialBuilder()
    .name("DxfLine")
    .shading(MaterialBuilder.Shading.UNLIT)
    .parameter("color", MaterialBuilder.UniformType.FLOAT3)
    .parameter("depthBias", MaterialBuilder.UniformType.FLOAT2)
    .materialVertex(DEPTH_BIAS_VERTEX)
    .material(LINE_FRAGMENT)
    .culling(MaterialBuilder.CullingMode.NONE)
    .forAllBackends()
    .build()
  write(outDir, LINE_MATERIAL_PATH, line)

  // толстые линии: ленты из квадов, раздвигаются в вершинном шейдере (RIBBON_VERTEX); непрозрачные, без отбрасывания
  // фрагментов — ранний тест глубины у линий чертежа остаётся
  val ribbon = MaterialBuilder()
    .name("DxfRibbon")
    .shading(MaterialBuilder.Shading.UNLIT)
    .require(VertexAttribute.CUSTOM0)
    .parameter("color", MaterialBuilder.UniformType.FLOAT3)
    .parameter("width", MaterialBuilder.UniformType.FLOAT)
    .parameter("depthBias", MaterialBuilder.UniformType.FLOAT2)
    .materialVertex(RIBBON_VERTEX)
    .material(LINE_FRAGMENT)
    .culling(MaterialBuilder.CullingMode.NONE)
    .forAllBackends()
    .build()
  write(outDir, RIBBON_MATERIAL_PATH, ribbon)

  // обводка выбранной линии: та же лента, торцы круглые (RIBBON_CAPSULE_FRAGMENT отбрасывает фрагменты за капсулой)
  val ribbonCapsule = MaterialBuilder()
    .name("DxfRibbonCapsule")
    .shading(MaterialBuilder.Shading.UNLIT)
    .require(VertexAttribute.CUSTOM0)
    .parameter("color", MaterialBuilder.UniformType.FLOAT3)
    .parameter("width", MaterialBuilder.UniformType.FLOAT)
    .parameter("depthBias", MaterialBuilder.UniformType.FLOAT2)
    .variable(MaterialBuilder.Variable.CUSTOM0, "cap")
    .materialVertex(RIBBON_CAPSULE_VERTEX)
    .material(RIBBON_CAPSULE_FRAGMENT)
    .blending(MaterialBuilder.BlendingMode.MASKED)
    .maskThreshold(0.5f)
    .culling(MaterialBuilder.CullingMode.NONE)
    .forAllBackends()
    .build()
  write(outDir, RIBBON_CAPSULE_MATERIAL_PATH, ribbonCapsule)

  // подписи и точки: квады с SDF-атласом, раскладку по камере считает вершинный шейдер (TEXT_VERTEX)
  val text = MaterialBuilder()
    .name("DxfText")
    .shading(MaterialBuilder.Shading.UNLIT)
    .require(VertexAttribute.UV0)
    .require(VertexAttribute.COLOR)
    .require(VertexAttribute.CUSTOM0)
    .require(VertexAttribute.CUSTOM1)
    .parameter("atlas", MaterialBuilder.SamplerType.SAMPLER_2D, MaterialBuilder.SamplerFormat.FLOAT, MaterialBuilder.ParameterPrecision.MEDIUM)
    .parameter("density", MaterialBuilder.UniformType.FLOAT)
    .parameter("thin", MaterialBuilder.UniformType.FLOAT)
    .parameter("readablePx", MaterialBuilder.UniformType.FLOAT)
    .parameter("minMarkerPx", MaterialBuilder.UniformType.FLOAT)
    .parameter("maxPx", MaterialBuilder.UniformType.FLOAT)
    .parameter("tint", MaterialBuilder.UniformType.FLOAT4)
    .parameter("haloColor", MaterialBuilder.UniformType.FLOAT3)
    .parameter("haloPx", MaterialBuilder.UniformType.FLOAT)
    .materialVertex(TEXT_VERTEX)
    .material(TEXT_FRAGMENT)
    .blending(MaterialBuilder.BlendingMode.TRANSPARENT)
    .depthWrite(false)
    .culling(MaterialBuilder.CullingMode.NONE)
    .forAllBackends()
    .build()
  write(outDir, TEXT_MATERIAL_PATH, text)

  MaterialBuilder.shutdown()
}

// feature level 0 добавляет в материал шейдеры ESSL 1.0 для OpenGL ES 2.0
private fun MaterialBuilder.forAllBackends(): MaterialBuilder = targetApi(MaterialBuilder.TargetApi.ALL)
  .platform(MaterialBuilder.Platform.ALL)
  .featureLevel(Engine.FeatureLevel.FEATURE_LEVEL_0)

private fun write(outDir: File, path: String, pkg: MaterialPackage) {
  check(pkg.isValid) { "$path: материал не скомпилировался" }
  val out = File(outDir, path.substringAfter("files/"))
  out.writeBytes(pkg.data)
  println("Записан ${out.absolutePath} (${pkg.data.size} байт)")
}
