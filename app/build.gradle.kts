plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

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
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
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
