plugins {
    id("com.android.application") version "8.13.0" apply false
    id("com.android.library") version "8.13.0" apply false
    id("org.jetbrains.kotlin.android") version "2.2.20" apply false
    id("org.jetbrains.kotlin.multiplatform") version "2.2.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.20" apply false
    id("org.jetbrains.compose") version "1.9.0" apply false
}

// OneDrive locks files inside build folders mid-build. When the project lives in OneDrive, build
// into ~/.pakpak-build instead (finished APKs are still copied to mobile/dist).
if (rootDir.absolutePath.contains("OneDrive", ignoreCase = true)) {
    subprojects {
        layout.buildDirectory.set(File(System.getProperty("user.home"), ".pakpak-build/${project.name}"))
    }
}
