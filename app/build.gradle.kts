import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Optional, git-ignored test provider that pre-fills the setup form in debug builds only
// (left out with -PnoDevProvider, for test builds that others may see).
val devProvider = Properties().apply {
    val file = rootProject.file("provider.local.properties")
    if (file.isFile && !project.hasProperty("noDevProvider")) file.reader().use { load(it) }
}

// Release signing: from environment variables (GitHub Actions secrets), or locally from
// ~/.telly/keystore.properties. Never committed. Without either, release builds are unsigned.
val releaseSigning = Properties().apply {
    val file = File(System.getProperty("user.home"), ".telly/keystore.properties")
    if (file.isFile) file.reader().use { load(it) }
}

fun signingValue(env: String, key: String): String? = System.getenv(env) ?: releaseSigning.getProperty(key)

// The version comes from the release tag (e.g. -PtellyVersion=1.2.3 from "v1.2.3");
// versionCode is derived from it so every release is an upgrade: 1.2.3 → 10203.
val tellyVersion = (findProperty("tellyVersion") as String?)?.removePrefix("v") ?: "1.0.0"
val tellyVersionCode = tellyVersion.split('.').map { it.toIntOrNull() ?: 0 }.let { (it + listOf(0, 0, 0)).take(3) }
    .let { (major, minor, patch) -> major * 10_000 + minor * 100 + patch }

fun javaString(value: String?): String =
    "\"" + value.orEmpty().trim().replace("\\", "\\\\").replace("\"", "\\\"") + "\""

android {
    namespace = "io.github.tellyteam.telly"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.tellyteam.telly"
        // 23 for Keystore AES-GCM; also covers Fire TV Stick 4K (Fire OS 6, Android 7.1).
        minSdk = 23
        targetSdk = 35
        versionCode = tellyVersionCode
        versionName = tellyVersion
    }

    signingConfigs {
        create("release") {
            signingValue("TELLY_KEYSTORE_PATH", "storeFile")?.let { path ->
                storeFile = file(path)
                storePassword = signingValue("TELLY_KEYSTORE_PASSWORD", "storePassword")
                keyAlias = signingValue("TELLY_KEY_ALIAS", "keyAlias")
                keyPassword = signingValue("TELLY_KEY_PASSWORD", "keyPassword")
            }
        }
    }

    buildTypes {
        val devKeys = listOf("type", "name", "server", "username", "password", "url", "allowCleartext")
        debug {
            devKeys.forEach { buildConfigField("String", "DEV_PROVIDER_${it.uppercase()}", javaString(devProvider.getProperty(it))) }
        }
        release {
            // Release builds never carry the developer's test provider.
            devKeys.forEach { buildConfigField("String", "DEV_PROVIDER_${it.uppercase()}", "\"\"") }
            signingConfig = signingConfigs.getByName("release").takeIf { it.storeFile != null }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

// AGP 9 compiles Kotlin itself (no org.jetbrains.kotlin.android plugin); this is its Kotlin config.
kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.datasource.okhttp)
    implementation(libs.media3.ui)

    implementation(libs.okhttp)
    implementation(libs.coil.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    // QR codes for linking sync (encoding only; Apache-2.0)
    implementation(libs.zxing.core)

    testImplementation(libs.junit)
}
