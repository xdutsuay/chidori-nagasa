package com.druk.lmplayground

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.navigation.fragment.NavHostFragment
import com.druk.lmplayground.checkin.ACTION_OPEN_CHECK_IN
import com.druk.lmplayground.checkin.CheckInPendingHold
import com.druk.lmplayground.checkin.EXTRA_CHECK_IN_INSTANCE_ID
import com.druk.lmplayground.checkin.EXTRA_CHECK_IN_RUN_ID
import com.druk.lmplayground.checkin.EXTRA_CHECK_IN_SESSION_ID
import com.druk.lmplayground.checkin.EXTRA_CHECK_IN_SOURCE
import com.druk.lmplayground.checkin.EXTRA_CHECK_IN_SOURCE_ID
import com.druk.lmplayground.checkin.OpenItemSource
import com.druk.lmplayground.checkin.parseCheckInDeepLink
import com.druk.lmplayground.coordinator.notifications.EXTRA_NEEDS_YOU_INSTANCE_ID
import com.druk.lmplayground.coordinator.notifications.EXTRA_NEEDS_YOU_RUN_ID
import com.druk.lmplayground.coordinator.notifications.NeedsYouDeepLink
import com.druk.lmplayground.coordinator.notifications.NeedsYouPendingHold
import com.druk.lmplayground.coordinator.notifications.parseNeedsYouDeepLink
import com.druk.lmplayground.storage.StorageDocuments

class MainActivity : AppCompatActivity() {

    private var folderPickerCallback: ((Uri?) -> Unit)? = null

    val folderPickerLauncher: ActivityResultLauncher<Uri?> = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        uri?.let {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
        folderPickerCallback?.invoke(uri)
        folderPickerCallback = null
    }

