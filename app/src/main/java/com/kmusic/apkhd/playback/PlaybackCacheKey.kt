package com.kmusic.apkhd.playback

import com.kmusic.apkhd.model.Track
import java.net.URI
import java.security.MessageDigest
import java.util.Locale

/** Content identity only; signing URLs, titles and decryption keys are never hashed. */
object PlaybackCacheKey {
    private val opaqueId = Regex("[A-Za-z0-9._:-]+")
    private val namespace = Regex("[a-fA-F0-9]{32,64}")
    private val formats = setOf("mp3", "flac", "mflac", "mgg", "ogg", "opus", "aac", "m4a", "mp4", "wav", "aiff", "aif", "alac", "dsf", "dff")

    /**
     * Call only with the resolved Track (actual source/rank, not the requested tier).
     * Private-server namespaces must be hashes of server identity + a non-secret account ID,
     * computed by the owner. A STRM requires an explicit resolved resource ID/version.
     */
    fun of(
        track: Track,
        namespaceHash: String? = null,
        container: String? = null,
        codec: String? = null,
        resourceIdentity: String? = null,
        resourceVersion: String? = null
    ): String? {
        val source = track.source.trim().lowercase(Locale.ROOT)
        val privateSource = source == "webdav" || source == "navidrome"
        if (source !in setOf("kuwo", "netease", "migu", "webdav", "navidrome")) return null
        val ns = if (privateSource) namespaceHash?.takeIf { namespace.matches(it) }?.lowercase(Locale.ROOT) ?: return null else ""
        val identity = when (source) {
            "webdav" -> davIdentity(resourceIdentity ?: track.sourceId) ?: return null
            else -> (resourceIdentity ?: track.sourceId).trim().takeIf { it.isNotEmpty() && opaqueId.matches(it) } ?: return null
        }
        if (source == "kuwo" && !identity.all(Char::isDigit)) return null
        if (source == "netease") {
            val id = identity.removePrefix("ne:")
            if (id.isEmpty() || !id.all(Char::isDigit)) return null
        }
        val bytes = track.sourceBytes.coerceAtLeast(0)
        val duration = track.durationMs.coerceAtLeast(0)
        val version = resourceVersion?.trim()?.takeIf { it.isNotEmpty() }
        if (version != null && (version.contains('?') || version.contains("://") || version.contains('@'))) return null
        if (privateSource) {
            // Directory listings alone do not prove which bytes a STRM/redirect represents.
            if (bytes == 0L && version == null) return null
            if (source == "webdav" && identity.substringBefore('?').endsWith(".strm", ignoreCase = true)) return null
        } else {
            if (track.qualityRank <= 0 || (bytes == 0L && duration == 0L)) return null
        }
        val format = container?.trim()?.lowercase(Locale.ROOT)?.takeIf { it in formats }
            ?: containerFromUrl(track.playUrl)
        val realCodec = codec?.trim()?.lowercase(Locale.ROOT)?.takeIf { opaqueId.matches(it) }.orEmpty()
        val parts = listOf(source, ns, identity, track.qualityRank.toString(), track.qualityLabel.trim(),
            track.isMaster.toString(), format.orEmpty(), realCodec, track.sourceKbps.coerceAtLeast(0).toString(),
            bytes.toString(), duration.toString(), version.orEmpty())
        val canonical = parts.joinToString("") { "${it.length}:$it" }
        return "kmusic:v2:" + digest(canonical)
    }

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    /** Hash only normalized server coordinates and a non-secret account identity. */
    fun namespace(server: String, account: String): String? = runCatching {
        if (account.isBlank()) return null
        val uri = URI(server.trim())
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return null
        val host = uri.host?.lowercase(Locale.ROOT) ?: return null
        if (scheme !in setOf("http", "https") || uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null) return null
        val port = uri.port.takeUnless { it == -1 || scheme == "https" && it == 443 || scheme == "http" && it == 80 }
        val path = uri.normalize().rawPath.orEmpty().trimEnd('/')
        val coordinates = "$scheme://$host${port?.let { ":$it" }.orEmpty()}$path"
        digest("${coordinates.length}:$coordinates${account.length}:$account")
    }.getOrNull()

    fun containerFromUrl(url: String?): String? = runCatching {
        URI(url ?: return null).rawPath?.substringAfterLast('.')?.lowercase(Locale.ROOT)?.takeIf { it in formats }
    }.getOrNull()

    private fun davIdentity(value: String): String? {
        if (value.isBlank() || '?' in value || '#' in value || '@' in value || '\\' in value) return null
        return runCatching {
            val uri = URI(value)
            if (uri.rawQuery != null || uri.rawFragment != null || uri.rawUserInfo != null) return null
            if (uri.isAbsolute) {
                if (uri.scheme.lowercase(Locale.ROOT) !in setOf("http", "https") || uri.host.isNullOrBlank()) return null
                val path = uri.rawPath?.takeIf { it.startsWith('/') && it != "/" } ?: return null
                if (uri.path.endsWith(".strm", ignoreCase = true)) return null
                val scheme = uri.scheme.lowercase(Locale.ROOT)
                val port = uri.port.takeUnless { it == -1 || scheme == "https" && it == 443 || scheme == "http" && it == 80 }
                "$scheme://${uri.host.lowercase(Locale.ROOT)}${port?.let { ":$it" }.orEmpty()}$path"
            } else value.takeIf { it.startsWith('/') && it != "/" && !it.startsWith("//") && !uri.path.endsWith(".strm", ignoreCase = true) }
        }.getOrNull()
    }
}
