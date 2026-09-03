package com.example.pdfmaker

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Emulator-independent lifecycle coverage for the application's real launcher activity. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppLaunchRobolectricTest {
    @Test
    fun `launcher activity creates its content and remains active`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()

        assertNotNull(activity.window.decorView)
        assertFalse("Main activity must remain active after first render", activity.isFinishing)

        controller.pause().stop().destroy()
    }
}
