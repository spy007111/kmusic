package com.kmusic.apkhd.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 响度分析结果缓存。
 *
 * 为什么要落盘：算一首歌要解码整首（2~4 秒），每次起播都重算不可接受。
 * 用文件而不是 DataStore：条目会越攒越多，DataStore 的 Preferences 是整块读写的，
 * 几百条以后每次写入都变慢。
 *
 * 失效判定看文件大小 + 修改时间：换歌不换 id 的情况（重新下载同一首）也能识别。
 */
@Serializable
data class LoudnessEntry(
    val id: String,
    val lufs: Double,
    val peak: Float,
    val size: Long = 0,
    val mtime: Long = 0,
    /** 来自标签的增益（dB）。有标签就不必自己分析 */
    val tagGainDb: Float? = null,
    val tagPeak: Float? = null,
    /** 旧 JSON 未带版本时为 0，不能复用旧的声道平均算法。仅版本化扫描，不删除标签。 */
    val algorithmVersion: Int = 0
) {
    fun matchesScan(version: Int, currentSize: Long, currentMtime: Long): Boolean =
        algorithmVersion == version && lufs.isFinite() && peak.isFinite() &&
            (currentSize > 0 || currentMtime > 0) &&
            (currentSize <= 0 || size == currentSize) &&
            (currentMtime <= 0 || mtime == currentMtime)
}

class LoudnessStore(private val context: Context) {

    private val file = File(context.filesDir, "loudness.json")
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Volatile
    private var cache: MutableMap<String, LoudnessEntry>? = null

    private suspend fun load(): MutableMap<String, LoudnessEntry> {
        cache?.let { return it }
        return mutex.withLock {
            cache ?: withContext(Dispatchers.IO) {
                val map = runCatching {
                    val text = file.readText()
                    json.decodeFromString(ListSerializer(LoudnessEntry.serializer()), text)
                        .associateBy { it.id }.toMutableMap()
                }.getOrElse { mutableMapOf() }
                cache = map
                map
            }
        }
    }

    suspend fun get(id: String): LoudnessEntry? {
        val map = load()
        return mutex.withLock { map[id] }
    }

    suspend fun all(): Map<String, LoudnessEntry> {
        val map = load()
        return mutex.withLock { map.toMap() }
    }

    suspend fun put(entry: LoudnessEntry) {
        val map = load()
        mutex.withLock {
            val old = map[entry.id]
            map[entry.id] = entry.copy(
                tagGainDb = entry.tagGainDb ?: old?.tagGainDb,
                tagPeak = entry.tagPeak ?: old?.tagPeak
            )
            persist(map)
        }
    }

    suspend fun remove(id: String) {
        val map = load()
        mutex.withLock {
            map.remove(id)
            persist(map)
        }
    }

    suspend fun clear() {
        val map = load()
        mutex.withLock {
            map.clear()
            persist(map)
        }
    }

    private fun persist(map: MutableMap<String, LoudnessEntry>) {
        runCatching {
            val tmp = File(file.parentFile, "loudness.json.tmp")
            tmp.writeText(json.encodeToString(ListSerializer(LoudnessEntry.serializer()), map.values.toList()))
            if (!tmp.renameTo(file)) {
                file.writeText(tmp.readText())
                tmp.delete()
            }
        }
    }
}
