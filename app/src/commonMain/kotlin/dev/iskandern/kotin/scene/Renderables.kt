package dev.iskandern.kotin.scene

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import io.github.erkko68.filament.Box
import io.github.erkko68.filament.Engine
import io.github.erkko68.filament.Entity
import io.github.erkko68.filament.IndexBuffer
import io.github.erkko68.filament.Material
import io.github.erkko68.filament.MaterialInstance
import io.github.erkko68.filament.RenderableManager
import io.github.erkko68.filament.Texture
import io.github.erkko68.filament.TextureSampler
import io.github.erkko68.filament.VertexBuffer
import io.github.erkko68.filament.VertexBuffer.AttributeType
import io.github.erkko68.filament.VertexBuffer.VertexAttribute
import io.github.erkko68.filament.compose.FilamentEffect
import io.github.erkko68.filament.compose.FilamentSceneScope
import io.github.erkko68.filament.compose.LocalFilamentEngine
import io.github.erkko68.filament.compose.scene.LinearColor
import io.github.erkko68.filament.toBytes

/**
 * Треугольная сетка с цветом в каждой вершине (освещение уже «запечено» в [colors], RGBA).
 * Материал — unlit ([SURFACE_MATERIAL_PATH]), работает и на OpenGL ES 2.0.
 * [priority] — порядок отрисовки (0..7): при равной глубине виден объект с большим.
 * [steps] — число на вершину в CUSTOM0 (ступень порядка заливок, [FILL_MATERIAL_PATH]); null — атрибута нет.
 */
@Composable
fun FilamentSceneScope.ColoredMesh(
  positions: FloatArray,
  colors: FloatArray,
  indices: IntArray,
  material: MaterialInstance,
  boundingBox: Box,
  priority: Int,
  steps: FloatArray? = null,
) {
  // пустой буфер Filament на web бросает исключение в композиции — экран встаёт
  if (indices.isEmpty()) return
  FilamentEffect(positions, colors, indices, material, priority, steps) {
    val vb = VertexBuffer.Builder()
      .vertexCount(positions.size / 3)
      .bufferCount(if (steps == null) 2 else 3)
      .attribute(VertexAttribute.POSITION, 0, AttributeType.FLOAT3)
      .attribute(VertexAttribute.COLOR, 1, AttributeType.FLOAT4)
      .apply { if (steps != null) attribute(VertexAttribute.CUSTOM0, 2, AttributeType.FLOAT) }
      .build(engine)
    vb.setBufferAt(engine, 0, positions.toBytes())
    vb.setBufferAt(engine, 1, colors.toBytes())
    if (steps != null) vb.setBufferAt(engine, 2, steps.toBytes())

    val ib = indexBuffer(engine, indices)
    val entity = engine.entityManager.create()
    RenderableManager.Builder(1)
      .geometry(0, RenderableManager.PrimitiveType.TRIANGLES, vb, ib)
      .material(0, material)
      .boundingBox(boundingBox)
      .priority(priority)
      .castShadows(false)
      .receiveShadows(false)
      .build(engine, entity)
    scene.addEntity(entity)

    onDispose {
      scene.remove(entity)
      engine.renderableManager.destroy(entity)
      engine.entityManager.destroy(entity)
      engine.destroy(vb)
      engine.destroy(ib)
    }
  }
}

/**
 * Набор отрезков (PrimitiveType.LINES) из общего буфера вершин и 32-битного индексного буфера.
 * В filament-compose готового композабла для линий нет — собираем renderable вручную.
 */
@Composable
fun FilamentSceneScope.LineMesh(
  positions: FloatArray,
  indices: IntArray,
  material: MaterialInstance,
  boundingBox: Box,
  priority: Int,
) {
  if (indices.isEmpty()) return
  FilamentEffect(positions, indices, material, priority) {
    val vb = VertexBuffer.Builder()
      .vertexCount(positions.size / 3)
      .bufferCount(1)
      .attribute(VertexAttribute.POSITION, 0, AttributeType.FLOAT3)
      .build(engine)
    vb.setBufferAt(engine, 0, positions.toBytes())

    val ib = indexBuffer(engine, indices)
    val entity = engine.entityManager.create()
    RenderableManager.Builder(1)
      .geometry(0, RenderableManager.PrimitiveType.LINES, vb, ib)
      .material(0, material)
      .boundingBox(boundingBox)
      .priority(priority)
      .castShadows(false)
      .receiveShadows(false)
      .build(engine, entity)
    scene.addEntity(entity)

    onDispose {
      scene.remove(entity)
      engine.renderableManager.destroy(entity)
      engine.entityManager.destroy(entity)
      engine.destroy(vb)
      engine.destroy(ib)
    }
  }
}

