package com.example.pdfmaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppNavigationStateTest {
    @Test
    fun `navigate records history and home clears transient origins`() {
        val navigation = AppNavigationState(showOnboardingInitially = false)
        navigation.fromMoreTools = true
        navigation.fromFiles = true

        navigation.navigate(Screen.SETTINGS)
        assertEquals(Screen.HOME, navigation.previousScreen)
        assertEquals(Screen.SETTINGS, navigation.currentScreen)
        assertTrue(navigation.fromMoreTools)
        assertTrue(navigation.fromFiles)

        navigation.navigate(Screen.HOME)
        assertEquals(Screen.SETTINGS, navigation.previousScreen)
        assertEquals(Screen.HOME, navigation.currentScreen)
        assertFalse(navigation.fromMoreTools)
        assertFalse(navigation.fromFiles)
    }

    @Test
    fun `back to origin consumes more tools origin`() {
        val navigation = AppNavigationState(showOnboardingInitially = false)
        navigation.fromMoreTools = true
        navigation.navigate(Screen.OCR)

        navigation.navigateBackToOrigin()

        assertEquals(Screen.MORE_TOOLS, navigation.currentScreen)
        assertFalse(navigation.fromMoreTools)
    }

    @Test
    fun `back to origin falls back home without origin`() {
        val navigation = AppNavigationState(showOnboardingInitially = false)
        navigation.navigate(Screen.OCR)

        navigation.navigateBackToOrigin()

        assertEquals(Screen.HOME, navigation.currentScreen)
    }

    @Test
    fun `system back consumes files origin and returns viewer to files`() {
        val navigation = AppNavigationState(showOnboardingInitially = false)
        navigation.fromFiles = true
        navigation.navigate(Screen.VIEWER)

        navigation.handleSystemBack()

        assertEquals(Screen.FILES, navigation.currentScreen)
        assertFalse(navigation.fromFiles)
    }

    @Test
    fun `editor launch exposes typed mode once`() {
        val navigation = AppNavigationState(showOnboardingInitially = false)
        val launch = requireNotNull(AppNavigationPolicy.moreTool("signature"))

        navigation.launchTool(launch, fromMoreToolsGrid = true)

        assertEquals(Screen.IMPORT_PDF, navigation.currentScreen)
        assertTrue(navigation.fromMoreTools)
        assertEquals(PdfEditMode.SIGNATURE, navigation.consumePendingEditMode())
        assertEquals(PdfEditMode.NONE, navigation.consumePendingEditMode())
    }

    @Test
    fun `ordinary launch replaces stale editor mode`() {
        val navigation = AppNavigationState(showOnboardingInitially = false)
        navigation.launchTool(requireNotNull(AppNavigationPolicy.moreTool("doodle")))
        navigation.launchTool(requireNotNull(AppNavigationPolicy.moreTool("import_pdf")))

        assertEquals(PdfEditMode.NONE, navigation.consumePendingEditMode())
    }

    @Test
    fun `onboarding and splash gates retain independent state`() {
        val firstLaunch = AppNavigationState(showOnboardingInitially = true)
        val returning = AppNavigationState(showOnboardingInitially = false)

        assertTrue(firstLaunch.showOnboarding)
        assertFalse(returning.showOnboarding)
        assertTrue(firstLaunch.showSplash)
        firstLaunch.showSplash = false
        assertFalse(firstLaunch.showSplash)
    }
}
