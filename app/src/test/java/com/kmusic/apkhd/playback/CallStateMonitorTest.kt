package com.kmusic.apkhd.playback

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.telephony.TelephonyManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class CallStateMonitorTest {
    @Test fun disabledDoesNotRequirePhonePermission() {
        val statuses = mutableListOf<CallMonitorStatus>()
        val monitor = CallStateMonitor(ApplicationProvider.getApplicationContext(), {}, statuses::add)
        monitor.configure(false)
        assertEquals(CallMonitorStatus.DISABLED, statuses.last())
        monitor.close()
    }

    @Test fun noTelephonyCapabilityReportsUnavailable() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app.packageManager).setSystemFeature(PackageManager.FEATURE_TELEPHONY, false)
        val statuses = mutableListOf<CallMonitorStatus>()
        val monitor = CallStateMonitor(app, {}, statuses::add)
        monitor.configure(true)
        assertEquals(CallMonitorStatus.UNAVAILABLE, statuses.last())
        monitor.close()
    }

    @Test fun permissionDeniedReportsFallbackWithoutListening() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app.packageManager).setSystemFeature(PackageManager.FEATURE_TELEPHONY, true)
        shadowOf(app).denyPermissions(Manifest.permission.READ_PHONE_STATE)
        val statuses = mutableListOf<CallMonitorStatus>()
        val states = mutableListOf<CellularCallState>()
        val monitor = CallStateMonitor(app, states::add, statuses::add)
        monitor.configure(true)
        assertEquals(CallMonitorStatus.PERMISSION_REQUIRED, statuses.last())
        assertTrue(states.isEmpty())
        monitor.close()
    }

    @Test fun closedMonitorCannotReregisterOnPermissionRefresh() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app.packageManager).setSystemFeature(PackageManager.FEATURE_TELEPHONY, true)
        shadowOf(app).denyPermissions(Manifest.permission.READ_PHONE_STATE)
        val statuses = mutableListOf<CallMonitorStatus>()
        val monitor = CallStateMonitor(app, {}, statuses::add)
        monitor.configure(true)
        monitor.close()
        val count = statuses.size
        monitor.refresh()
        assertEquals(count, statuses.size)
    }

    @Test fun cellularStateMappingNeverUsesPhoneNumber() {
        assertEquals(CellularCallState.IDLE, CallStateMonitor.stateOf(TelephonyManager.CALL_STATE_IDLE))
        assertEquals(CellularCallState.RINGING, CallStateMonitor.stateOf(TelephonyManager.CALL_STATE_RINGING))
        assertEquals(CellularCallState.OFFHOOK, CallStateMonitor.stateOf(TelephonyManager.CALL_STATE_OFFHOOK))
        assertEquals(CellularCallState.UNKNOWN, CallStateMonitor.stateOf(-1))
    }

    @Test @Config(sdk = [26]) fun legacyPlatformPermissionDenialUsesSameHonestFallback() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app.packageManager).setSystemFeature(PackageManager.FEATURE_TELEPHONY, true)
        shadowOf(app).denyPermissions(Manifest.permission.READ_PHONE_STATE)
        val statuses = mutableListOf<CallMonitorStatus>()
        val monitor = CallStateMonitor(app, {}, statuses::add)
        monitor.configure(true)
        assertEquals(CallMonitorStatus.PERMISSION_REQUIRED, statuses.last())
        monitor.close()
    }
}
