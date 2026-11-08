package com.kmusic.apkhd.ui

import org.junit.Assert.*
import org.junit.Test

class FirstRunPermissionsTest {
    private val audio = FirstRunPermissionPlan.READ_MEDIA_AUDIO
    private val phone = FirstRunPermissionPlan.READ_PHONE_STATE
    private val notifications = FirstRunPermissionPlan.POST_NOTIFICATIONS

    @Test fun androidThirtyThreePhoneRequestsAudioThenPhoneThenNotifications() {
        assertEquals(listOf(audio, phone, notifications), FirstRunPermissionPlan.select(33, false, true, emptySet()))
    }

    @Test fun androidThirtyTwoUsesLegacyAudioAndNoNotificationRequest() {
        assertEquals(listOf(FirstRunPermissionPlan.READ_EXTERNAL_STORAGE, phone),
            FirstRunPermissionPlan.select(32, false, true, emptySet()))
    }

    @Test fun androidTwentySixStillUsesLegacyAudio() {
        assertEquals(listOf(FirstRunPermissionPlan.READ_EXTERNAL_STORAGE),
            FirstRunPermissionPlan.select(26, false, false, emptySet()))
    }

    @Test fun tvNeverAsksForPhoneOrBackgroundNotificationEvenWithTelephonyFlag() {
        assertEquals(listOf(audio), FirstRunPermissionPlan.select(34, true, true, emptySet()))
    }

    @Test fun noTelephonyDeviceOmitsPhonePermission() {
        assertEquals(listOf(audio, notifications), FirstRunPermissionPlan.select(34, false, false, emptySet()))
    }

    @Test fun alreadyGrantedPermissionsAreOmittedAndBluetoothIsNeverIncluded() {
        val plan = FirstRunPermissionPlan.select(34, false, true, setOf(audio, phone))
        assertEquals(listOf(notifications), plan)
        assertFalse(plan.any { it.contains("BLUETOOTH") })
    }

    @Test fun returnedPlanCannotBeMutatedByCaller() {
        val plan = FirstRunPermissionPlan.select(34, false, true, emptySet())
        try { (plan as MutableList<String>).clear(); fail("Plan must be immutable") }
        catch (_: UnsupportedOperationException) { }
    }

    @Test fun requestsAreSerialAndRepeatedNextNeverOverlapsPendingRequest() {
        val coordinator = FirstRunPermissionCoordinator(listOf(audio, phone))
        assertEquals(audio, coordinator.nextPermission())
        assertNull(coordinator.nextPermission())
        assertEquals(audio, coordinator.pending)
        assertTrue(coordinator.onResult(audio, true))
        assertEquals(phone, coordinator.nextPermission())
        assertNull(coordinator.nextPermission())
    }

    @Test fun denialAndLaunchFailureAdvanceInsteadOfRetrying() {
        val coordinator = FirstRunPermissionCoordinator(listOf(audio, phone, notifications))
        assertEquals(audio, coordinator.nextPermission())
        assertTrue(coordinator.onResult(audio, false))
        assertEquals(phone, coordinator.nextPermission())
        assertTrue(coordinator.onRequestFailed(phone))
        assertEquals(notifications, coordinator.nextPermission())
        assertTrue(coordinator.onResult(notifications, false))
        assertTrue(coordinator.completed)
        assertNull(coordinator.nextPermission())
    }

    @Test fun staleOrDuplicateResultCannotConsumeAnotherPermission() {
        val coordinator = FirstRunPermissionCoordinator(listOf(audio, phone))
        coordinator.nextPermission()
        assertFalse(coordinator.onResult(phone, true))
        assertEquals(audio, coordinator.pending)
        assertTrue(coordinator.onResult(audio, false))
        coordinator.nextPermission()
        assertFalse(coordinator.onResult(audio, false))
        assertEquals(phone, coordinator.pending)
    }

    @Test fun permissionGrantedWhileQueueWaitsIsSkippedWithoutAnotherDialog() {
        val coordinator = FirstRunPermissionCoordinator(listOf(audio, phone, notifications))
        assertEquals(audio, coordinator.nextPermission())
        coordinator.onResult(audio, true)
        assertEquals(notifications, coordinator.nextPermission(setOf(phone)))
    }

    @Test fun skipFinishesWholeQueueAndLateResultCannotRestartIt() {
        val coordinator = FirstRunPermissionCoordinator(listOf(audio, phone))
        coordinator.nextPermission()
        coordinator.finish()
        assertTrue(coordinator.completed)
        assertNull(coordinator.pending)
        assertFalse(coordinator.onResult(audio, true))
        assertNull(coordinator.nextPermission())
    }

    @Test fun emptyAndDuplicatePlansDoNotRequestExtraSteps() {
        assertTrue(FirstRunPermissionCoordinator(emptyList()).completed)
        val coordinator = FirstRunPermissionCoordinator(listOf(audio, audio))
        assertEquals(audio, coordinator.nextPermission())
        coordinator.onResult(audio, false)
        assertTrue(coordinator.completed)
    }
}
