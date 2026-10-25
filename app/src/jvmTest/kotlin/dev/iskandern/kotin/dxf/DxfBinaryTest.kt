package dev.iskandern.kotin.dxf

import dev.iskandern.kotin.io.asByteInput
import dev.iskandern.kotin.model.SceneGeometry
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DxfBinaryTest {

  private fun geometry(name: String): SceneGeometry {
    val file = File("src/jvmTest/resources/dxf/$name")
    val document = file.inputStream().use { DxfDocumentReader(it.asByteInput()).read() }
    return DxfFlattener(document).flatten()
  }

  private fun assertSameGeometry(expected: SceneGeometry, actual: SceneGeometry) {
    assertTrue(expected.lines.isNotEmpty() && expected.labels.isNotEmpty() && expected.meshes.isNotEmpty())
    assertEquals(expected.lines.size, actual.lines.size)
    for (i in expected.lines.indices) {
      assertEquals(expected.lines[i].layer, actual.lines[i].layer)
      assertEquals(expected.lines[i].color, actual.lines[i].color)
      assertContentEquals(expected.lines[i].coords, actual.lines[i].coords)
    }
    assertEquals(expected.fills.size, actual.fills.size)
    assertEquals(expected.meshes.size, actual.meshes.size)
    assertEquals(expected.meshes.map { it.layer }, actual.meshes.map { it.layer })
    assertEquals(expected.labels.map { it.text to it.height }, actual.labels.map { it.text to it.height })
    assertEquals(
      expected.points.map { Triple(it.x, it.y, it.z) },
      actual.points.map { Triple(it.x, it.y, it.z) },
    )
    assertEquals(expected.layers.map { it.name }, actual.layers.map { it.name })
  }

  @Test
  fun binaryR2018MatchesAscii() =
    assertSameGeometry(geometry("binary-ascii-twin.dxf"), geometry("binary-r2018.dxf"))

  @Test
  fun binaryR2000MatchesAscii() =
    assertSameGeometry(geometry("binary-ascii-twin.dxf"), geometry("binary-r2000.dxf"))
}
