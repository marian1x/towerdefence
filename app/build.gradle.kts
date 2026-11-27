import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// ---------------------------------------------------------------- versioning
// versionCode = number of commits, so every pushed commit is a newer build (CI and local agree).
// The app compares it with the latest GitHub release ("build-<code>") to offer updates.
val versionBase: String = Properties().apply {
    rootProject.file("version.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}.getProperty("base", "0.1")

val commitCount: Int = runCatching {
    providers.exec {
        commandLine("git", "rev-list", "--count", "HEAD")
        isIgnoreExitValue = true
    }.standardOutput.asText.get().trim().toInt()
}.getOrDefault(1)

// ---------------------------------------------------------------- signing
// One shared key signs every build (local + CI) so updates install over each other and keep the save.
// Locally it comes from keystore.properties (git-ignored, created by tools/setup-signing.ps1);
// on CI from environment variables filled from GitHub secrets. Without it, the debug key is used.
val keystoreProps = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

fun signingValue(prop: String, env: String): String? =
    providers.environmentVariable(env).orNull?.takeIf { it.isNotBlank() } ?: keystoreProps.getProperty(prop)

val keystorePath: String? = signingValue("storeFile", "SIGNING_KEYSTORE")?.takeIf { file(it).exists() }

android {
    namespace = "com.marian1x.agesofsiege"
    compileSdk {
        version = release(37) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.marian1x.agesofsiege"
        minSdk = 26
        targetSdk = 37
        versionCode = commitCount
        versionName = "$versionBase.$commitCount"

        // Public repository whose GitHub releases deliver in-app updates.
        buildConfigField("String", "UPDATE_REPO", "\"marian1x/towerdefence\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (keystorePath != null) {
            create("shared") {
                storeFile = file(keystorePath)
                storePassword = signingValue("storePassword", "SIGNING_STORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "SIGNING_KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "SIGNING_KEY_PASSWORD") ?: storePassword
            }
        }
    }

    buildTypes {
        val shared = signingConfigs.findByName("shared")
        debug {
            if (shared != null) signingConfig = shared
        }
        release {
            signingConfig = shared ?: signingConfigs.getByName("debug")
            // R8 package-scoped optimization crashed at startup (IllegalAccessError in kotlin.collections).
            // Shrinking isn't needed for a friends-only build; revisit with proper keep rules for Filament.
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.work.runtime)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.filament.android)
    implementation(libs.filamat.android)
    implementation(libs.gltfio.android)
    implementation(libs.filament.utils.android)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