    fun launchFolderPicker(callback: (Uri?) -> Unit) {
        folderPickerCallback = callback
        try {
            folderPickerLauncher.launch(null)
        } catch (_: ActivityNotFoundException) {
            // Some devices ship without (or with disabled) DocumentsUI —
            // OPEN_DOCUMENT_TREE has no handler and ActivityResultLauncher
            // throws synchronously. No file-manager app can stand in for it,
            // so fall back to the app-private models directory instead of
            // leaving the user wedged on the mandatory folder-setup dialog.
            folderPickerCallback = null
            val fallbackUri = StorageDocuments.appStorageFallbackUri(this)
            if (fallbackUri != null) {
                Toast.makeText(
                    this,
                    R.string.storage_fallback_used,
                    Toast.LENGTH_LONG,
                ).show()
                callback(fallbackUri)
            } else {
                Toast.makeText(
                    this,
                    R.string.folder_picker_unavailable,
                    Toast.LENGTH_LONG,
                ).show()
                callback(null)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContentView(R.layout.content_main)
        handleNeedsYouIntent(intent)
        handleCheckInIntent(intent)
        handleComputerDeepLink(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleNeedsYouIntent(intent)
        handleCheckInIntent(intent)
        handleComputerDeepLink(intent)
    }

    /**
     * Check-in notification tap. Chat opens the session, a desktop run reuses
     * the needs-you run deep link, and a team-room item opens Settings → Check-ins
     * because the team room has no screen of its own.
     */
    private fun handleCheckInIntent(intent: Intent?) {
        if (intent == null) return
        val link = parseCheckInDeepLink(
            action = intent.action,
            source = intent.getStringExtra(EXTRA_CHECK_IN_SOURCE),
            sourceId = intent.getStringExtra(EXTRA_CHECK_IN_SOURCE_ID),
            sessionId = intent.getStringExtra(EXTRA_CHECK_IN_SESSION_ID),
            instanceId = intent.getStringExtra(EXTRA_CHECK_IN_INSTANCE_ID),
            runId = intent.getStringExtra(EXTRA_CHECK_IN_RUN_ID),
        ) ?: return
        if (intent.action == ACTION_OPEN_CHECK_IN) {
            intent.action = Intent.ACTION_MAIN
            intent.removeExtra(EXTRA_CHECK_IN_SOURCE)
            intent.removeExtra(EXTRA_CHECK_IN_SOURCE_ID)
            intent.removeExtra(EXTRA_CHECK_IN_SESSION_ID)
            intent.removeExtra(EXTRA_CHECK_IN_INSTANCE_ID)
            intent.removeExtra(EXTRA_CHECK_IN_RUN_ID)
        }
        if (link.source == OpenItemSource.DESKTOP_RUN.storageValue()) {
            val instanceId = link.instanceId
            val runId = link.runId
            if (instanceId != null && runId != null) {
                // Offer before navigation so a Chidori screen that is already
                // started can consume the link, and so a cold start finds it
                // waiting in onStart.
                NeedsYouPendingHold.offer(NeedsYouDeepLink(instanceId, runId))
            }
        }
        val navHost = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as? NavHostFragment
            ?: return
        val nav = navHost.navController
        window.decorView.post {
            when (link.source) {
                OpenItemSource.DESKTOP_RUN.storageValue() -> {
                    if (nav.currentDestination?.id != R.id.nav_chidori) {
                        runCatching { nav.navigate(R.id.nav_chidori) }
                    }
                }
                OpenItemSource.TEAM_ROOM.storageValue() -> {
                    if (nav.currentDestination?.id != R.id.nav_check_ins) {
                        runCatching { nav.navigate(R.id.nav_check_ins) }
                    }
                }
                else -> {
                    CheckInPendingHold.offer(link)
                    if (nav.currentDestination?.id != R.id.nav_home) {
                        runCatching { nav.popBackStack(R.id.nav_home, false) }
                    }
                }
            }
        }
    }

    /**
     * Dogfood / adb: `chidori-nagasa://computer` opens [ComputerFragment] without
     * UI taps or KEYCODE_BACK (which previously risked leaving the app for Imou).
     */
    private fun handleComputerDeepLink(intent: Intent?) {
        if (intent == null) return
        val uri = intent.data ?: return
        if (uri.scheme != "chidori-nagasa" || uri.host != "computer") return
        intent.data = null
        val navHost = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as? NavHostFragment
            ?: return
        val nav = navHost.navController
        window.decorView.post {
            if (nav.currentDestination?.id != R.id.nav_computer) {
                runCatching { nav.navigate(R.id.nav_computer) }
            }
        }
    }

    /**
     * needs_you notification tap (KMA-288 follow-on): stash the deep link
     * and navigate to Chidori Desktop so the ViewModel can open the run
     * once paired instances are loaded. Pairing itself is not modified.
     */
    private fun handleNeedsYouIntent(intent: Intent?) {
        if (intent == null) return
        val link = parseNeedsYouDeepLink(
            action = intent.action,
            instanceId = intent.getStringExtra(EXTRA_NEEDS_YOU_INSTANCE_ID),
            runId = intent.getStringExtra(EXTRA_NEEDS_YOU_RUN_ID),
        ) ?: return
        NeedsYouPendingHold.offer(link)
        // Drop deep-link fields so a later MAIN recreation cannot re-open.
        intent.action = Intent.ACTION_MAIN
        intent.removeExtra(EXTRA_NEEDS_YOU_INSTANCE_ID)
        intent.removeExtra(EXTRA_NEEDS_YOU_RUN_ID)

        val navHost = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as? NavHostFragment
            ?: return
        val nav = navHost.navController
        if (nav.currentDestination?.id != R.id.nav_chidori) {
            // Post so the NavHost finishes the first layout/startDestination
            // before we push Chidori (cold start from notification).
            window.decorView.post {
                if (nav.currentDestination?.id != R.id.nav_chidori) {
                    runCatching { nav.navigate(R.id.nav_chidori) }
                }
            }
        }
    }
}
