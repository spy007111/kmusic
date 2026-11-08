package com.kmusic.apkhd.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.ByteBuffer
import java.nio.ByteOrder

@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class TruePeakProcessorIntegrationTest {
    private fun processor(enabled: Boolean = true): EqProcessor = EqProcessor().also {
        it.applyParams(false, IntArray(EqBands.COUNT), 0, 0f, truePeakEnabled = enabled)
        it.configure(AudioProcessor.AudioFormat(48000, 2, C.ENCODING_PCM_FLOAT))
        it.flush()
    }

    private fun input(frames: Int, value: Float = 0.1f): ByteBuffer =
        ByteBuffer.allocateDirect(frames * 8).order(ByteOrder.nativeOrder()).also { b ->
            repeat(frames) { b.putFloat(value); b.putFloat(-value) }
            b.flip()
        }

    private fun consume(processor: EqProcessor): List<Float> {
        val output = processor.output.order(ByteOrder.nativeOrder())
        val samples = ArrayList<Float>()
        while (output.remaining() >= 4) samples.add(output.float)
        return samples
    }

    @Test fun enabledWithoutEqDoesNotPassthroughAndEosDrainsShortInput() {
        val p = processor()
        val source = input(7)
        p.queueInput(source)
        assertEquals(source.limit(), source.position())
        assertTrue(consume(p).isEmpty())
        assertTrue(p.currentTruePeakActive)
        assertEquals(192, p.currentTruePeakLatencyFrames)
        p.queueEndOfStream()
        val output = consume(p)
        assertEquals(14, output.size)
        for (i in output.indices) assertEquals(if (i % 2 == 0) 0.1f else -0.1f, output[i], 0f)
        assertTrue(p.isEnded)
    }

    @Test fun blocksAndEosProduceExactlyInputFramesWithoutPadding() {
        val p = processor()
        var samples = 0
        for (frames in listOf(1, 31, 255, 400, 13)) {
            p.queueInput(input(frames))
            samples += consume(p).size
        }
        p.queueEndOfStream()
        samples += consume(p).size
        assertEquals((1 + 31 + 255 + 400 + 13) * 2, samples)
    }

    @Test fun flushAndResetCannotLeakPreviousTrackOrBufferedSamples() {
        val p = processor()
        p.queueInput(input(20, 1.5f))
        consume(p)
        p.flush()
        p.queueInput(input(9, 0.2f))
        consume(p)
        p.queueEndOfStream()
        val output = consume(p)
        assertEquals(18, output.size)
        for (i in output.indices) assertEquals(if (i % 2 == 0) 0.2f else -0.2f, output[i], 0f)
        p.reset()
        assertFalse(p.currentTruePeakActive)
        assertEquals(0, p.currentTruePeakLatencyFrames)
    }

    @Test fun disabledAndForcedBypassAreByteExactOnFreshStream() {
        for (bypass in listOf(false, true)) {
            val p = processor(enabled = bypass)
            p.isBypassed = bypass
            val bytes = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
            val source = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).put(bytes)
            source.flip()
            p.queueInput(source)
            val actual = ByteArray(bytes.size)
            p.output.get(actual)
            assertArrayEquals(bytes, actual)
            assertFalse(p.currentTruePeakActive)
            p.queueEndOfStream()
            assertEquals(0, p.output.remaining())
        }
    }

    @Test fun disablingMidstreamPreservesBufferedFramesAndFlushRestoresZeroDelay() {
        val p = processor()
        p.queueInput(input(31))
        var count = consume(p).size
        p.applyParams(false, IntArray(EqBands.COUNT), 0, 0f, truePeakEnabled = false)
        p.queueInput(input(255))
        count += consume(p).size
        assertFalse(p.currentTruePeakActive)
        assertEquals(192, p.currentTruePeakLatencyFrames)
        p.queueEndOfStream()
        count += consume(p).size
        assertEquals((31 + 255) * 2, count)
        p.flush()
        p.queueInput(input(7))
        assertEquals(14, consume(p).size)
        assertEquals(0, p.currentTruePeakLatencyFrames)
    }
}
