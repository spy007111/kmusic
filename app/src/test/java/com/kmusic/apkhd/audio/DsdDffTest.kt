package com.kmusic.apkhd.audio

import androidx.media3.common.util.UnstableApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * DFF（DSDIFF）解析与解码测试。
 *
 * ffmpeg **没有** DFF demuxer（只有 dsf），所以 DFF 只能靠自己的实现验证。
 * 这里用手工构造的 DFF 字节流验证：
 * - 容器头解析（FS 采样率 / CHNL 声道 / DSD 数据偏移）
 * - 字节交织 + MSB-first 去交织后，解出的应是 1kHz
 */
@UnstableApi
class DsdDffTest {

    /** DFF chunk: 4 字节标识 + 8 字节 size（不含头）+ payload */
    private fun chunk(tag: String, payload: ByteArray): ByteArray {
        val out = ByteArray(12 + payload.size)
        tag.toByteArray(Charsets.ISO_8859_1).copyInto(out, 0)
        ByteBuffer.wrap(out, 4, 8).order(ByteOrder.BIG_ENDIAN).putLong(payload.size.toLong())
        payload.copyInto(out, 12)
        return out
    }

    /** 构造一个 DFF 文件（立体声 1kHz） */
    private fun buildDff(
        dsdRate: Int = 2822400,
        channels: Int = 2,
        seconds: Double = 0.5
    ): ByteArray {
        val n = (dsdRate * seconds).toInt()
        // 两个声道同相信号（方便验证）
        val bits = sigmaDeltaBits(1000.0, dsdRate, n)
        // 1 bit/样本 → 每声道 n/8 字节
        val bytesPerCh = (n + 7) / 8
        val msb = ByteArray(bytesPerCh)
        for (i in 0 until n) {
            // DFF 是 MSB-first：bit7 对应时间上最早的样本
            if (bits[i] == 1) msb[i shr 3] = (msb[i shr 3].toInt() or (1 shl (7 - (i and 7)))).toByte()
        }
        // 字节交织：ch0[0] ch1[0] ch0[1] ch1[1] ...
        val data = ByteArray(bytesPerCh * channels)
        for (i in 0 until bytesPerCh) {
            for (c in 0 until channels) data[i * channels + c] = msb[i]
        }

        val fsPayload = ByteBuffer.allocate(16)
            .order(ByteOrder.BIG_ENDIAN)
            .putInt(dsdRate).putLong(n.toLong()).putInt(4096).array()
        val chnlPayload = ByteBuffer.allocate(2 + channels * 2)
            .order(ByteOrder.BIG_ENDIAN)
            .putShort(channels.toShort()).apply {
                for (i in 0 until channels) putShort(i.toShort())
            }.array()

        val prop = chunk("PROP", "SND ".toByteArray(Charsets.ISO_8859_1) +
            chunk("FS  ", fsPayload) + chunk("CHNL", chnlPayload))
        val dsd = chunk("DSD ", data)
        val fver = chunk("FVER", ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(0x01050000).array())
        val body = fver + prop + dsd
        // FRM8 头 = "FRM8"(4) + size(8) + formType(4) = 16 字节
        val frm8 = ByteArray(16 + body.size)
        "FRM8".toByteArray(Charsets.ISO_8859_1).copyInto(frm8, 0)
        ByteBuffer.wrap(frm8, 4, 8).order(ByteOrder.BIG_ENDIAN).putLong((12 + body.size).toLong())
        "DSD ".toByteArray(Charsets.ISO_8859_1).copyInto(frm8, 12)
        body.copyInto(frm8, 16)
        return frm8
    }

    /** 二阶 sigma-delta 调制器（和 kmtest/mkdsf.py 一致） */
    private fun sigmaDeltaBits(freq: Double, sr: Int, n: Int): IntArray {
        val out = IntArray(n)
        var acc = 0.0
        var i1 = 0.0
        var i2 = 0.0
        val step = 2 * PI * freq / sr
        var ph = 0.0
        for (i in 0 until n) {
            val x = 0.5 * sin(ph)
            ph += step
            i1 += x - acc
            i2 += i1 - acc
            val bit = if (i2 >= 0) 1 else 0
            acc = if (bit == 1) 1.0 else -1.0
            out[i] = bit
        }
        return out
    }

    @Test
    fun `DFF 头解析出采样率声道和字节交织标记`() {
        val blob = buildDff(dsdRate = 2822400, channels = 2)
        val info = DsdContainerParser.parse(blob, blob.size, blob.size.toLong())!!

        assertEquals(2822400, info.dsdRate)
        assertEquals(2, info.channels)
        assertEquals(DsdInfo.Interleave.BYTE, info.interleave)
        assertTrue("DFF 必须是 MSB-first", !info.bitOrderLsbFirst)
        assertTrue(info.isSupported)
        assertEquals("DSD64", DsdInfo.rateLabel(info.dsdRate))
    }

