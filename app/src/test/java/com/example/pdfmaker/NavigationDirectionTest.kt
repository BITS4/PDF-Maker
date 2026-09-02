package com.example.pdfmaker

import org.junit.Assert.assertEquals
import org.junit.Test

class NavigationDirectionTest {
    @Test
    fun `forward journeys slide content left`() {
        assertEquals(NavDirection.LEFT, navDirectionFor(Screen.HOME, Screen.FILES))
        assertEquals(NavDirection.LEFT, navDirectionFor(Screen.IMAGE_SELECTION, Screen.CONVERT_RESULT))
    }

    @Test
    fun `backward journeys slide content right`() {
        assertEquals(NavDirection.RIGHT, navDirectionFor(Screen.SETTINGS, Screen.HOME))
        assertEquals(NavDirection.RIGHT, navDirectionFor(Screen.PRINT_PDF, Screen.IMPORT_PDF))
    }

    @Test
    fun `same destination does not animate directionally`() {
        Screen.entries.forEach { screen ->
            assertEquals(NavDirection.NONE, navDirectionFor(screen, screen))
        }
    }
}
