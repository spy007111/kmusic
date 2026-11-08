package com.kmusic.apkhd.playback

import org.junit.Assert.*
import org.junit.Test

class UnsupportedAudioPolicyTest {
    @Test fun vorbisUsesDeviceLimitNotGlobalEightChannelLimit() {
        assertFalse(UnsupportedAudioPolicy.acceptsVorbis("audio/vorbis", 12, 44100, 8, true))
        assertTrue(UnsupportedAudioPolicy.acceptsVorbis("audio/vorbis", 12, 44100, 12, true))
    }
    @Test fun sampleRateCapabilityIsChecked() {
        assertFalse(UnsupportedAudioPolicy.acceptsVorbis("audio/vorbis", 2, 192000, 8, false))
    }
    @Test fun unknownCapabilitiesAreNotGuessed() {
        assertTrue(UnsupportedAudioPolicy.acceptsVorbis("audio/vorbis", -1, -1, null, null))
        assertTrue(UnsupportedAudioPolicy.acceptsVorbis("audio/vorbis", 12, 44100, null, null))
    }
    @Test fun otherMimeTypesRetainExistingRouting() {
        assertTrue(UnsupportedAudioPolicy.acceptsVorbis("audio/flac", 12, 44100, 8, false))
        assertFalse(UnsupportedAudioPolicy.invalidVorbisOutput("audio/flac", 12, 1))
    }
    @Test fun multichannelVorbisMustNotSilentlyBecomeMono() {
        assertTrue(UnsupportedAudioPolicy.invalidVorbisOutput("audio/vorbis", 12, 1))
        assertTrue(UnsupportedAudioPolicy.invalidVorbisOutput("audio/vorbis", 6, 2))
        assertFalse(UnsupportedAudioPolicy.invalidVorbisOutput("audio/vorbis", 12, 12))
        assertFalse(UnsupportedAudioPolicy.invalidVorbisOutput("audio/vorbis", 2, 1))
        assertFalse(UnsupportedAudioPolicy.invalidVorbisOutput("audio/vorbis", 12, -1))
    }
    @Test fun onlyUnsupportedCodesUseSkipPath() {
        listOf(3003, 4001, 4002, 4004, 4005, 5001).forEach { assertTrue(UnsupportedAudioPolicy.isUnsupported(it)) }
        listOf(2001, 2002, 2004, 3001, 4003, 5002, 1000).forEach { assertFalse(UnsupportedAudioPolicy.isUnsupported(it)) }
    }
    @Test fun singleItemAndOrderTailStop() {
        val p = UnsupportedAudioSkipPolicy()
        assertNull(p.next("one", listOf("one"), "one", true))
        assertNull(p.next("two", listOf("one", "two"), null, false))
    }
    @Test fun repeatOneBypassesFailedCurrentItemWithoutChangingMode() {
        val p = UnsupportedAudioSkipPolicy()
        assertEquals("two", p.next("one", listOf("one", "two"), "one", true))
        assertNull(p.next("two", listOf("one", "two"), "two", true))
    }
    @Test fun allUnsupportedStopsAfterAtMostOneQueuePass() {
        val p = UnsupportedAudioSkipPolicy(); val q = listOf("a", "b", "c", "d")
        assertEquals("b", p.next("a", q, "b", true))
        assertEquals("c", p.next("b", q, "c", true))
        assertEquals("d", p.next("c", q, "d", true))
        assertNull(p.next("d", q, "a", true))
    }
    @Test fun shuffleSuggestionIsHonoredUnlessAlreadyFailed() {
        val p = UnsupportedAudioSkipPolicy(); val q = listOf("a", "b", "c")
        assertEquals("c", p.next("a", q, "c", true))
        assertEquals("b", p.next("c", q, "a", true))
    }
    @Test fun resetAllowsExplicitRetryAfterDeviceChanges() {
        val p = UnsupportedAudioSkipPolicy(); val q = listOf("a", "b")
        p.next("a", q, "b", true); assertNull(p.next("b", q, "a", true))
        p.reset(); assertEquals("a", p.next("b", q, "a", true))
    }
    @Test fun messageExplainsBothSkipAndStop() {
        assertTrue(UnsupportedAudioPolicy.message("测试曲", true).contains("已跳过"))
        assertTrue(UnsupportedAudioPolicy.message("测试曲", false).contains("已停止"))
    }
}
