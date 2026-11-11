package com.druk.lmplayground.settings

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/**
 * Runs an action after asking for POST_NOTIFICATIONS (API 33+) when it is not granted, so
 * Computer API approvals can show the Allow / Deny notification. The action runs whatever the
 * answer; without the grant, approvals are only answerable in the app.
 */
@Composable
internal fun rememberWithApprovalNotificationPermission(): (askIf: Boolean, action: () -> Unit) -> Unit {
    val context = LocalContext.current
    val pending = remember { arrayOfNulls<() -> Unit>(1) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        pending[0]?.invoke()
        pending[0] = null
    }
    return remember(context, launcher) {
        { askIf, action ->
            val needed = askIf &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            if (needed) {
                pending[0] = action
                launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                action()
            }
        }
    }
}
