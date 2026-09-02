package com.example.pdfmaker

import androidx.compose.runtime.Composable

@Composable
internal fun AppRoute(
    activity: MainActivity,
    navigation: AppNavigationState,
    screen: Screen,
) {
    when (AppNavigationPolicy.routeGroup(screen)) {
        AppRouteGroup.CORE -> AppCoreRoute(activity, navigation, screen)
        AppRouteGroup.IMAGE -> AppImageRoute(navigation, screen)
        AppRouteGroup.DOCUMENT -> AppDocumentRoute(activity, navigation, screen)
    }
}
