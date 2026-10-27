import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "dev.heynol.afterglow"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.heynol.afterglow"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
    }

    // Release key: a properties file (storeFile, storePassword, keyAlias, keyPassword) outside the repo,
    // from $HEYNOL_SIGNING_PROPERTIES or ~/Keystores/heynol-sideload.properties. Without it the release
    // build falls back to the debug key, so anyone can build an installable APK from source.
    val signingProperties = (System.getenv("HEYNOL_SIGNING_PROPERTIES")?.takeIf { it.isNotBlank() }
        ?.let(::File) ?: File(System.getProperty("user.home"), "Keystores/heynol-sideload.properties"))
        .takeIf { it.isFile }
        ?.let { file -> Properties().apply { file.inputStream().use(::load) } }

    signingConfigs {
        if (signingProperties != null) {
            create("release") {
                storeFile = File(signingProperties.getProperty("storeFile"))
                storePassword = signingProperties.getProperty("storePassword")
                keyAlias = signingProperties.getProperty("keyAlias")
                keyPassword = signingProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (signingProperties != null) {
                signingConfigs.getByName("release")
            } else {
                logger.warn("Afterglow: no release signing properties found, signing the release build with the debug key.")
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        // AudioAnalyzer & co. are plain Kotlin; org.json is used by the catalog parser in tests.
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.json)
}
