package com.kmusic.apkhd.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.permissionOnboardingDataStore by preferencesDataStore(name = "permission_onboarding")

/** Caller captures legacy evidence before normal startup can create settings or queue files. */
class PermissionOnboardingStore internal constructor(private val store: DataStore<Preferences>) {
    constructor(context: Context) : this(context.applicationContext.permissionOnboardingDataStore)

    enum class Gate { NEW, LEGACY, COMPLETE }

    private val attemptedKey = booleanPreferencesKey("attempted")
    private val completedKey = booleanPreferencesKey("completed")
    private val skippedKey = booleanPreferencesKey("skipped")
    private val legacyNoticeKey = booleanPreferencesKey("legacy_notice_pending")

    val attempted: Flow<Boolean> = store.data.map { it[attemptedKey] ?: false }
    val completed: Flow<Boolean> = store.data.map { it[completedKey] ?: false }

    suspend fun initialize(existingUser: Boolean): Gate {
        var gate = Gate.COMPLETE
        store.edit { preferences ->
            when {
                preferences[completedKey] == true -> Unit
                preferences[attemptedKey] == true -> preferences[completedKey] = true
                existingUser -> {
                    preferences[attemptedKey] = true
                    preferences[completedKey] = true
                    preferences[legacyNoticeKey] = true
                    gate = Gate.LEGACY
                }
                else -> gate = Gate.NEW
            }
        }
        return gate
    }

    suspend fun markStarted() {
        store.edit { it[attemptedKey] = true }
    }

    suspend fun complete(skipped: Boolean = false) {
        store.edit {
            it[attemptedKey] = true
            it[completedKey] = true
            if (skipped) it[skippedKey] = true
        }
    }

    /** A legacy user's missing permission can be explained once, never turned into another request queue. */
    suspend fun takeLegacyNotice(): Boolean {
        var show = false
        store.edit {
            show = it[legacyNoticeKey] == true
            if (show) it[legacyNoticeKey] = false
        }
        return show
    }
}
