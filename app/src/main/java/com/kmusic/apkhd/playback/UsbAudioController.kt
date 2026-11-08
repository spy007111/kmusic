package com.kmusic.apkhd.playback

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioMixerAttributes
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executor

/** Single playback service publishes process-local evidence here; nothing is persisted. */
object UsbAudioRuntime {
    private val mutableState = MutableStateFlow(UsbOutputSnapshot())
    val state: StateFlow<UsbOutputSnapshot> = mutableState.asStateFlow()
    internal fun publish(snapshot: UsbOutputSnapshot) { mutableState.value = snapshot }
}

/** Platform seam keeps policy and lifecycle tests independent of a connected DAC. */
interface UsbMixerPlatform {
    val apiLevel: Int
    fun isConnected(route: OutputRoute): Boolean
    fun supported(route: OutputRoute): List<UsbMixerOption>
    fun request(route: OutputRoute, mixer: UsbMixerOption): Boolean
    fun preferred(route: OutputRoute): UsbMixerOption?
    fun clear(route: OutputRoute): Boolean
    fun observe(onPreferred: (OutputRoute, UsbMixerOption?) -> Unit, onTopology: () -> Unit)
    fun close()
}

class UsbAudioController(private val platform: UsbMixerPlatform) : AutoCloseable {
    constructor(context: Context, attributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()) :
        this(if (Build.VERSION.SDK_INT >= 34) AndroidUsbMixerPlatform(context, attributes) else UnsupportedUsbMixerPlatform())

    private data class Lease(val route: OutputRoute, val mixer: UsbMixerOption)
    private val mutableUpdates = MutableStateFlow(UsbOutputSnapshot())
    val updates: StateFlow<UsbOutputSnapshot> = mutableUpdates.asStateFlow()
    val snapshot: UsbOutputSnapshot get() = mutableUpdates.value
    private var enabled = false
    private var source: UsbSourceFormat? = null
    private var constraints = UsbStrictConstraints()
    private var route: OutputRoute? = null
    private var output: ApplicationAudioFormat? = null
    private var focused = false
    private var closed = false
    private var lease: Lease? = null
    private var clearPending = false
    private var rejected: Lease? = null
    private var rejectionReason = UsbOutputReason.PREFERENCE_REJECTED
    private var observationFailed = false

    init {
        if (platform.apiLevel >= 34) {
            observationFailed = runCatching { platform.observe(::onPreferredChanged, ::onTopologyChanged) }.isFailure
        }
    }

    @Synchronized fun configure(enabled: Boolean, source: UsbSourceFormat?, constraints: UsbStrictConstraints) {
        if (closed) return
        if (this.enabled != enabled || this.source != source || this.constraints != constraints) rejected = null
        if (this.source != source) {
            output = null
            clearLease()
        }
        this.enabled = enabled
        this.source = source
        this.constraints = constraints
        reconcile()
    }

    /** Only an AudioTrack's routedDevice is route evidence; connected-device enumeration is not. */
    @Synchronized fun onOutput(format: ApplicationAudioFormat?, route: OutputRoute?) {
        if (closed) return
        if (this.route != route || output != format) rejected = null
        this.route = route
        output = format
        reconcile()
    }

    /** May apply a preference before a replacement track, but never invents its current output format. */
    @Synchronized fun beforeOutputCreated(format: ApplicationAudioFormat) {
        if (closed) return
        output = null
        val pcm = source?.pcm
        if (enabled && focused && UsbOutputPolicy.sourceReason(source, constraints) == null && pcm != null &&
            (!format.isPcm || pcm.sampleRate != format.sampleRate || pcm.channelCount != format.channelCount ||
                pcm.encoding != format.encoding)) {
            rejected = lease
            rejectionReason = UsbOutputReason.OUTPUT_FORMAT_MISMATCH
            return stop(UsbOutputStatus.UNSATISFIED, UsbOutputReason.OUTPUT_FORMAT_MISMATCH)
        }
        reconcile()
    }

    /** Observation only: Media3 remains the sole audio-focus owner. */
    @Synchronized fun onFocus(hasFocus: Boolean) {
        if (closed) return
        if (focused != hasFocus) rejected = null
        focused = hasFocus
        if (!hasFocus) output = null
        reconcile()
    }

    private fun publish(status: UsbOutputStatus, reason: UsbOutputReason, confirmed: Boolean = false) {
        val requested = lease?.mixer
        val matches = requested != null && UsbOutputPolicy.outputMatches(source, requested, output)
        mutableUpdates.value = UsbOutputSnapshot(enabled, status, reason, source, route, output, requested, confirmed, matches)
        UsbAudioRuntime.publish(snapshot)
    }

