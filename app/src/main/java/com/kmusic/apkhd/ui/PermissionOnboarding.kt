package com.kmusic.apkhd.ui

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat
import com.kmusic.apkhd.data.PermissionOnboardingStore
import com.kmusic.apkhd.playback.CallStateMonitor
import kotlinx.coroutines.launch

/** One capability-filtered request at a time; rejected requests do not block listening. */
@Composable
internal fun PermissionOnboarding(
    context: Context,
    existingUser: Boolean,
    isTv: Boolean,
    beforeRequest: () -> Unit,
    afterRequest: () -> Unit,
    onAudioResult: () -> Unit
) {
    val store = remember(context.applicationContext) { PermissionOnboardingStore(context) }
    val scope = rememberCoroutineScope()
    var showIntro by remember { mutableStateOf(false) }
    var coordinator by remember { mutableStateOf<FirstRunPermissionCoordinator?>(null) }
    var step by remember { mutableIntStateOf(0) }
    val hasTelephony = remember { context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY) }
    fun granted(): Set<String> = listOf(FirstRunPermissionPlan.READ_EXTERNAL_STORAGE,
        FirstRunPermissionPlan.READ_MEDIA_AUDIO, FirstRunPermissionPlan.READ_PHONE_STATE,
        FirstRunPermissionPlan.POST_NOTIFICATIONS).filterTo(mutableSetOf()) {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        afterRequest()
        val active = coordinator
        val permission = active?.pending
        if (permission != null && active.onResult(permission, allowed)) {
            if (permission == FirstRunPermissionPlan.READ_EXTERNAL_STORAGE || permission == FirstRunPermissionPlan.READ_MEDIA_AUDIO) onAudioResult()
            if (permission == FirstRunPermissionPlan.READ_PHONE_STATE) CallStateMonitor.requestRefresh()
            step++
        }
    }
    LaunchedEffect(Unit) {
        when (store.initialize(existingUser)) {
            PermissionOnboardingStore.Gate.NEW -> {
                val plan = FirstRunPermissionPlan.select(Build.VERSION.SDK_INT, isTv, hasTelephony, granted())
                if (plan.isEmpty()) store.complete() else showIntro = true
            }
            PermissionOnboardingStore.Gate.LEGACY -> {
                if (store.takeLegacyNotice() && !isTv && hasTelephony && FirstRunPermissionPlan.READ_PHONE_STATE !in granted()) {
                    android.widget.Toast.makeText(context, "来电恢复需电话状态权限；可在系统应用权限中授权，不影响正常听歌", android.widget.Toast.LENGTH_LONG).show()
                }
            }
            PermissionOnboardingStore.Gate.COMPLETE -> Unit
        }
    }
    LaunchedEffect(step, coordinator) {
        val active = coordinator ?: return@LaunchedEffect
        val permission = active.nextPermission(granted())
        if (permission == null) {
            if (active.completed) store.complete()
        } else {
            beforeRequest()
            try { launcher.launch(permission) } catch (_: Exception) {
                afterRequest()
                active.onRequestFailed(permission)
                step++
            }
        }
    }
    if (showIntro) AlertDialog(
        onDismissRequest = { showIntro = false; scope.launch { store.complete(skipped = true) } },
        title = { Text("让播放更顺畅") },
        text = { Text(buildString {
            append("读取本地音乐，仅访问音频文件。")
            if (!isTv && hasTelephony) append("\n识别通话状态，以便挂机后续播，不读取号码或联系人。")
            if (!isTv && Build.VERSION.SDK_INT >= 33) append("\n显示播放通知，方便后台控制。")
            append("\n\n可逐项拒绝，不影响在线听歌。")
        }) },
        confirmButton = { TextButton(onClick = {
            showIntro = false
            scope.launch {
                store.markStarted()
                coordinator = FirstRunPermissionCoordinator(FirstRunPermissionPlan.select(Build.VERSION.SDK_INT, isTv, hasTelephony, granted()))
            }
        }) { Text("继续") } },
        dismissButton = { TextButton(onClick = { showIntro = false; scope.launch { store.complete(skipped = true) } }) { Text("暂不授权") } }
    )
}