/**
 * Толстые линии: ленты из квадов ([buildRibbon]) с вектором к другому концу отрезка в CUSTOM0 (FLOAT4).
 * Ширину в пикселях задаёт материал ([rememberRibbonMaterialInstance]), вершинный шейдер раздвигает ленту.
 */
@Composable
fun FilamentSceneScope.RibbonMesh(
  ribbon: RibbonGeometry,
  material: MaterialInstance,
  boundingBox: Box,
  priority: Int,
) {
  if (ribbon.indices.isEmpty()) return
  FilamentEffect(ribbon, material, priority) {
    val vb = VertexBuffer.Builder()
      .vertexCount(ribbon.positions.size / 3)
      .bufferCount(2)
      .attribute(VertexAttribute.POSITION, 0, AttributeType.FLOAT3)
      .attribute(VertexAttribute.CUSTOM0, 1, AttributeType.FLOAT4)
      .build(engine)
    vb.setBufferAt(engine, 0, ribbon.positions.toBytes())
    vb.setBufferAt(engine, 1, ribbon.deltas.toBytes())

    val ib = indexBuffer(engine, ribbon.indices)
    val entity = engine.entityManager.create()
    RenderableManager.Builder(1)
      .geometry(0, RenderableManager.PrimitiveType.TRIANGLES, vb, ib)
      .material(0, material)
      .boundingBox(boundingBox)
      .priority(priority)
      .castShadows(false)
      .receiveShadows(false)
      .build(engine, entity)
    scene.addEntity(entity)

    onDispose {
      scene.remove(entity)
      engine.renderableManager.destroy(entity)
      engine.entityManager.destroy(entity)
      engine.destroy(vb)
      engine.destroy(ib)
    }
  }
}

/**
 * Квады подписей или значков одного слоя ([TextLayerMesh]) на материале [rememberTextMaterialInstance].
 * Вершины стоят в точке вставки, раскладку по камере делает вершинный шейдер, поэтому масштаб по высоте
 * ([scaleY] — вертикальное преувеличение) задаётся трансформацией объекта, а не перезаливкой вершин.
 * Габариты — огромные: вершины шейдер двигает, а подписи могут лежать за габаритами поверхности.
 * [halo] — экземпляр прохода ореола ([rememberTextMaterialInstance] с `haloDp`): те же квады вторым примитивом, порядок смешивания
 * общий для всех подписей — ореолы раньше любого текста. [outerHalo] — ещё один ореол, под [halo] (кант выбранной
 * точки). [blendOrder] — порядок смешивания первого прохода, следующие — за ним; выбранное ([SELECTED_LABELS_BLEND_ORDER],
 * [SELECTED_MARKS_BLEND_ORDER]) — после всех обычных подписей и значков.
 */
