package com.kmusic.apkhd.playback

import org.junit.Assert.*
import org.junit.Test

class PurePcmFxPolicyTest {
    @Test fun purePcmBypassIsNotReportedAsOffload() {
        val status = FxStatus.resolve(false, false, true, 3, true, pureAudioMode = true)
        assertEquals(FxStatus.Bypassed(FxStatus.Bypassed.Reason.PURE_PCM), status)
    }

    @Test fun purePcmReasonPrecedesRequestedOffloadAndDetachedState() {
        val status = FxStatus.resolve(true, false, true, 0, false, pureAudioMode = true)
        assertEquals(FxStatus.Bypassed(FxStatus.Bypassed.Reason.PURE_PCM), status)
    }

    @Test fun normalPcmWithoutEffectsRemainsIdle() {
        assertEquals(FxStatus.Idle, FxStatus.resolve(false, false, true, 0, true))
    }
}
