package com.druk.lmplayground.computer

import android.content.Context
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.druk.lmplayground.R

/**
 * One-shot explainer the first time the user opens Computer (COS): storage cost,
 * Stop tears down proot/Xvfb, off-by-default. Pref: [ComputerPrefs.KEY_FIRST_RUN_SEEN].
 */
@Composable
fun CosFirstRunDialogHost() {
    val context = LocalContext.current
    var show by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val prefs = context.getSharedPreferences(CosConstants.PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(ComputerPrefs.KEY_FIRST_RUN_SEEN, false)) {
            show = true
        }
    }
    if (!show) return
    AlertDialog(
        onDismissRequest = { /* require Got it so storage/Stop copy is seen once */ },
        title = { Text(stringResource(R.string.cos_first_run_title)) },
        text = { Text(stringResource(R.string.cos_first_run_body)) },
        confirmButton = {
            TextButton(
                onClick = {
                    context.getSharedPreferences(CosConstants.PREFS_NAME, Context.MODE_PRIVATE)
                        .edit()
                        .putBoolean(ComputerPrefs.KEY_FIRST_RUN_SEEN, true)
                        .apply()
                    show = false
                },
            ) {
                Text(stringResource(R.string.cos_first_run_got_it))
            }
        },
    )
}