@Composable
fun FilamentSceneScope.TextQuads(
  mesh: TextLayerMesh, material: MaterialInstance, scaleY: Float, halo: MaterialInstance? = null,
  outerHalo: MaterialInstance? = null, blendOrder: Int = HALO_BLEND_ORDER,
) {
  val scale = rememberVerticalScale(scaleY)
  if (mesh.indices.isEmpty()) return
  FilamentEffect(mesh, material, halo, outerHalo, blendOrder) {
    val vb = VertexBuffer.Builder()
      .vertexCount(mesh.positions.size / 3)
      .bufferCount(5)
      .attribute(VertexAttribute.POSITION, 0, AttributeType.FLOAT3)
      .attribute(VertexAttribute.UV0, 1, AttributeType.FLOAT2)
      .attribute(VertexAttribute.COLOR, 2, AttributeType.UBYTE4)
      .normalized(VertexAttribute.COLOR)
      .attribute(VertexAttribute.CUSTOM0, 3, AttributeType.FLOAT4)
      .attribute(VertexAttribute.CUSTOM1, 4, AttributeType.FLOAT2)
      .build(engine)
    vb.setBufferAt(engine, 0, mesh.positions.toBytes())
    vb.setBufferAt(engine, 1, mesh.uvs.toBytes())
    vb.setBufferAt(engine, 2, mesh.colors)
    vb.setBufferAt(engine, 3, mesh.corners.toBytes())
    vb.setBufferAt(engine, 4, mesh.along.toBytes())

    val ib = indexBuffer(engine, mesh.indices)
    val entity = engine.entityManager.create()
    // примитивы по порядку рисования: ореолы снаружи внутрь, затем текст; порядок смешивания — общий для всех подписей
    val passes = listOfNotNull(outerHalo, halo, material)
    val builder = RenderableManager.Builder(passes.size)
    for ((i, pass) in passes.withIndex()) {
      builder.geometry(i, RenderableManager.PrimitiveType.TRIANGLES, vb, ib).material(i, pass)
      if (passes.size > 1 || blendOrder != HALO_BLEND_ORDER) builder.blendOrder(i, blendOrder + i).globalBlendOrderEnabled(i, true)
    }
    builder
      .boundingBox(Box(floatArrayOf(0f, 0f, 0f), floatArrayOf(TEXT_BOUNDS, TEXT_BOUNDS, TEXT_BOUNDS)))
      // после непрозрачной геометрии
      .priority(TEXT_PRIORITY)
      .castShadows(false)
      .receiveShadows(false)
      .build(engine, entity)
    scale.attach(engine, entity)
    scene.addEntity(entity)

    onDispose {
      scene.remove(entity)
      scale.detach(engine)
      engine.renderableManager.destroy(entity)
      engine.entityManager.destroy(entity)
      engine.destroy(vb)
      engine.destroy(ib)
    }
  }
}

private const val TEXT_BOUNDS = 1e7f
private const val TEXT_PRIORITY = 7
// ореол подписей и значков — 0, их текст — 1; выбранное — после них: плашки подписей, затем значки (три прохода);
// номера кандидатов выбора — поверх всего
private const val HALO_BLEND_ORDER = 0
const val SELECTED_LABELS_BLEND_ORDER = 2
const val SELECTED_MARKS_BLEND_ORDER = 3
const val CANDIDATE_MARKS_BLEND_ORDER = 6

private fun indexBuffer(engine: Engine, indices: IntArray): IndexBuffer {
  val ib = IndexBuffer.Builder()
    .indexCount(indices.size)
    .bufferType(IndexBuffer.IndexType.UINT)
    .build(engine)
  ib.setBuffer(engine, indices.toBytes())
  return ib
}

/**
 * Масштаб объекта по вертикали через TransformManager, без пересоздания буферов. Порядок DisposableEffect
 * и SideEffect не гарантирован — последнее значение хранится здесь и применяется и при создании объекта,
 * и при изменении.
 */
private class VerticalScale {
  var scaleY = 1f
  private var entity: Entity? = null
  private val matrix = floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)

  fun attach(engine: Engine, entity: Entity) {
    engine.transformManager.create(entity)
    this.entity = entity
    apply(engine)
  }

  fun detach(engine: Engine) {
    val attached = entity ?: return
    engine.transformManager.destroy(attached)
    entity = null
  }

  fun apply(engine: Engine) {
    val attached = entity ?: return
    // матрица по столбцам: масштаб по Y — элемент 5
    matrix[5] = scaleY
    engine.transformManager.setTransform(engine.transformManager.getInstance(attached), matrix)
  }
}

@Composable
private fun rememberVerticalScale(scaleY: Float): VerticalScale {
  val engine = LocalFilamentEngine.current ?: error("@error_no_engine")
  val scale = remember { VerticalScale() }
  SideEffect {
    scale.scaleY = scaleY
    scale.apply(engine)
  }
  return scale
}

/**
 * Сдвиг вершины к камере (см. `biasDepth` в [DEPTH_BIAS_FUNCTION]) — доля расстояния `perDepth × глубина + fraction`:
 * первое слагаемое — шаги буфера глубины, второе — запас на наклон грани.
 */
data class DepthBias(val perDepth: Float, val fraction: Float)

