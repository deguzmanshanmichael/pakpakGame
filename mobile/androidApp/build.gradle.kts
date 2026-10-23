import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Build configuration comes from -P command line properties, mobile/local.properties, or
// mobile/keystore.properties. Nothing environment-specific is hardcoded in source.
val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
val keystoreProps = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
fun prop(name: String): String? = (findProperty(name) as String?) ?: localProps.getProperty(name)

val debugApi = prop("pakpak.apiBaseUrl.debug") ?: ""
val releaseApi = prop("pakpak.apiBaseUrl.release") ?: ""
if (releaseApi.isNotEmpty() && !releaseApi.startsWith("https://")) {
    throw GradleException("pakpak.apiBaseUrl.release must use https:// (got $releaseApi)")
}

android {
    namespace = "com.shan.pakpak"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.shan.pakpak"
        minSdk = 24
        targetSdk = 36
        versionCode = 4
        versionName = "1.2.1"
    }

    signingConfigs {
        if (keystoreProps.getProperty("storeFile") != null) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            buildConfigField("String", "API_BASE_URL", "\"$debugApi\"")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            buildConfigField("String", "API_BASE_URL", "\"$releaseApi\"")
            signingConfig = signingConfigs.findByName("release")
        }
    }

    buildFeatures { buildConfig = true }
    // SoundPool/MediaPlayer read the WAVs straight out of the APK, which needs them uncompressed.
    androidResources { noCompress += "wav" }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
    }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":shared"))
    implementation("androidx.activity:activity-compose:1.10.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
}

// Copy finished APKs to mobile/dist/ with a friendly name, e.g. dist/PakPak-debug.apk.
androidComponents {
    onVariants { variant ->
        val name = variant.name
        val cap = name.replaceFirstChar { it.uppercase() }
        val copy = tasks.register<Copy>("copy${cap}Apk") {
            from(variant.artifacts.get(com.android.build.api.artifact.SingleArtifact.APK))
            include("*.apk")
            into(rootProject.layout.projectDirectory.dir("dist"))
            rename { "PakPak-$name.apk" }
        }
        afterEvaluate { tasks.named("assemble$cap") { finalizedBy(copy) } }
    }
}
