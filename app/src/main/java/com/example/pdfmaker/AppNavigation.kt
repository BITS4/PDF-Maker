package com.example.pdfmaker

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

private const val PreferencesName = "pdfmaker_prefs"
private const val OnboardingDoneKey = "onboarding_done"

@Composable
fun AppNavigation(activity: MainActivity) {
    val navigation =
        remember(activity) {
            val onboardingDone =
                activity
                    .getSharedPreferences(PreferencesName, 0)
                    .getBoolean(OnboardingDoneKey, false)
            AppNavigationState(showOnboardingInitially = !onboardingDone)
        }

    val needsPin =
        SettingsManager.getSecurityEnabled(activity) &&
            SettingsManager.hasPin(activity) &&
            !navigation.pinUnlocked
    if (needsPin) {
        PinScreen(onUnlocked = { navigation.pinUnlocked = true })
        return
    }

    if (navigation.showSplash) {
        SplashScreen(onReady = { navigation.showSplash = false })
        return
    }

    if (navigation.showOnboarding) {
        OnboardingScreen(
            onDone = {
                activity
                    .getSharedPreferences(PreferencesName, 0)
                    .edit()
                    .putBoolean(OnboardingDoneKey, true)
                    .apply()
                navigation.showOnboarding = false
            },
        )
        return
    }

    BackHandler(enabled = navigation.currentScreen != Screen.HOME) {
        navigation.handleSystemBack()
    }
    IncomingDocumentEffect(activity, navigation)

    ScreenTransition(
        targetState = navigation.currentScreen,
        direction = navDirectionFor(navigation.previousScreen, navigation.currentScreen),
    ) { screen ->
        AppRoute(
            activity = activity,
            navigation = navigation,
            screen = screen,
        )
    }
}