operator fun Float.times(bias: DepthBias) = DepthBias(this * bias.perDepth, this * bias.fraction)
operator fun DepthBias.plus(other: DepthBias) = DepthBias(perDepth + other.perDepth, fraction + other.fraction)

/**
 * Сдвиг глубины к камере ([depthBias]) меняется при каждом движении камеры — задаётся параметром материала,
 * экземпляр не пересоздаётся.
 */
@Composable
private fun MaterialInstance.applyDepthBias(depthBias: DepthBias) {
  SideEffect { setParameter("depthBias", depthBias.perDepth, depthBias.fraction) }
}

/**
 * Экземпляр материала линий ([LINE_MATERIAL_PATH]) цвета [color]; уничтожается вместе с композицией. Цвет — параметр
 * живого экземпляра: его смена (подсветка слоя, каркас при выключенной поверхности) не пересоздаёт экземпляр, а значит,
 * и сетку, которая на него ссылается.
 */
@Composable
fun rememberLineMaterialInstance(material: Material, color: LinearColor, depthBias: DepthBias, depthWrite: Boolean = true): MaterialInstance {
  val engine = LocalFilamentEngine.current ?: error("@error_no_engine")
  val instance = remember(material, depthWrite) {
    material.createInstance().apply {
      setParameter("color", color.r, color.g, color.b)
      isDepthWriteEnabled = depthWrite
    }
  }
  LaunchedEffect(instance, color) { instance.setParameter("color", color.r, color.g, color.b) }
  DisposableEffect(instance) {
    onDispose { engine.destroy(instance) }
  }
  instance.applyDepthBias(depthBias)
  return instance
}

/**
 * Экземпляр материала лент ([RIBBON_MATERIAL_PATH] или капсулы обводки [RIBBON_CAPSULE_MATERIAL_PATH]): цвет (параметр,
 * как у [rememberLineMaterialInstance]) и ширина в пикселях.
 */
@Composable
fun rememberRibbonMaterialInstance(
  material: Material, color: LinearColor, widthPx: Float, depthBias: DepthBias, depthWrite: Boolean = true,
): MaterialInstance {
  val engine = LocalFilamentEngine.current ?: error("@error_no_engine")
  val instance = remember(material, widthPx, depthWrite) {
    material.createInstance().apply {
      setParameter("color", color.r, color.g, color.b)
      setParameter("width", widthPx)
      isDepthWriteEnabled = depthWrite
    }
  }
  LaunchedEffect(instance, color) { instance.setParameter("color", color.r, color.g, color.b) }
  DisposableEffect(instance) {
    onDispose { engine.destroy(instance) }
  }
  instance.applyDepthBias(depthBias)
  return instance
}

/**
 * Экземпляр материала поверхности; цвет берётся из вершин, подсветка — [applyTone]. Свой на каждый слой — у слоёв
 * разный сдвиг глубины и своя подсветка.
 */
@Composable
fun rememberSurfaceMaterialInstance(material: Material, depthBias: DepthBias): MaterialInstance {
  val engine = LocalFilamentEngine.current ?: error("@error_no_engine")
  val instance = remember(material) { material.createInstance().apply { setParameter("tint", 0f, 0f, 0f, 0f) } }
  DisposableEffect(instance) {
    onDispose { engine.destroy(instance) }
  }
  instance.applyDepthBias(depthBias)
  return instance
}

/**
 * Экземпляр материала заливок ([FILL_MATERIAL_PATH]): общий сдвиг [depthBias] и сдвиг на ступень порядка
 * прорисовки — [stepPerDepth] (доля на единицу глубины, как `perDepth`), но не больше доли [stepMax];
 * меняются с камерой.
 */
@Composable
fun rememberFillMaterialInstance(material: Material, depthBias: DepthBias, stepPerDepth: Float, stepMax: Float): MaterialInstance {
  val instance = rememberSurfaceMaterialInstance(material, depthBias)
  SideEffect { instance.setParameter("stepBias", stepPerDepth, stepMax) }
  return instance
}