    @Test
    fun `DFF 数据偏移指向 DSD chunk 的数据起点`() {
        val blob = buildDff()
        val info = DsdContainerParser.parse(blob, blob.size, blob.size.toLong())!!

        // FRM8(12) + FVER(12+4) + PROP(12+4+16+12+16+...) + DSD 头(12)
        // 关键：偏移必须 > 0 且 + dataSize == 文件末尾
        assertTrue("dataOffset 必须 > 0，实际 ${info.dataOffset}", info.dataOffset > 0)
        assertEquals(blob.size.toLong(), info.dataOffset + info.dataSize)
    }

    @Test
    fun `DFF 解码出的 PCM 是 1kHz`() {
        val blob = buildDff(dsdRate = 2822400, channels = 2, seconds = 1.0)
        val info = DsdContainerParser.parse(blob, blob.size, blob.size.toLong())!!
        val reader = DsdPcmReader(info, 44100)

        // 从 dataOffset 开始喂数据
        val src = blob.copyOfRange(info.dataOffset.toInt(), blob.size)
        // read() 每次把产出写到 out[0..]，返回字节数。
        // 要拼接多次产出必须用一个独立的累积缓冲。
        val chunkBuf = ByteArray(65536 * 8)
        val all = ByteArray(1 shl 20)
        var produced = 0
        var off = 0
        while (off < src.size && produced < all.size - 65536) {
            val chunk = minOf(65536, src.size - off)
            val got = reader.read(src.copyOfRange(off, off + chunk), chunk, chunkBuf)
            chunkBuf.copyInto(all, produced, 0, got)
            produced += got
            off += chunk
        }
        val out = all

        assertTrue("应该产出 PCM，实际 $produced", produced > 10000)

        // 提取左声道，找主频
        val ch = info.channels
        val frames = produced / 2 / ch
        val left = FloatArray(frames)
        for (i in 0 until frames) {
            val o = i * 2 * ch
            val lo = (out[o].toInt() and 0xFF) or ((out[o + 1].toInt() and 0xFF) shl 8)
            left[i] = (lo.toShort() / 32768f)
        }


        // 取稳态半秒（恰为500个1k周期）；不能把目标频率向下取整到FFT bin。
        val measuredFrames = 22050
        assertTrue(frames > measuredFrames + 1000)
        val omega = 2 * PI * 1000.0 / 44100.0
        var real = 0.0
        var imag = 0.0
        for (i in 0 until measuredFrames) {
            val sample = left[frames - measuredFrames + i]
            real += sample * cos(omega * i)
            imag -= sample * sin(omega * i)
        }
        val amp = 2.0 * kotlin.math.hypot(real, imag) / measuredFrames
        val db = 20 * kotlin.math.log10(amp.coerceAtLeast(1e-12))
        // 源为0.5幅度；排除起始瞬态和频率格点泄漏后应约−6.02dBFS。
        assertTrue("1kHz 幅度异常：$db dB", db in -6.4..-5.7)
    }

    @Test
    fun `DST 压缩的 DFF 被标记为不支持`() {
        // 造一个带 CMPR "DST " 的 DFF
        val cmpr = chunk("CMPR", "DST ".toByteArray(Charsets.ISO_8859_1) +
            ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putInt(0).putInt(0).array())
        val fsPayload = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN)
            .putInt(2822400).putLong(0).putInt(4096).array()
        val chnlPayload = ByteBuffer.allocate(6).order(ByteOrder.BIG_ENDIAN)
            .putShort(2).putShort(0).putShort(1).array()
        val prop = chunk("PROP", "SND ".toByteArray(Charsets.ISO_8859_1) +
            chunk("FS  ", fsPayload) + chunk("CHNL", chnlPayload) + cmpr)
        val dsd = chunk("DSD ", ByteArray(1024))
        val body = prop + dsd
        val frm8 = ByteArray(16 + body.size)
        "FRM8".toByteArray(Charsets.ISO_8859_1).copyInto(frm8, 0)
        ByteBuffer.wrap(frm8, 4, 8).order(ByteOrder.BIG_ENDIAN).putLong((12 + body.size).toLong())
        "DSD ".toByteArray(Charsets.ISO_8859_1).copyInto(frm8, 12)
        body.copyInto(frm8, 16)

        val info = DsdContainerParser.parse(frm8, frm8.size, frm8.size.toLong())
        // 解析器对不支持的压缩直接返回 null（由 Extractor 报错）
        assertTrue("DST 必须解析失败", info == null)
    }

    @Test
    fun `真实 DFF 文件能解析出正确倍率`() {
        val f = File(
            System.getProperty("user.home") + "/AppData/Local/hermes/cache/scratch/kmtest/dsd64_1k.dff"
        )
        if (!f.exists()) return
        val blob = f.readBytes()
        val info = DsdContainerParser.parse(blob, blob.size, blob.size.toLong())
        assertTrue("真实 DFF 应能解析", info != null)
        assertEquals("DSD64", DsdInfo.rateLabel(info!!.dsdRate))
        assertEquals(2, info.channels)
    }
}