    private fun clearLease(): Boolean {
        val previous = lease ?: return true
        clearPending = true
        val cleared = runCatching { platform.clear(previous.route) }.getOrDefault(false)
        if (cleared) {
            lease = null
            clearPending = false
        }
        return cleared
    }

    private fun stop(status: UsbOutputStatus, reason: UsbOutputReason) {
        if (clearLease()) publish(status, reason) else publish(UsbOutputStatus.UNSATISFIED, UsbOutputReason.CLEAR_FAILED)
    }

    private fun reconcile() {
        if (!enabled) return stop(UsbOutputStatus.DISABLED, UsbOutputReason.DISABLED)
        if (platform.apiLevel < 34) return stop(UsbOutputStatus.UNSUPPORTED, UsbOutputReason.API_TOO_OLD)
        if (!focused) return stop(UsbOutputStatus.UNSATISFIED, UsbOutputReason.FOCUS_SUSPENDED)
        val currentRoute = route
        if (currentRoute == null || !UsbOutputPolicy.isUsb(currentRoute.deviceType)) {
            return stop(UsbOutputStatus.NO_USB, UsbOutputReason.NO_USB)
        }
        val connected = runCatching { platform.isConnected(currentRoute) }.getOrNull()
            ?: return stop(UsbOutputStatus.UNSATISFIED, UsbOutputReason.PLATFORM_ERROR)
        if (!connected) {
            route = null
            output = null
            return stop(UsbOutputStatus.NO_USB, UsbOutputReason.NO_USB)
        }
        UsbOutputPolicy.sourceReason(source, constraints)?.let { return stop(UsbOutputStatus.UNSATISFIED, it) }
        val supported = runCatching { platform.supported(currentRoute) }.getOrNull()
            ?: return stop(UsbOutputStatus.UNSATISFIED, UsbOutputReason.PLATFORM_ERROR)
        val selection = UsbOutputPolicy.select(source, constraints, supported)
        val mixer = selection.option ?: return stop(
            if (selection.reason == UsbOutputReason.NO_BIT_PERFECT_MIXER) UsbOutputStatus.UNSUPPORTED else UsbOutputStatus.UNSATISFIED,
            selection.reason)
        val desired = Lease(currentRoute, mixer)
        if (clearPending || lease != null && lease != desired) {
            if (!clearLease()) return publish(UsbOutputStatus.UNSATISFIED, UsbOutputReason.CLEAR_FAILED)
        }
        if (rejected == desired) return publish(UsbOutputStatus.UNSATISFIED, rejectionReason)
        if (lease == null) {
            lease = desired
            val accepted = runCatching { platform.request(currentRoute, mixer) }.getOrDefault(false)
            if (!accepted) {
                rejected = desired
                rejectionReason = UsbOutputReason.PREFERENCE_REJECTED
                return stop(UsbOutputStatus.UNSATISFIED, UsbOutputReason.PREFERENCE_REJECTED)
            }
        }
        val preferred = runCatching { platform.preferred(currentRoute) }
        if (preferred.isFailure) return publish(UsbOutputStatus.REQUESTED, UsbOutputReason.PLATFORM_ERROR)
        verify(preferred.getOrNull() == mixer)
    }

    private fun verify(confirmed: Boolean) {
        val current = lease ?: return
        val matches = UsbOutputPolicy.outputMatches(source, current.mixer, output)
        when {
            !focused || !enabled || clearPending -> Unit
            !confirmed -> publish(UsbOutputStatus.REQUESTED, UsbOutputReason.PREFERENCE_UNCONFIRMED)
            observationFailed -> publish(UsbOutputStatus.REQUESTED, UsbOutputReason.PLATFORM_ERROR, true)
            output == null -> publish(UsbOutputStatus.REQUESTED, UsbOutputReason.OUTPUT_UNKNOWN, true)
            !matches -> {
                rejected = current
                rejectionReason = UsbOutputReason.OUTPUT_FORMAT_MISMATCH
                stop(UsbOutputStatus.UNSATISFIED, UsbOutputReason.OUTPUT_FORMAT_MISMATCH)
            }
            else -> publish(UsbOutputStatus.CONFIGURATION_MATCHED, UsbOutputReason.CONFIGURATION_MATCHED, true)
        }
    }

    @Synchronized private fun onPreferredChanged(changedRoute: OutputRoute, mixer: UsbMixerOption?) {
        if (closed || clearPending) return
        val current = lease ?: return
        if (current.route.sameSessionDevice(changedRoute)) {
            val actual = runCatching { platform.preferred(current.route) }
            verify(actual.isSuccess && actual.getOrNull() == mixer && mixer == current.mixer)
        }
    }

