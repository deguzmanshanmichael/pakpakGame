package com.shan.pakpak

import androidx.compose.ui.window.ComposeUIViewController
import platform.UIKit.UIViewController

/**
 * Entry points called from Swift (iosApp/iosApp/iOSApp.swift).
 * Swift sees these as MainViewControllerKt.MainViewController(...), .onAppPause(), .onAppResume().
 */
fun MainViewController(apiBaseUrl: String, versionName: String): UIViewController =
    ComposeUIViewController { PakPakApp(PlatformConfig(apiBaseUrl, versionName, "iOS")) }

fun onAppPause() = G.onPause()

fun onAppResume() = G.onResume()
