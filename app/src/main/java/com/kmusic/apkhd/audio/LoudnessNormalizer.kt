package com.kmusic.apkhd.audio

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.kmusic.apkhd.data.LoudnessEntry
import com.kmusic.apkhd.data.LoudnessStore
import com.kmusic.apkhd.model.Track
import java.io.File
import kotlin.math.log10
import kotlin.math.min
import kotlin.math.pow

/** 标签优先、扫描缓存次之；首期通过 Media3.volume 归一，仅允许衰减。 */
object LoudnessNormalizer {
    const val TARGET_LUFS = -14.0
    private const val MIN_GAIN_DB = -24f

    data class Applied(
        val gainDb: Float,
        val lufs: Double?,
        /** tag / cache / r128 / none / off / length-unsupported */
        val source: String,
        /** album 仅表示专辑标签；未聚合扫描专辑时明确 track-fallback。 */
        val basis: String = "none"
    ) {
        val volume: Float get() = toLinear(gainDb)
    }

    data class TagSelection(val gainDb: Float, val peak: Float?, val basis: String)

    fun selectTag(mode: String, tag: TagReader.Loudness?): TagSelection? {
        if (tag == null) return null
        val album = tag.albumGainDb?.takeIf { it.isFinite() }
        val track = tag.trackGainDb?.takeIf { it.isFinite() }
        if (mode == "album" && album != null) {
            // TagReader 尚无 albumPeak，不能拿 trackPeak 当专辑峰值。
            return TagSelection(album, null, "album")
        }
        if (track != null) return TagSelection(track, tag.trackPeak, scanBasis(mode))
        return album?.let { TagSelection(it, null, "album") }
    }

    fun scanBasis(mode: String): String = if (mode == "album") "track-fallback" else "track"

    suspend fun resolve(
        context: Context,
        track: Track,
        uri: Uri,
        mode: String,
        store: LoudnessStore
    ): Applied {
        if (mode == "off") return Applied(0f, null, "off")
        if (track.source != "local") return Applied(0f, null, "none")

        selectTag(mode, TagReader.readReplayGain(context, uri))?.let {
            return Applied(clampGain(it.gainDb, it.peak), null, "tag", it.basis)
        }

        if (!R128Analyzer.canScanDuration(track.durationMs)) {
            return Applied(0f, null, "length-unsupported")
        }

        val stamp = fingerprint(context, uri)
        val cached = store.get(track.id)
        val basis = scanBasis(mode)
        if (cached?.matchesScan(R128.ALGORITHM_VERSION, stamp.first, stamp.second) == true) {
            return Applied(clampGain((TARGET_LUFS - cached.lufs).toFloat(), cached.peak), cached.lufs, "cache", basis)
        }

        val result = R128Analyzer.analyze(context, uri) ?: return Applied(0f, null, "none")
        // 分析途中文件被替换则不能把测量归到新的指纹。
        if (fingerprint(context, uri) != stamp) return Applied(0f, null, "none")
        store.put(LoudnessEntry(
            id = track.id,
            lufs = result.lufs,
            peak = result.peak,
            size = stamp.first,
            mtime = stamp.second,
            algorithmVersion = R128.ALGORITHM_VERSION
        ))
        return Applied(clampGain((TARGET_LUFS - result.lufs).toFloat(), result.peak), result.lufs, "r128", basis)
    }

    private fun fingerprint(context: Context, uri: Uri): Pair<Long, Long> {
        if (uri.scheme == "file") {
            val file = File(uri.path ?: return 0L to 0L)
            return file.length() to file.lastModified()
        }
        return runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (!c.moveToFirst()) return@use 0L to 0L
                fun value(name: String): Long {
                    val i = c.getColumnIndex(name)
                    return if (i >= 0 && !c.isNull(i)) c.getLong(i).coerceAtLeast(0L) else 0L
                }
                val size = value(OpenableColumns.SIZE)
                // DocumentsProvider 是 ms；MediaStore date_modified 是 Unix seconds。
                val mtime = value("last_modified").takeIf { it > 0 } ?: (value("date_modified") * 1000L)
                size to mtime
            } ?: (0L to 0L)
        }.getOrDefault(0L to 0L)
    }

    /** 输出为实际施加的 dB，任何标签/峰值均不能允许正增益。 */
    fun clampGain(rawGainDb: Float, peak: Float?): Float {
        if (!rawGainDb.isFinite()) return 0f
        val ceiling = if (peak != null && peak.isFinite() && peak > 0f) {
            min(0f, (20.0 * log10(0.98f.toDouble() / peak)).toFloat())
        } else 0f
        return min(rawGainDb.coerceAtLeast(MIN_GAIN_DB), ceiling)
    }

    /** 与 Media3 的 0..1 volume 范围一致，包括异常值防御。 */
    fun toLinear(gainDb: Float): Float =
        if (gainDb.isNaN()) 1f else 10.0.pow(gainDb.coerceAtMost(0f) / 20.0).toFloat().coerceIn(0f, 1f)
}
