package com.kmusic.apkhd.playback

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.kmusic.apkhd.data.DeviceEqProfile
import com.kmusic.apkhd.data.DeviceEqProfileStore
import com.kmusic.apkhd.data.SettingsStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

object DeviceEqProfiles {
    @Volatile private var shared: DeviceEqProfileStore? = null
    @Synchronized fun store(context: Context): DeviceEqProfileStore = shared ?: DeviceEqProfileStore(context).also { shared = it }
    val runtime = MutableStateFlow(ProfileRuntime())
    private val manualUpdates = MutableStateFlow<String?>(null)
    fun selectManual(id: String?) { manualUpdates.value = id }
    fun clearManual() { selectManual(null) }
    internal fun manualFlow(): StateFlow<String?> = manualUpdates

    data class ProfileRuntime(
        val active: DeviceEqProfile? = null,
        val routeLabel: String = "尚未确认输出路由",
        val bindingKey: String? = null,
        val categoryBindingKey: String? = null,
        val perDeviceIdentity: Boolean = false,
        val bypassed: Boolean = false
    )

    fun identify(context: Context, route: OutputRoute?): ProfileRuntime {
        if (route == null) return ProfileRuntime()
        val category = when (route.deviceType) {
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> DeviceEqProfilePolicy.Category.SPEAKER
            AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> DeviceEqProfilePolicy.Category.WIRED
            AudioDeviceInfo.TYPE_HDMI, AudioDeviceInfo.TYPE_HDMI_ARC, AudioDeviceInfo.TYPE_HDMI_EARC -> DeviceEqProfilePolicy.Category.HDMI
            AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_ACCESSORY -> DeviceEqProfilePolicy.Category.USB
            else -> if (route.isBluetooth) DeviceEqProfilePolicy.Category.BLUETOOTH else DeviceEqProfilePolicy.Category.UNKNOWN
        }
        val categoryKey = DeviceEqProfilePolicy.bindingKey(category)
        val authorized = Build.VERSION.SDK_INT >= 31 && ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        val identity = if (category == DeviceEqProfilePolicy.Category.BLUETOOTH && authorized) runCatching {
            context.getSystemService(AudioManager::class.java)?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                ?.singleOrNull { it.id == route.deviceId && it.type == route.deviceType }?.address
                ?.takeIf { it.matches(Regex("(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}")) && it != "02:00:00:00:00:00" }
        }.getOrNull() else null
        val deviceKey = identity?.let { DeviceEqProfilePolicy.bindingKey(category, it, true) }
        return ProfileRuntime(routeLabel = route.deviceTypeName, bindingKey = deviceKey ?: categoryKey,
            categoryBindingKey = categoryKey, perDeviceIdentity = deviceKey != null)
    }
}

/** The service owns automatic profile application even after the UI is closed. */
class DeviceEqProfileManager(private val context: Context) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val settings = SettingsStore(context)
    private var latestGlobal: DeviceEqProfile? = null
    private var lastRoute: Pair<Int, Int>? = null
    private var lastApplied: DeviceEqProfile? = null
    private var globalCurve: Pair<Int, List<Int>>? = null
    private var globalPresetIndex = 0

    fun start() {
        val globalEq = combine(settings.eqEnabled, settings.eqPreset, settings.fxBass, settings.fxLoudness) { enabled, preset, bass, loud ->
            globalPresetIndex = preset
            val savedCurve = globalCurve?.takeIf { it.first == preset }?.second
            DeviceEqProfile(id = "global", name = "全局音效", eqEnabled = enabled,
                bandsMb = savedCurve ?: EqPresets.gainsOf(preset).toList(), bass = bass, fxLoudness = loud)
        }
        val global = combine(globalEq, settings.truePeakEnabled, settings.truePeakCeilingDb) { eq, peak, ceiling ->
            eq.copy(truePeakEnabled = peak, ceilingDbTp = ceiling)
        }
        scope.launch {
            combine(DeviceEqProfiles.store(context).profiles, DeviceEqProfiles.store(context).autoEnabled,
                AudioRuntimeState.updates, DeviceEqProfiles.manualFlow(), global) { profiles, auto, audio, manual, defaults ->
                latestGlobal = globalCurve?.takeIf { it.first == globalPresetIndex }?.let { defaults.copy(bandsMb = it.second) } ?: defaults
                val routeId = audio.route?.let { it.deviceId to it.deviceType }
                val decision = DeviceProfileTransition.resolve(lastRoute, routeId, manual)
                lastRoute = decision.rememberedRoute
                if (decision.clearManual) DeviceEqProfiles.clearManual()
                val evidence = DeviceEqProfiles.identify(context, audio.route)
                val category = evidence.categoryBindingKey?.substringAfterLast(':')?.let { key ->
                    DeviceEqProfilePolicy.Category.entries.firstOrNull { it.key == key }
                } ?: DeviceEqProfilePolicy.Category.UNKNOWN
                val selected = profiles.firstOrNull { it.id == decision.manualId }
                    ?: DeviceEqProfilePolicy.select(profiles, DeviceEqProfilePolicy.Route(category, audio.route != null,
                        evidence.bindingKey.takeIf { evidence.perDeviceIdentity }), auto)
                Triple(selected, evidence, audio)
            }.collect { (selected, evidence, audio) ->
                val effective = selected ?: requireNotNull(latestGlobal)
                if (effective != lastApplied) {
                    if (selected != null && lastApplied?.id == "global") {
                        // Preserve a current manually edited global curve; persistent defaults remain authoritative for switches/strengths.
                        globalCurve = globalPresetIndex to FxChain.bandLevels().toList()
                        latestGlobal = latestGlobal?.copy(bandsMb = globalCurve!!.second)
                    }
                    apply(effective)
                    lastApplied = effective
                }
                DeviceEqProfiles.runtime.value = evidence.copy(active = selected,
                    bypassed = audio.dspBypassReason != null)
            }
        }
    }

    private fun apply(profile: DeviceEqProfile) {
        FxChain.applyCurve(profile.bandsMb.toIntArray())
        FxChain.configure(profile.eqEnabled, FxChain.presetIndex)
        FxChain.configureEnhanced(profile.bass, profile.fxLoudness)
        FxChain.configureDevicePreGain(profile.preGainDb)
        FxChain.configureTruePeak(profile.truePeakEnabled, profile.ceilingDbTp)
    }

    override fun close() {
        scope.cancel()
        latestGlobal?.let(::apply)
        latestGlobal = null
        lastApplied = null
        DeviceEqProfiles.runtime.value = DeviceEqProfiles.ProfileRuntime()
    }
}
