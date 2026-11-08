package com.kmusic.apkhd.playback

import org.junit.Assert.*
import org.junit.Test

class CodecRoutingPolicyTest {
    @Test fun alacAndVorbisUseSoftwareOnlyWhenBothCapabilityChecksPass() {
        for (mime in listOf("audio/alac", "audio/vorbis")) {
            assertTrue(CodecRoutingPolicy.useSoftwareDecoder(mime, true, true))
            assertFalse(CodecRoutingPolicy.useSoftwareDecoder(mime, false, true))
            assertFalse(CodecRoutingPolicy.useSoftwareDecoder(mime, true, false))
            assertFalse(CodecRoutingPolicy.useSoftwareDecoder(mime, false, false))
        }
    }

    @Test fun otherAudioAndVideoKeepPlatformCandidates() {
        for (mime in listOf("audio/flac", "audio/mpeg", "audio/mp4a-latm", "audio/opus", "video/avc", "video/x-vnd.on2.vp9", "application/ogg")) {
            assertFalse(mime, CodecRoutingPolicy.useSoftwareDecoder(mime, true, true))
        }
    }

    @Test fun secureAndTunneledTracksNeverLoseTheirPlatformCandidate() {
        for (mime in listOf("audio/alac", "audio/vorbis")) {
            assertFalse(CodecRoutingPolicy.useSoftwareDecoder(mime, true, true, secure = true))
            assertFalse(CodecRoutingPolicy.useSoftwareDecoder(mime, true, true, tunneling = true))
        }
    }

    @Test fun nameOrContainerIsNotMistakenForAudioMimeSupport() {
        for (mime in listOf("vorbis", "audio/ogg", "audio/Vorbis", "雪映移城", "")) {
            assertFalse(CodecRoutingPolicy.useSoftwareDecoder(mime, true, true))
        }
    }

    @Test fun vorbisPlatformSoftwareComesBeforeVendorAndKeepsEveryCandidate() {
        val original = listOf("c2.vendor.vorbis.decoder", "c2.android.vorbis.decoder", "OMX.google.vorbis.decoder", "OMX.vendor.vorbis.decoder")
        val ordered = CodecRoutingPolicy.platformSoftwareFirst("audio/vorbis", original, name = { it })
        assertEquals(listOf("c2.android.vorbis.decoder", "OMX.google.vorbis.decoder", "c2.vendor.vorbis.decoder", "OMX.vendor.vorbis.decoder"), ordered)
        assertEquals(original.size, ordered.size)
        assertEquals(original.toSet(), ordered.toSet())
    }

    @Test fun softwareOnlyMetadataAndStableSoftwareOrderAreRespected() {
        val original = listOf("vendor-hardware", "vendor-software", "c2.android.vorbis.decoder")
        val ordered = CodecRoutingPolicy.platformSoftwareFirst("audio/vorbis", original,
            name = { it }, softwareOnly = { it == "vendor-software" })
        assertEquals(listOf("vendor-software", "c2.android.vorbis.decoder", "vendor-hardware"), ordered)
        assertEquals(original, CodecRoutingPolicy.platformSoftwareFirst("audio/flac", original, name = { it }))
        assertEquals(original, CodecRoutingPolicy.platformSoftwareFirst("audio/vorbis", original, name = { it }, secure = true))
        assertEquals(original, CodecRoutingPolicy.platformSoftwareFirst("audio/vorbis", original, name = { it }, tunneling = true))
    }

    @Test fun noPlatformSoftwareCandidateNeverDeletesVendorFallback() {
        val original = listOf("c2.vendor.vorbis.decoder", "OMX.vendor.vorbis.decoder")
        assertEquals(original, CodecRoutingPolicy.platformSoftwareFirst("audio/vorbis", original, name = { it }))
        assertTrue(CodecRoutingPolicy.platformSoftwareFirst("audio/vorbis", emptyList<String>(), name = { it }).isEmpty())
    }
}
