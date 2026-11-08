package com.kmusic.apkhd.playback

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.kmusic.apkhd.data.DeviceEqProfile
import com.kmusic.apkhd.data.DeviceEqProfileStore
import com.kmusic.apkhd.data.SettingsStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class DeviceEqProfileManagerTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var context: Context
    private lateinit var settings: SettingsStore
    private lateinit var store: DeviceEqProfileStore
    private var manager: DeviceEqProfileManager? = null
    private var previousStore: Any? = null
    private lateinit var previousEffects: DeviceEqProfile
    private var previousPreset = 0
    private val manual = DeviceEqProfile(id = "manual", name = "Manual fixture", eqEnabled = false,
        bandsMb = List(10) { -400 }, bass = 900, fxLoudness = 800, preGainDb = -6f,
        truePeakEnabled = false, ceilingDbTp = -1f)

    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        settings = SettingsStore(context)
        previousPreset = FxChain.presetIndex
        previousEffects = snapshotEffects()
        val shared = DeviceEqProfiles::class.java.getDeclaredField("shared").apply { isAccessible = true }
        previousStore = shared.get(DeviceEqProfiles)
        store = DeviceEqProfileStore(File(temporary.root, "manager-profiles.json"))
        shared.set(DeviceEqProfiles, store)
        DeviceEqProfiles.clearManual()
        DeviceEqProfiles.runtime.value = DeviceEqProfiles.ProfileRuntime()
        AudioRuntimeState.serviceStarted(false)
        AudioRuntimeState.reportOutput(null, null)
        runBlocking {
            store.setAutoEnabled(false)
            store.update(manual)
            setGlobal(true, 2, 200, 300, true, -3f)
        }
        FxChain.configure(false, 0)
        FxChain.applyCurve(IntArray(10))
        FxChain.configureEnhanced(0, 0)
        FxChain.configureDevicePreGain(0f)
        FxChain.configureTruePeak(false, -1f)
    }

    @After fun cleanup() {
        manager?.close()
        manager = null
        DeviceEqProfiles.clearManual()
        DeviceEqProfiles.runtime.value = DeviceEqProfiles.ProfileRuntime()
        AudioRuntimeState.serviceStopped()
        runBlocking { setGlobal(false, 0, 0, 0, false, -1f) }
        FxChain.configure(previousEffects.eqEnabled, previousPreset)
        FxChain.applyCurve(previousEffects.bandsMb.toIntArray())
        FxChain.configureEnhanced(previousEffects.bass, previousEffects.fxLoudness)
        FxChain.configureDevicePreGain(previousEffects.preGainDb)
        FxChain.configureTruePeak(previousEffects.truePeakEnabled, previousEffects.ceilingDbTp)
        DeviceEqProfiles::class.java.getDeclaredField("shared").apply { isAccessible = true }
            .set(DeviceEqProfiles, previousStore)
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun initiallySelectedManualProfileRestoresPersistedGlobalNotUninitializedFxDefaults() {
        DeviceEqProfiles.selectManual(manual.id)
        startManager()
        pumpUntil("initial manual applied") { DeviceEqProfiles.runtime.value.active?.id == manual.id }
        assertEffects(manual)
        DeviceEqProfiles.clearManual()
        pumpUntil("global restored") { DeviceEqProfiles.runtime.value.active == null && FxChain.bassStrength == 200 }
        assertTrue(FxChain.enabled)
        assertEquals(300, FxChain.loudnessGain)
        assertTrue(FxChain.truePeakEnabled)
        assertEquals(-3f, FxChain.truePeakCeilingDb, 0f)
        assertEquals(0f, FxChain.devicePreGainDb, 0f)
        assertArrayEquals("Persistent EQ preset must not restore the pre-load zero curve", EqPresets.gainsOf(2), FxChain.bandLevels())
    }

    @Test fun globalSettingsChangedDuringManualProfileAreLatestWhenManualClears() {
        startManager()
        pumpUntil("global settings applied") { FxChain.enabled && FxChain.bassStrength == 200 }
        DeviceEqProfiles.selectManual(manual.id)
        pumpUntil("manual profile applied") { DeviceEqProfiles.runtime.value.active?.id == manual.id }
        runBlocking { setGlobal(true, 3, 450, 650, true, -4f) }
        pumpUntil("latest global collected behind profile") {
            latestGlobal()?.let { it.bass == 450 && it.fxLoudness == 650 && it.ceilingDbTp == -4f } == true
        }
        assertEffects(manual)
        DeviceEqProfiles.clearManual()
        pumpUntil("latest global restored") { DeviceEqProfiles.runtime.value.active == null && FxChain.bassStrength == 450 }
        assertTrue(FxChain.enabled)
        assertEquals(650, FxChain.loudnessGain)
        assertTrue(FxChain.truePeakEnabled)
        assertEquals(-4f, FxChain.truePeakCeilingDb, 0f)
        assertArrayEquals(EqPresets.gainsOf(3), FxChain.bandLevels())
    }

    @Test fun closeRestoresCurrentGlobalStrengthsAndPreservesSessionCustomCurve() {
        startManager()
        pumpUntil("global settings applied") { FxChain.enabled && FxChain.bassStrength == 200 }
        val editedGlobal = IntArray(10) { it * 100 - 450 }
        FxChain.applyCurve(editedGlobal)
        DeviceEqProfiles.selectManual(manual.id)
        pumpUntil("manual profile applied") { DeviceEqProfiles.runtime.value.active?.id == manual.id }
        runBlocking { settings.setFxBass(500); settings.setTruePeakCeilingDb(-5f) }
        pumpUntil("latest global collected behind profile") {
            latestGlobal()?.let { it.bass == 500 && it.ceilingDbTp == -5f } == true
        }
        manager!!.close()
        manager = null
        assertNull(DeviceEqProfiles.runtime.value.active)
        assertTrue(FxChain.enabled)
        assertEquals(500, FxChain.bassStrength)
        assertEquals(300, FxChain.loudnessGain)
        assertTrue(FxChain.truePeakEnabled)
        assertEquals(-5f, FxChain.truePeakCeilingDb, 0f)
        assertEquals(0f, FxChain.devicePreGainDb, 0f)
        assertArrayEquals(editedGlobal, FxChain.bandLevels())
    }

    @Test fun clearingManualWithoutNewSettingsEmissionPreservesSessionCurve() {
        startManager()
        pumpUntil("global settings applied") { FxChain.enabled && FxChain.bassStrength == 200 }
        val editedGlobal = IntArray(10) { it * 100 - 450 }
        FxChain.applyCurve(editedGlobal)
        DeviceEqProfiles.selectManual(manual.id)
        pumpUntil("manual profile applied") { DeviceEqProfiles.runtime.value.active?.id == manual.id }
        DeviceEqProfiles.clearManual()
        pumpUntil("global restored immediately") { DeviceEqProfiles.runtime.value.active == null }
        assertArrayEquals(editedGlobal, FxChain.bandLevels())
    }

    private fun startManager() { manager = DeviceEqProfileManager(context).also { it.start() } }
    private suspend fun setGlobal(enabled: Boolean, preset: Int, bass: Int, loudness: Int, peak: Boolean, ceiling: Float) {
        settings.setEqEnabled(enabled)
        settings.setEqPreset(preset)
        settings.setFxBass(bass)
        settings.setFxLoudness(loudness)
        settings.setTruePeakEnabled(peak)
        settings.setTruePeakCeilingDb(ceiling)
    }
    private fun latestGlobal(): DeviceEqProfile? = manager?.let {
        DeviceEqProfileManager::class.java.getDeclaredField("latestGlobal").apply { isAccessible = true }.get(it) as? DeviceEqProfile
    }
    private fun snapshotEffects() = DeviceEqProfile(id = "effects", name = "Fixture baseline", eqEnabled = FxChain.enabled,
        bandsMb = FxChain.bandLevels().toList(), bass = FxChain.bassStrength, fxLoudness = FxChain.loudnessGain,
        preGainDb = FxChain.devicePreGainDb, truePeakEnabled = FxChain.truePeakEnabled, ceilingDbTp = FxChain.truePeakCeilingDb)
    private fun assertEffects(profile: DeviceEqProfile) {
        assertEquals(profile.eqEnabled, FxChain.enabled)
        assertArrayEquals(profile.bandsMb.toIntArray(), FxChain.bandLevels())
        assertEquals(profile.bass, FxChain.bassStrength)
        assertEquals(profile.fxLoudness, FxChain.loudnessGain)
        assertEquals(profile.preGainDb, FxChain.devicePreGainDb, 0f)
        assertEquals(profile.truePeakEnabled, FxChain.truePeakEnabled)
        assertEquals(profile.ceilingDbTp, FxChain.truePeakCeilingDb, 0f)
    }
    private fun pumpUntil(description: String, predicate: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (predicate()) return
            Thread.sleep(5)
        }
        fail("Timed out waiting for $description")
    }
}
