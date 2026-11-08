package com.kmusic.apkhd.audio

import com.kmusic.apkhd.data.LoudnessEntry
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class LoudnessPolicyTest {
    @Test fun versionZeroScanIsInvalidButTagFieldsSurviveDeserialization() {
        val old = """[{"id":"a","lufs":-17.0,"peak":0.8,"size":100,"mtime":200,"tagGainDb":-4.0,"tagPeak":0.9}]"""
        val e = Json.decodeFromString(ListSerializer(LoudnessEntry.serializer()), old).single()
        assertFalse(e.matchesScan(R128.ALGORITHM_VERSION, 100, 200))
        assertEquals(-4f, e.tagGainDb!!, 0f)
        assertEquals(0.9f, e.tagPeak!!, 0f)
    }

    @Test fun scanRequiresVersionAndKnownFingerprintMatch() {
        val e = LoudnessEntry("a", -17.0, 0.8f, 100, 200, algorithmVersion = R128.ALGORITHM_VERSION)
        assertTrue(e.matchesScan(R128.ALGORITHM_VERSION, 100, 200))
        assertFalse(e.matchesScan(R128.ALGORITHM_VERSION - 1, 100, 200))
        assertFalse(e.matchesScan(R128.ALGORITHM_VERSION, 101, 200))
        assertFalse(e.matchesScan(R128.ALGORITHM_VERSION, 100, 201))
        assertFalse(e.matchesScan(R128.ALGORITHM_VERSION, 0, 0))
        assertFalse(e.copy(mtime = 0).matchesScan(R128.ALGORITHM_VERSION, 100, 200))
        assertTrue(e.matchesScan(R128.ALGORITHM_VERSION, 100, 0))
    }

    @Test fun albumWithoutTagsExplicitlyUsesTrackFallback() {
        assertNull(LoudnessNormalizer.selectTag("album", null))
        assertEquals("track-fallback", LoudnessNormalizer.scanBasis("album"))
        val selection = LoudnessNormalizer.selectTag("album", TagReader.Loudness(-3f, null, 0.8f))!!
        assertEquals("track-fallback", selection.basis)
        assertEquals(-3f, selection.gainDb, 0f)
    }

    @Test fun albumTagDoesNotBorrowTrackPeak() {
        val selection = LoudnessNormalizer.selectTag("album", TagReader.Loudness(-2f, -5f, 0.4f))!!
        assertEquals("album", selection.basis)
        assertNull(selection.peak)
        assertEquals(-5f, selection.gainDb, 0f)
    }

    @Test fun scanningRejectsLongTracksAndRequiresNaturalEos() {
        assertTrue(R128Analyzer.canScanDuration(0))
        assertTrue(R128Analyzer.canScanDuration(600_000))
        assertFalse(R128Analyzer.canScanDuration(600_001))
        assertFalse(R128Analyzer.isCompleteScan(false))
        assertTrue(R128Analyzer.isCompleteScan(true))
    }

    @Test fun positiveGainNeverClaimsOrAppliesBoost() {
        for (peak in listOf(null, 0.1f, 0.5f, 0.98f)) {
            assertEquals(0f, LoudnessNormalizer.clampGain(6f, peak), 0f)
        }
        assertEquals(1f, LoudnessNormalizer.toLinear(12f), 0f)
        assertEquals(1f, LoudnessNormalizer.toLinear(Float.NaN), 0f)
        assertEquals(0.5011872f, LoudnessNormalizer.toLinear(-6f), 0.00001f)
        assertTrue(LoudnessNormalizer.clampGain(6f, 1f) < 0f)
        assertEquals(0f, LoudnessNormalizer.Applied(0f, null, "tag").gainDb, 0f)
        assertEquals(1f, LoudnessNormalizer.Applied(0f, null, "tag").volume, 0f)
    }
}
