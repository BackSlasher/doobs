import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

/**
 * Signing material never lives in the repo. `./dev` mounts it read-only at
 * /secrets; CI supplies the same values as environment variables. With neither
 * present, a release build comes out unsigned rather than quietly falling back
 * to the debug key — a debug-signed "release" cannot upgrade a real one, and
 * finding that out later costs an uninstall.
 */
val signingProperties = Properties().apply {
    val dir = System.getenv("DOOBS_KEYSTORE_DIR") ?: "/secrets"
    File(dir, "keystore.properties").takeIf { it.isFile }?.inputStream()?.use { load(it) }
}

fun signingValue(environment: String, property: String): String? =
    System.getenv(environment) ?: signingProperties.getProperty(property)

/**
 * One per commit: monotonic, identical here and in CI, and needing no state to
 * coordinate. Falls back to 1 only outside a git checkout.
 */
val commitCount: Int = providers.exec {
    commandLine("git", "rev-list", "--count", "HEAD")
    isIgnoreExitValue = true
}.standardOutput.asText.map { it.trim().toIntOrNull() ?: 1 }.getOrElse(1)

android {
    namespace = "dev.doobs"
    compileSdk = 35
    // Pinned to what the build image ships; the SDK dir is read-only so Gradle
    // must not try to fetch a different one.
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "dev.doobs"
        minSdk = 26
        targetSdk = 35
        versionCode = commitCount
        versionName = "0.1.$commitCount"
    }

    signingConfigs {
        val storePath = signingValue("DOOBS_STORE_FILE", "storeFile")
        if (storePath != null && File(storePath).isFile) {
            create("release") {
                storeFile = File(storePath)
                storePassword = signingValue("DOOBS_STORE_PASSWORD", "storePassword")
                keyAlias = signingValue("DOOBS_KEY_ALIAS", "keyAlias")
                keyPassword = signingValue("DOOBS_KEY_PASSWORD", "keyPassword")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    buildFeatures {
        buildConfig = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    sourceSets {
        getByName("main").java.srcDirs("src/main/kotlin")
        getByName("test").java.srcDirs("src/test/kotlin")
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    lint {
        warningsAsErrors = false
        abortOnError = true
    }
}

dependencies {
    // Nothing but the platform and the Kotlin stdlib. The parser is pure Kotlin,
    // so it is testable on the JVM without Robolectric.
    testImplementation("junit:junit:4.13.2")
}
