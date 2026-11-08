package com.kmusic.apkhd.playback

import com.kmusic.apkhd.model.Track
import org.junit.Assert.*
import org.junit.Test

class PlaybackCacheKeyTest {
    private val resolved = Track("display-id", "Song", "Artist", "Album", source = "kuwo", sourceId = "123",
        qualityRank = 4, qualityLabel = "无损", sourceKbps = 900, sourceBytes = 20_000_000,
        durationMs = 180_000, playUrl = "https://cdn.example/music.flac?token=secret-a")

    @Test fun sameContentWithNewSignatureHasSameKey() {
        val key = PlaybackCacheKey.of(resolved)
        assertNotNull(key)
        assertEquals(key, PlaybackCacheKey.of(resolved.copy(playUrl = "https://other.example/music.flac?token=secret-b#k=old")))
        assertEquals(key, PlaybackCacheKey.of(resolved.copy(id = "other-display", name = "Other title", album = "Other album")))
        assertFalse(key!!.contains("secret"))
    }

    @Test fun actualSourceAndResourceDiffer() {
        val key = PlaybackCacheKey.of(resolved)
        assertNotEquals(key, PlaybackCacheKey.of(resolved.copy(source = "netease")))
        assertNotEquals(key, PlaybackCacheKey.of(resolved.copy(sourceId = "124")))
        assertNull(PlaybackCacheKey.of(resolved.copy(sourceId = "ne:123")))
    }

    @Test fun silentDowngradeAndMasterNeverShare() {
        val key = PlaybackCacheKey.of(resolved)
        assertNotEquals(key, PlaybackCacheKey.of(resolved.copy(qualityRank = 1, qualityLabel = "128k", sourceKbps = 128,
            sourceBytes = 3_000_000, playUrl = "https://cdn.example/music.mp3?requested=master")))
        assertNotEquals(key, PlaybackCacheKey.of(resolved.copy(qualityRank = 7, isMaster = true)))
        assertNotEquals(key, PlaybackCacheKey.of(resolved.copy(qualityLabel = "另一实际档位")))
    }

    @Test fun containerCodecAndResourceFingerprintSeparate() {
        val key = PlaybackCacheKey.of(resolved)
        assertNotEquals(key, PlaybackCacheKey.of(resolved.copy(sourceBytes = resolved.sourceBytes + 1)))
        assertNotEquals(key, PlaybackCacheKey.of(resolved.copy(durationMs = resolved.durationMs + 1)))
        assertNotEquals(key, PlaybackCacheKey.of(resolved, container = "mflac"))
        assertNotEquals(PlaybackCacheKey.of(resolved, codec = "flac"), PlaybackCacheKey.of(resolved, codec = "alac"))
        assertNotEquals(PlaybackCacheKey.of(resolved, resourceVersion = "v1"), PlaybackCacheKey.of(resolved, resourceVersion = "v2"))
    }

    @Test fun unknownIdentityOrActualQualityFallsBack() {
        assertNull(PlaybackCacheKey.of(resolved.copy(sourceId = "")))
        assertNull(PlaybackCacheKey.of(resolved.copy(sourceId = "https://example?id=123&token=x")))
        assertNull(PlaybackCacheKey.of(resolved.copy(qualityRank = 0)))
        assertNull(PlaybackCacheKey.of(resolved.copy(sourceBytes = 0, durationMs = 0)))
        assertNull(PlaybackCacheKey.of(resolved.copy(source = "local")))
        assertNull(PlaybackCacheKey.of(resolved.copy(source = "unknown")))
    }

    @Test fun formatsComeFromPathNeverQueryOrDisplayLabel() {
        assertEquals("mflac", PlaybackCacheKey.containerFromUrl("https://cdn.example/a.MFLAC?token=abc"))
        assertNull(PlaybackCacheKey.containerFromUrl("https://cdn.example/stream?format=flac&token=.flac"))
        assertNull(PlaybackCacheKey.containerFromUrl("not a url"))
    }

    @Test fun namespaceCanonicalizesServerAndSeparatesAccounts() {
        val a = PlaybackCacheKey.namespace("HTTPS://Dav.Example:443/music/", "alice")
        assertEquals(a, PlaybackCacheKey.namespace("https://dav.example/music", "alice"))
        assertNotEquals(a, PlaybackCacheKey.namespace("https://dav.example/music", "bob"))
        assertNotEquals(a, PlaybackCacheKey.namespace("https://other.example/music", "alice"))
        assertNotEquals(a, PlaybackCacheKey.namespace("https://dav.example/other", "alice"))
        assertNotNull(a)
        assertFalse(a!!.contains("alice"))
        assertNull(PlaybackCacheKey.namespace("https://alice:password@dav.example/music", "alice"))
        assertNull(PlaybackCacheKey.namespace("https://dav.example/music?token=secret", "alice"))
        assertNull(PlaybackCacheKey.namespace("https://dav.example/music", ""))
    }

    @Test fun webdavRequiresScopeAndVersionedResource() {
        val dav = resolved.copy(source = "webdav", sourceId = "https://dav.example/music/song.flac", qualityRank = 0)
        val scope = PlaybackCacheKey.namespace("https://dav.example/music", "alice")
        assertNull(PlaybackCacheKey.of(dav))
        assertNull(PlaybackCacheKey.of(dav, namespaceHash = "not-a-hash"))
        val key = PlaybackCacheKey.of(dav, namespaceHash = scope)
        assertNotNull(key)
        assertNotEquals(key, PlaybackCacheKey.of(dav, namespaceHash = PlaybackCacheKey.namespace("https://dav.example/music", "bob")))
        assertNull(PlaybackCacheKey.of(dav.copy(sourceBytes = 0), namespaceHash = scope))
        assertNotNull(PlaybackCacheKey.of(dav.copy(sourceBytes = 0), namespaceHash = scope, resourceVersion = "etag-v1"))
    }

    @Test fun webdavQueryCredentialsAndStrmStayUnknown() {
        val scope = PlaybackCacheKey.namespace("https://dav.example/music", "alice")
        val dav = resolved.copy(source = "webdav", sourceId = "https://dav.example/music/song.flac?token=secret")
        assertNull(PlaybackCacheKey.of(dav, namespaceHash = scope))
        assertNull(PlaybackCacheKey.of(dav.copy(sourceId = "https://u:p@dav.example/music/song.flac"), namespaceHash = scope))
        assertNull(PlaybackCacheKey.of(dav.copy(sourceId = "/music/song.strm"), namespaceHash = scope))
        assertNotNull(PlaybackCacheKey.of(dav, namespaceHash = scope, resourceIdentity = "/actual/versioned.flac", resourceVersion = "etag-v1"))
        assertNull(PlaybackCacheKey.of(dav, namespaceHash = scope, resourceIdentity = "/actual/versioned.flac", resourceVersion = "?token=secret"))
    }

    @Test fun navidromeRequiresAccountScopeAndFingerprint() {
        val nd = resolved.copy(source = "navidrome", sourceId = "opaque-id")
        assertNull(PlaybackCacheKey.of(nd))
        assertNotNull(PlaybackCacheKey.of(nd, namespaceHash = "a".repeat(64)))
        assertNull(PlaybackCacheKey.of(nd.copy(sourceBytes = 0), namespaceHash = "a".repeat(64)))
    }
}