/** Текстура атласа глифов ([GlyphAtlas]); уничтожается вместе с композицией. */
@Composable
fun rememberGlyphTexture(atlas: GlyphAtlas): Texture {
  val engine = LocalFilamentEngine.current ?: error("@error_no_engine")
  val texture = remember(atlas) {
    Texture.Builder()
      .width(atlas.width)
      .height(atlas.height)
      .levels(1)
      .sampler(Texture.Sampler.SAMPLER_2D)
      .format(Texture.InternalFormat.RGBA8)
      .build(engine)
      .also {
        it.setImage(engine, 0, Texture.PixelBufferDescriptor(atlas.rgba, atlas.rgba.size, Texture.Format.RGBA, Texture.Type.UBYTE))
      }
  }
  DisposableEffect(texture) {
    onDispose { engine.destroy(texture) }
  }
  return texture
}

/**
 * Экземпляр материала подписей ([TEXT_MATERIAL_PATH]). Пороги размера — в физических пикселях ([density] — пикселей на dp).
 * [depthTest] выключен — подписи рисуются поверх поверхности, не прячась за рельефом.
 * [thin] — прореживать подписи по масштабу (приоритетный отсев); иначе показаны все, настоящего размера.
 * [haloDp] > 0 — экземпляр прохода ореола этой ширины и цвета [haloRgb] (0xRRGGBB, sRGB) ([TEXT_FRAGMENT]), 0 — самого текста.
 */
@Composable
fun rememberTextMaterialInstance(
  material: Material, texture: Texture, density: Float, depthTest: Boolean, thin: Boolean, haloDp: Float = 0f, haloRgb: Int = HALO_RGB,
): MaterialInstance {
  val engine = LocalFilamentEngine.current ?: error("@error_no_engine")
  val instance = remember(material, texture, density, depthTest, thin, haloDp, haloRgb) {
    material.createInstance().apply {
      setParameter("atlas", texture, TextureSampler(TextureSampler.MinFilter.LINEAR, TextureSampler.MagFilter.LINEAR, TextureSampler.WrapMode.CLAMP_TO_EDGE))
      setParameter("density", density)
      setParameter("thin", if (thin) 1f else 0f)
      setParameter("readablePx", READABLE_TEXT_DP * density)
      setParameter("minMarkerPx", MIN_MARKER_DP * density)
      setParameter("maxPx", MAX_TEXT_DP * density)
      // без подкраски: цвета из вершин
      setParameter("tint", 0f, 0f, 0f, 0f)
      setParameter("haloColor", ((haloRgb shr 16) and 0xFF) / 255f, ((haloRgb shr 8) and 0xFF) / 255f, (haloRgb and 0xFF) / 255f)
      setParameter("haloPx", haloDp * density)
      if (!depthTest) depthFunc = TextureSampler.CompareFunc.A
    }
  }
  DisposableEffect(instance) {
    onDispose { engine.destroy(instance) }
  }
  return instance
}

/**
 * Показывает поверхность цвета [base] (вершины — из [bakeSurfaceColors]) в тоне [toneRgb] (sRGB при полном свете) с
 * той же светотенью; null — цвета из вершин. У вершин светотень — общий множитель каналов, поэтому тон делится на сумму
 * каналов [base] на экране (см. [SURFACE_FRAGMENT]). Это значение параметра материала: сетка и её буферы на GPU
 * остаются как есть. Меняется только при смене значения, а не на каждой перекомпоновке.
 */
@Composable
fun MaterialInstance.applyTone(base: LinearColor, toneRgb: Int?) {
  LaunchedEffect(this, base, toneRgb) {
    if (toneRgb == null) {
      setParameter("tint", 0f, 0f, 0f, 0f)
    } else {
      val shown = displayColor(base)
      val sum = 255f * (shown.r + shown.g + shown.b)
      setParameter("tint", ((toneRgb shr 16) and 0xFF) / sum, ((toneRgb shr 8) and 0xFF) / sum, (toneRgb and 0xFF) / sum, 1f)
    }
  }
}

// минимальный читаемый кегль и верхний предел, dp; когда нет места даже под читаемый, подпись скрывается
internal const val READABLE_TEXT_DP = 12f
internal const val MAX_TEXT_DP = 80f
internal const val MIN_MARKER_DP = 6f

// ореол подписей (как обводка 4 px в эталоне — 2 px наружу) и значков точек (обводка 1 px)
internal const val HALO_DP = 2f
internal const val POINT_HALO_DP = 1f
private const val HALO_RGB = 0xFFFFFF

