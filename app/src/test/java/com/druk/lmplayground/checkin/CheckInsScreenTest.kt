package com.druk.lmplayground.checkin

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.druk.lmplayground.settings.CheckInsContent
import com.druk.lmplayground.theme.PlaygroundTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Settings → Check-ins without an emulator. The enable switch defaults to
 * off in this content, and an open item can be parked from the list.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CheckInsScreenTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun enableSwitchIsOffAndOpenItemCanBeParked() {
        var parked: String? = null
        rule.setContent {
            PlaygroundTheme {
                CheckInsContent(
                    enabled = false,
                    frequency = CheckInFrequency.DAILY,
                    inactivityMs = CheckInPreferences.DEFAULT_INACTIVITY_MS,
                    quietStartMinute = 22 * 60,
                    quietEndMinute = 8 * 60,
                    paused = false,
                    items = listOf(
                        OpenItemEntity(
                            id = "item-1",
                            source = "chat",
                            sourceId = "s",
                            kind = "DECISION",
                            summary = "Ship the migration",
                            probeJson = """{"type":"chat"}""",
                            status = "OPEN",
                            lastProbeState = "open",
                            initialProbeState = "open",
                            fingerprint = "fp",
                            createdAt = 1,
                            updatedAt = 1,
                        ),
                    ),
                    onEnabled = {},
                    onFrequency = {},
                    onInactivity = {},
                    onQuietStart = {},
                    onQuietEnd = {},
                    onPaused = {},
                    onPark = { parked = it.id },
                    onClose = {},
                )
            }
        }
        rule.onNodeWithText("Enable check-ins").assertIsDisplayed()
        rule.onNodeWithTag("checkins_enabled").assertIsOff()
        rule.onNodeWithTag("checkins_pause").assertIsOff()
        rule.onNodeWithText("Open items").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Ship the migration").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("open_item_park_item-1").performScrollTo().performClick()
        assertEquals("item-1", parked)
    }
}
