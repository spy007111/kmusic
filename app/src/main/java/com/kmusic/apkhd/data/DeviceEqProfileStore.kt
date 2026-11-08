package com.kmusic.apkhd.data

import android.content.Context
import com.kmusic.apkhd.playback.DeviceEqProfilePolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Collections
import java.util.UUID

@Serializable
data class DeviceEqProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val bindingKey: String? = null,
    val eqEnabled: Boolean = false,
    val bandsMb: List<Int> = List(10) { 0 },
    val bass: Int = 0,
    val fxLoudness: Int = 0,
    val preGainDb: Float = 0f,
    val truePeakEnabled: Boolean = false,
    val ceilingDbTp: Float = -1f
) {
    fun defensiveCopy(): DeviceEqProfile = copy(bandsMb = Collections.unmodifiableList(ArrayList(bandsMb)))

    fun validated(): DeviceEqProfile {
        require(id.matches(Regex("[A-Za-z0-9_-]{1,80}"))) { "Invalid profile ID" }
        require(name.isNotBlank() && name.length <= 80 && name.none(Char::isISOControl)) { "Invalid profile name" }
        require(bindingKey == null || DeviceEqProfilePolicy.isValidBinding(bindingKey)) { "Invalid profile binding" }
        require(bandsMb.size == 10 && bandsMb.all { it in -1500..1500 }) { "Invalid EQ bands" }
        require(bass in 0..1000 && fxLoudness in 0..1500) { "Invalid effect strength" }
        require(preGainDb.isFinite() && preGainDb in -24f..0f) { "Invalid pre-gain" }
        require(ceilingDbTp.isFinite() && ceilingDbTp in -12f..0f) { "Invalid true-peak ceiling" }
        return defensiveCopy()
    }
}

/** Service-owned store; callers must share one instance per file. No route/permission reads here. */
class DeviceEqProfileStore(private val file: File) {
    constructor(context: Context) : this(File(context.applicationContext.filesDir, "device_eq_profiles.json"))

    enum class LoadStatus { NOT_LOADED, READY, UNKNOWN_SCHEMA, DAMAGED }

    @Serializable
    internal data class Document(val schemaVersion: Int = 1, val autoEnabled: Boolean = false, val profiles: List<DeviceEqProfile> = emptyList())

    private val mutex = Mutex()
    private val state = MutableStateFlow(Document())
    private val status = MutableStateFlow(LoadStatus.NOT_LOADED)
    val loadStatus: StateFlow<LoadStatus> = status.asStateFlow()
    val profiles: Flow<List<DeviceEqProfile>> = state.map { it.profiles.map(DeviceEqProfile::defensiveCopy) }.onStart { load() }
    val autoEnabled: Flow<Boolean> = state.map { it.autoEnabled }.onStart { load() }

    suspend fun load(): LoadStatus = mutex.withLock {
        withContext(Dispatchers.IO) { loadLocked() }
    }

    suspend fun all(): List<DeviceEqProfile> = mutex.withLock {
        withContext(Dispatchers.IO) { loadLocked(); state.value.profiles.map(DeviceEqProfile::defensiveCopy) }
    }

    suspend fun get(id: String): DeviceEqProfile? = all().singleOrNull { it.id == id }
    suspend fun getForBinding(bindingKey: String): DeviceEqProfile? = all().singleOrNull { it.bindingKey == bindingKey }

    /** Binding is transferred to this profile; older profiles remain available for manual selection. */
    suspend fun update(profile: DeviceEqProfile) {
        val safe = profile.validated()
        mutate { document ->
            val profiles = document.profiles.filterNot { it.id == safe.id }.map {
                if (safe.bindingKey != null && it.bindingKey == safe.bindingKey) it.copy(bindingKey = null).defensiveCopy() else it
            } + safe
            require(profiles.size <= MAX_PROFILES) { "Too many device profiles" }
            document.copy(profiles = profiles)
        }
    }

    /** Patch the latest saved profile while holding the same persistence mutex. */
    suspend fun updateExisting(id: String, transform: (DeviceEqProfile) -> DeviceEqProfile) {
        mutate { document ->
            val current = document.profiles.singleOrNull { it.id == id }
                ?: throw NoSuchElementException("Device profile no longer exists")
            val updated = transform(current.defensiveCopy()).validated()
            require(updated.id == id) { "Profile ID cannot change during patch" }
            document.copy(profiles = document.profiles.map { profile ->
                when {
                    profile.id == id -> updated
                    updated.bindingKey != null && profile.bindingKey == updated.bindingKey ->
                        profile.copy(bindingKey = null).defensiveCopy()
                    else -> profile
                }
            })
        }
    }

    suspend fun remove(id: String) = mutate { it.copy(profiles = it.profiles.filterNot { profile -> profile.id == id }) }
    suspend fun setAutoEnabled(enabled: Boolean) = mutate { it.copy(autoEnabled = enabled) }

    private suspend fun mutate(change: (Document) -> Document) = mutex.withLock {
        withContext(Dispatchers.IO) {
            check(loadLocked() == LoadStatus.READY) { "Profile file requires manual recovery before writing" }
            val next = change(state.value)
            val bytes = encode(next).toByteArray(Charsets.UTF_8)
            require(bytes.size <= MAX_FILE_BYTES) { "Profile file too large" }
            val parent = file.absoluteFile.parentFile
            check(parent.isDirectory || parent.mkdirs()) { "Cannot create profile directory" }
            val temporary = File(parent, ".${file.name}.${UUID.randomUUID()}.tmp")
            FileOutputStream(temporary).use { stream -> stream.write(bytes); stream.fd.sync() }
            // No non-atomic overwrite fallback: retain the old file and temp if the move fails.
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            state.value = next
        }
    }

    private fun loadLocked(): LoadStatus {
        if (status.value != LoadStatus.NOT_LOADED) return status.value
        if (!file.exists()) {
            status.value = LoadStatus.READY
            return status.value
        }
        try {
            require(file.length() <= MAX_FILE_BYTES) { "Profile file too large" }
            val text = file.readText(Charsets.UTF_8)
            val schema = json.parseToJsonElement(text).jsonObject["schemaVersion"]?.jsonPrimitive?.intOrNull
            if (schema != SCHEMA_VERSION) {
                preserveOriginal()
                status.value = LoadStatus.UNKNOWN_SCHEMA
                return status.value
            }
            state.value = decode(text)
            status.value = LoadStatus.READY
        } catch (_: Exception) {
            runCatching { preserveOriginal() }
            status.value = LoadStatus.DAMAGED
        }
        return status.value
    }

    private fun preserveOriginal() {
        val backup = File(file.absoluteFile.parentFile, "${file.name}.preserved.${UUID.randomUUID()}.bak")
        Files.copy(file.toPath(), backup.toPath())
    }

    companion object {
        const val SCHEMA_VERSION = 1
        private const val MAX_PROFILES = 128
        private const val MAX_FILE_BYTES = 1024L * 1024
        private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }

        internal fun encode(document: Document): String = json.encodeToString(Document.serializer(), document)
        internal fun decode(text: String): Document {
            val document = json.decodeFromString(Document.serializer(), text)
            require(document.schemaVersion == SCHEMA_VERSION)
            require(document.profiles.size <= MAX_PROFILES)
            require(document.profiles.map { it.id }.distinct().size == document.profiles.size)
            val profiles = document.profiles.map(DeviceEqProfile::validated)
            val bindings = profiles.mapNotNull { it.bindingKey }
            require(bindings.distinct().size == bindings.size)
            return document.copy(profiles = profiles)
        }
    }
}
