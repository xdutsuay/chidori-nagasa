package com.xdutsuay.chidori

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.xdutsuay.chidori.conversation.ConversationBarTestTag
import org.junit.Rule
import org.junit.Test

class ConversationTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun testAppLaunch() {
        // Check that the conversation screen is visible on launch
        composeTestRule.onNodeWithTag(ConversationBarTestTag).assertIsDisplayed()
    }
}