    @Synchronized private fun onTopologyChanged() {
        if (closed) return
        route?.let { current ->
            if (!runCatching { platform.isConnected(current) }.getOrDefault(false)) {
                route = null
                output = null
            }
        }
        reconcile()
    }

    @Synchronized override fun close() {
        if (closed) return
        closed = true
        val cleared = clearLease()
        runCatching { platform.close() }
        publish(if (cleared) UsbOutputStatus.DISABLED else UsbOutputStatus.UNSATISFIED,
            if (cleared) UsbOutputReason.CLOSED else UsbOutputReason.CLEAR_FAILED)
    }
}

private class UnsupportedUsbMixerPlatform : UsbMixerPlatform {
    override val apiLevel: Int = Build.VERSION.SDK_INT
    override fun isConnected(route: OutputRoute) = false
    override fun supported(route: OutputRoute) = emptyList<UsbMixerOption>()
    override fun request(route: OutputRoute, mixer: UsbMixerOption) = false
    override fun preferred(route: OutputRoute): UsbMixerOption? = null
    override fun clear(route: OutputRoute) = true
    override fun observe(onPreferred: (OutputRoute, UsbMixerOption?) -> Unit, onTopology: () -> Unit) = Unit
    override fun close() = Unit
}

@RequiresApi(34)
private class AndroidUsbMixerPlatform(context: Context, private val attributes: AudioAttributes) : UsbMixerPlatform {
    override val apiLevel: Int = Build.VERSION.SDK_INT
    private val manager = requireNotNull(context.applicationContext.getSystemService(AudioManager::class.java))
    private val handler = Handler(Looper.getMainLooper())
    private var mixerListener: AudioManager.OnPreferredMixerAttributesChangedListener? = null
    private var deviceCallback: AudioDeviceCallback? = null
    private val requestedDevices = mutableMapOf<Int, AudioDeviceInfo>()

    private fun device(route: OutputRoute): AudioDeviceInfo? = manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        .firstOrNull { it.id == route.deviceId && it.type == route.deviceType && it.isSink }

    override fun isConnected(route: OutputRoute): Boolean = device(route) != null

    override fun supported(route: OutputRoute): List<UsbMixerOption> = device(route)?.let {
        manager.getSupportedMixerAttributes(it).map(::option)
    }.orEmpty()

    override fun request(route: OutputRoute, mixer: UsbMixerOption): Boolean {
        val device = device(route) ?: return false
        // Use the exact public descriptor returned by the platform, never synthesize a DAC format.
        val supported = manager.getSupportedMixerAttributes(device).firstOrNull { option(it) == mixer } ?: return false
        requestedDevices[device.id] = device
        return manager.setPreferredMixerAttributes(attributes, device, supported)
    }

    override fun preferred(route: OutputRoute): UsbMixerOption? = device(route)?.let {
        manager.getPreferredMixerAttributes(attributes, it)?.let(::option)
    }

    override fun clear(route: OutputRoute): Boolean {
        val device = requestedDevices[route.deviceId] ?: device(route) ?: return true
        val cleared = manager.clearPreferredMixerAttributes(attributes, device) ||
            manager.getPreferredMixerAttributes(attributes, device) == null
        if (cleared) requestedDevices.remove(route.deviceId)
        return cleared
    }

    override fun observe(onPreferred: (OutputRoute, UsbMixerOption?) -> Unit, onTopology: () -> Unit) {
        val listener = AudioManager.OnPreferredMixerAttributesChangedListener { changedAttributes, device, mixer ->
            if (changedAttributes == attributes) onPreferred(
                OutputRoute(device.id, device.type, "USB音频"), mixer?.let(::option))
        }
        mixerListener = listener
        manager.addOnPreferredMixerAttributesChangedListener(Executor { handler.post(it) }, listener)
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) { onTopology() }
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) { onTopology() }
        }
        deviceCallback = callback
        manager.registerAudioDeviceCallback(callback, handler)
    }

    override fun close() {
        mixerListener?.let { runCatching { manager.removeOnPreferredMixerAttributesChangedListener(it) } }
        deviceCallback?.let { runCatching { manager.unregisterAudioDeviceCallback(it) } }
        mixerListener = null
        deviceCallback = null
        handler.removeCallbacksAndMessages(null)
    }

    private fun option(mixer: AudioMixerAttributes): UsbMixerOption {
        val format = mixer.format
        return UsbMixerOption(UsbPcmFormat(format.sampleRate, format.channelCount, format.encoding, format.channelMask),
            mixer.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT)
    }
}
