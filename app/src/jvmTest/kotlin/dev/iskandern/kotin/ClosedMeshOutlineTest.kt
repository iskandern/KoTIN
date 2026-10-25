package dev.iskandern.kotin

import dev.iskandern.kotin.scene.buildRenderScene
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * kotin-23: куб из 3DFACE — замкнутая сетка без внешней границы. Пустая обводка шла в Filament пустым буфером, и на
 * web файл не открывался (исключение JS в композиции, экран вставал). Обводки у такой сетки нет — null, а не пустой массив.
 */
class ClosedMeshOutlineTest {

  @Test
  fun `замкнутая сетка без обводки, открытые — с обводкой`() {
    val scene = buildRenderScene(readGeometry(File("src/jvmTest/resources/dxf/3dface-quad.dxf")))
    val box = scene.surfaces.single { it.layer == "Box" }
    assertNull(box.outline)
    // ни одной пустой обводки: у открытых сеток граница есть
    for (surface in scene.surfaces) surface.outline?.let { assertTrue(it.isNotEmpty(), surface.layer) }
    assertEquals(2, scene.surfaces.count { it.outline != null })
  }
}
