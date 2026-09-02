package com.example.pdfmaker

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppLaunchInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun activityLaunchesAndRendersAComposeRoot() {
        composeRule.onRoot(useUnmergedTree = true).assertExists()
        composeRule.runOnUiThread {
            assertFalse("Main activity must remain active after first render", composeRule.activity.isFinishing)
        }
    }
}
