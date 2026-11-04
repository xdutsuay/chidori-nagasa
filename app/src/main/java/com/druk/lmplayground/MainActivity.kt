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
import com.druk.lmplayground.coordinator.notifications.EXTRA_NEEDS_YOU_INSTANCE_ID
import com.druk.lmplayground.coordinator.notifications.EXTRA_NEEDS_YOU_RUN_ID
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
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleNeedsYouIntent(intent)
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
