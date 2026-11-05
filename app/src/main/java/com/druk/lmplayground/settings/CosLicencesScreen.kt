package com.druk.lmplayground.settings

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.druk.lmplayground.R

/** Renders `assets/licenses/NOTICE` for Settings → COS licences. */
@Composable
fun CosLicencesContent(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val text = remember {
        runCatching {
            context.assets.open("licenses/NOTICE").bufferedReader().use { it.readText() }
        }.getOrNull()
    }
    Text(
        text = text ?: stringResource(R.string.cos_licences_missing),
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    )
}
