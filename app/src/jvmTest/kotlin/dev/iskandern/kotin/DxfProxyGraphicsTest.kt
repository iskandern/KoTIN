package dev.iskandern.kotin

import dev.iskandern.kotin.dxf.DxfDocumentReader
import dev.iskandern.kotin.dxf.DxfEntity
import dev.iskandern.kotin.dxf.HatchEdge
import dev.iskandern.kotin.io.asByteInput
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DxfProxyGraphicsTest {
  private fun entities(name: String): List<DxfEntity> =
    File(testDxfDir, "$name").inputStream().use { DxfDocumentReader(it.asByteInput()).read().entities }

  // ---------- образцы из задачи ----------

  @Test
  fun `MLEADER из Civil - надпись, заливка стрелки и две ломаные`() {
    val e = entities("proxy-leader.dxf")
    val label = e.filterIsInstance<DxfEntity.Text>().single { it.value == "LEADER" }
    assertEquals(16.7609, label.x, 1e-3)
    assertEquals(14.4531, label.y, 1e-3)
    // стрелка — залитый треугольник
    val arrow = e.filterIsInstance<DxfEntity.Hatch>().single()
    val edge = arrow.loops.single().edges.single() as HatchEdge.Segments
    assertEquals(3, edge.xy.size / 2)
  }

  @Test
  fun `гайка - ломаные, квадрат shell и буква из прокси`() {
    val e = entities("proxy-gayka.dxf")
    // квадрат 5×5 — плоский shell, на чертеже заливка, не поверхность
    assertEquals(0, e.count { it is DxfEntity.Polyface })
    val square = e.filterIsInstance<DxfEntity.Hatch>().map { it.loops.single().edges.single() as HatchEdge.Segments }
      .single { it.xy.size == 8 && Math.abs(it.xy[0] - 88.251) < 1e-3 && Math.abs(it.xy[1] - 276.272) < 1e-3 }
    assertEquals(93.251, square.xy[2], 1e-3)
    val letter = e.filterIsInstance<DxfEntity.Text>().single { it.value == "А" && it.height == 5.0 }
    assertEquals(88.2509, letter.x, 1e-3)
    assertEquals(276.2717, letter.y, 1e-3)
    assertTrue(e.filterIsInstance<DxfEntity.Polyline>().any { it.xyz.size == 6 && Math.abs(it.xyz[0] - 83.392) < 1e-3 && Math.abs(it.xyz[1] - 269.753) < 1e-3 })
    assertTrue(e.filterIsInstance<DxfEntity.Polyline>().any { it.xyz.size == 6 && Math.abs(it.xyz[0] - 86.251) < 1e-3 && Math.abs(it.xyz[3] - 95.251) < 1e-3 })
  }

  @Test
  fun `мусор вместо графики ничего не добавляет и не роняет разбор`() {
    val e = entities("proxy-fake.dxf")
    assertEquals(0, e.count { it is DxfEntity.Polyline || it is DxfEntity.Polyface || it is DxfEntity.Hatch })
    assertEquals(20, e.count { it is DxfEntity.Line })
  }

  // ---------- собранный поток ----------

  private fun ByteBuffer.vertex(x: Double, y: Double, z: Double = 0.0) = putDouble(x).putDouble(y).putDouble(z)

  private class Stream {
    val body = ByteBuffer.allocate(4096).order(ByteOrder.LITTLE_ENDIAN)
    var commands = 0

    fun command(type: Int, build: ByteBuffer.() -> Unit) {
      val start = body.position()
      body.putInt(0).putInt(type)
      body.build()
      body.putInt(start, body.position() - start)
      commands++
    }

    fun bytes(): ByteArray {
      val total = body.position() + 8
      val out = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
      out.putInt(total).putInt(commands)
      out.put(body.array(), 0, body.position())
      return out.array()
    }
  }

  private fun proxyDoc(stream: Stream, chunk: Int = 127): List<DxfEntity> {
    val data = stream.bytes()
    val dxf = StringBuilder("0\nSECTION\n2\nENTITIES\n0\nACAD_PROXY_ENTITY\n8\nP\n100\nAcDbProxyEntity\n90\n498\n91\n520\n92\n${data.size}\n")
    for (i in data.indices step chunk) {
      dxf.append("310\n").append(data.copyOfRange(i, minOf(i + chunk, data.size)).joinToString("") { "%02X".format(it) }).append('\n')
    }
    dxf.append("93\n4\n310\n01020304\n0\nENDSEC\n0\nEOF\n")
    return DxfDocumentReader(dxf.toString().byteInputStream().asByteInput()).read().entities
  }

  @Test
  fun `круг, дуга и 3-точечная окружность`() {
    val s = Stream()
    s.command(2) { vertex(1.0, 2.0, 0.0); putDouble(3.0); vertex(0.0, 0.0, 1.0) }
    s.command(4) { vertex(0.0, 0.0, 0.0); putDouble(5.0); vertex(0.0, 0.0, 1.0); vertex(0.0, 1.0, 0.0); putDouble(PI / 2); putInt(0) }
    s.command(3) { vertex(10.0, 0.0); vertex(0.0, 10.0); vertex(-10.0, 0.0) }
    val e = proxyDoc(s).filterIsInstance<DxfEntity.Arc>()
    assertEquals(3, e.size)
    assertTrue(e[0].isCircle); assertEquals(3.0, e[0].radius); assertEquals(1.0, e[0].cx); assertEquals(2.0, e[0].cy)
    // дуга от 90° на 90° против часовой
    assertEquals(90.0, e[1].startDeg, 1e-9); assertEquals(180.0, e[1].endDeg, 1e-9)
    assertTrue(e[2].isCircle); assertEquals(10.0, e[2].radius, 1e-9); assertEquals(0.0, e[2].cx, 1e-9)
  }

  @Test
  fun `ломаная, заливка и смена цвета и слоя`() {
    val s = Stream()
    s.command(14) { putInt(3) }
    s.command(6) { putInt(3); vertex(0.0, 0.0); vertex(1.0, 0.0); vertex(1.0, 1.0) }
    s.command(20) { putInt(1) }
    s.command(7) { putInt(3); vertex(0.0, 0.0); vertex(2.0, 0.0); vertex(2.0, 2.0) }
    // заливка действует на один примитив
    s.command(7) { putInt(3); vertex(5.0, 5.0); vertex(6.0, 5.0); vertex(6.0, 6.0) }
    val e = proxyDoc(s)
    val line = e[0] as DxfEntity.Polyline
    assertEquals(3, line.common.color)
    assertEquals(3, line.vertexCount)
    assertTrue(e[1] is DxfEntity.Hatch)
    val outline = e[2] as DxfEntity.Polyline
    assertTrue(outline.closed)
  }

  @Test
  fun `матрица сдвигает следующие примитивы, pop возвращает`() {
    val s = Stream()
    s.command(29) {
      for (v in doubleArrayOf(1.0, 0.0, 0.0, 10.0, 0.0, 1.0, 0.0, 20.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0)) putDouble(v)
    }
    s.command(12) { vertex(0.0, 0.0); vertex(1.0, 0.0) }
    s.command(31) {}
    s.command(13) { vertex(0.0, 0.0); vertex(0.0, 1.0) }
    val e = proxyDoc(s).filterIsInstance<DxfEntity.XLine>()
    assertEquals(10.0, e[0].x); assertEquals(20.0, e[0].y); assertTrue(!e[0].isRay)
    assertEquals(0.0, e[1].x); assertTrue(e[1].isRay)
  }

  @Test
  fun `обрезанная команда пропускается, остальные читаются`() {
    val s = Stream()
    s.command(6) { putInt(100); vertex(0.0, 0.0) } // обещает 100 вершин, данных на одну
    s.command(6) { putInt(2); vertex(0.0, 0.0); vertex(1.0, 1.0) }
    val e = proxyDoc(s).filterIsInstance<DxfEntity.Polyline>()
    assertEquals(1, e.size)
  }
}
