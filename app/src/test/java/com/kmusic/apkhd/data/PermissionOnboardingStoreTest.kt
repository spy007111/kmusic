package com.kmusic.apkhd.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PermissionOnboardingStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private val scopes = mutableListOf<CoroutineScope>()

    @After fun cleanup() { scopes.forEach { it.cancel() } }

    private fun store(): PermissionOnboardingStore {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scopes += scope
        val file = File(temporary.root, "permission_onboarding.preferences_pb")
        return PermissionOnboardingStore(PreferenceDataStoreFactory.create(scope = scope, produceFile = { file }))
    }

    @Test fun genuinelyNewInstallOffersGateWithoutClaimingPermissionAttempt() = runBlocking {
        val store = store()
        assertEquals(PermissionOnboardingStore.Gate.NEW, store.initialize(false))
        assertFalse(store.attempted.first())
        assertFalse(store.completed.first())
        assertFalse(store.takeLegacyNotice())
    }

    @Test fun startMarkerPrecedesRequestsAndInterruptedFlowDoesNotAskAgain() = runBlocking {
        val store = store()
        store.initialize(false)
        store.markStarted()
        assertTrue(store.attempted.first())
        assertFalse(store.completed.first())
        assertEquals(PermissionOnboardingStore.Gate.COMPLETE, store.initialize(false))
        assertTrue(store.completed.first())
        assertFalse(store.takeLegacyNotice())
    }

    @Test fun successfulOrDeniedCompletionDoesNotReenterOnNextLaunch() = runBlocking {
        val store = store()
        store.markStarted()
        store.complete()
        assertTrue(store.completed.first())
        assertEquals(PermissionOnboardingStore.Gate.COMPLETE, store.initialize(false))
        assertEquals(PermissionOnboardingStore.Gate.COMPLETE, store.initialize(true))
        assertFalse(store.takeLegacyNotice())
    }

    @Test fun skippingCardIsPersistedAsCompletedWithoutRequests() = runBlocking {
        val store = store()
        store.complete(skipped = true)
        assertTrue(store.attempted.first())
        assertTrue(store.completed.first())
        assertEquals(PermissionOnboardingStore.Gate.COMPLETE, store.initialize(false))
    }

    @Test fun legacyInstallMigratesBeforeAnyPermissionRequestAndNoticeIsOneShot() = runBlocking {
        val store = store()
        assertEquals(PermissionOnboardingStore.Gate.LEGACY, store.initialize(true))
        assertTrue(store.attempted.first())
        assertTrue(store.completed.first())
        assertTrue(store.takeLegacyNotice())
        assertFalse(store.takeLegacyNotice())
        assertEquals(PermissionOnboardingStore.Gate.COMPLETE, store.initialize(true))
        assertFalse(store.takeLegacyNotice())
    }

    @Test fun existingUserEvidenceIsSuppliedBeforeOnboardingCreatesItsFile() = runBlocking {
        val oldFile = File(temporary.root, "moyin_settings.preferences_pb").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val original = oldFile.readBytes()
        val existingBeforeInitialize = oldFile.exists() && oldFile.length() > 0
        val store = store()
        assertEquals(PermissionOnboardingStore.Gate.LEGACY, store.initialize(existingBeforeInitialize))
        assertArrayEquals(original, oldFile.readBytes())
        assertEquals(setOf("moyin_settings.preferences_pb", "permission_onboarding.preferences_pb"),
            temporary.root.listFiles()!!.map { it.name }.toSet())
    }

    @Test fun systemSettingsGrantChangesCannotReopenCompletedOnboarding() = runBlocking {
        val store = store()
        store.complete()
        repeat(3) { assertEquals(PermissionOnboardingStore.Gate.COMPLETE, store.initialize(false)) }
        assertTrue(store.completed.first())
    }

    @Test fun completedMarkerSurvivesStoreReload() = runBlocking {
        val first = store()
        first.markStarted()
        first.complete(skipped = true)
        scopes.last().coroutineContext[kotlinx.coroutines.Job]!!.cancel()
        scopes.last().coroutineContext[kotlinx.coroutines.Job]!!.join()
        val reloaded = store()
        assertTrue(reloaded.attempted.first())
        assertTrue(reloaded.completed.first())
        assertEquals(PermissionOnboardingStore.Gate.COMPLETE, reloaded.initialize(false))
    }

    @Test fun legacyNoticeSurvivesRepeatedInitializationUntilConsumed() = runBlocking {
        val store = store()
        store.initialize(true)
        assertEquals(PermissionOnboardingStore.Gate.COMPLETE, store.initialize(false))
        assertTrue(store.takeLegacyNotice())
        assertFalse(store.takeLegacyNotice())
    }
}
