package dev.iskandern.kotin

import dev.iskandern.kotin.scene.buildRenderScene
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Строки подписей входят в кадр исходного вида целиком. */
class LabelFrameTest {

  private fun line(x1: Double, y1: Double, x2: Double, y2: Double) = arrayOf(
    "0", "LINE", "8", "A", "10", "$x1", "20", "$y1", "30", "0", "11", "$x2", "21", "$y2", "31", "0",
  )

  private fun text(x: Double, y: Double, height: Double, value: String, alignH: Int = 0) = arrayOf(
    "0", "TEXT", "8", "A", "10", "$x", "20", "$y", "30", "0", "40", "$height", "1", value,
  ) + (if (alignH != 0) arrayOf("72", "$alignH", "11", "$x", "21", "$y", "31", "0") else emptyArray())

  private fun doc(entities: List<Array<String>>) = arrayOf(
    "0", "SECTION", "2", "ENTITIES", *entities.flatMap { it.toList() }.toTypedArray(), "0", "ENDSEC", "0", "EOF",
  )

  @Test
  fun `длинная строка справа от точки расширяет кадр вправо`() {
    val scene = buildRenderScene(readGeometry(*doc(listOf(line(0.0, 0.0, 100.0, 0.0), text(90.0, 5.0, 4.0, "Очень длинная подпись у края")))))
    val home = assertNotNull(scene.home)
    // сцена отцентрована на линии (середина — 50): правый край линии +50; строка ~28 знаков × 0,6 × 4 / 0,7 ≈ 96 от x = 90
    assertTrue(home.x + home.halfX > 120f, "${home.x + home.halfX}")
    assertTrue(home.x - home.halfX <= -50f + 1f)
  }

  @Test
  fun `строка выравнивания справа расширяет кадр влево`() {
    val scene = buildRenderScene(readGeometry(*doc(listOf(line(0.0, 0.0, 100.0, 0.0), text(10.0, 5.0, 4.0, "Подпись слева от точки", 2)))))
    val home = assertNotNull(scene.home)
    assertTrue(home.x - home.halfX < -40f, "${home.x - home.halfX}")
  }

  @Test
  fun `файл со стилями текста — подписи слева от линий в кадре`() {
    val file = java.io.File("src/jvmTest/resources/dxf/text-style.dxf")
    val scene = buildRenderScene(readGeometry(*file.readLines().toTypedArray()))
    val home = assertNotNull(scene.home)
    // строки стилей начинаются в x = −85, левее линий (±45)
    assertTrue(home.x - home.halfX <= -85f, "${home.x - home.halfX}")
    assertTrue(scene.labels.all { it.x >= home.x - home.halfX && it.x <= home.x + home.halfX })
  }

  @Test
  fun `отражённый текст kotin-35 целиком в кадре`() {
    val file = java.io.File("src/jvmTest/resources/dxf/text-mirror.dxf")
    val scene = buildRenderScene(readGeometry(*file.readLines().toTypedArray()))
    val home = assertNotNull(scene.home, "кадр должен расшириться под строки")
    // «ABC 71=2» выровнена вправо от x = −40: ~9 знаков × 0,6 × 1,5 / 0,7 ≈ 11 влево; линии кончаются на −40
    assertTrue(home.x - home.halfX < -50f, "${home.x - home.halfX}")
    assertTrue(home.x + home.halfX >= 40f)
  }

  @Test
  fun `короткие подписи кадр не меняют`() {
    val scene = buildRenderScene(readGeometry(*doc(listOf(line(0.0, 0.0, 100.0, 0.0), line(0.0, 0.0, 0.0, 100.0), text(40.0, 50.0, 2.0, "ab")))))
    assertNull(scene.home)
  }
}
