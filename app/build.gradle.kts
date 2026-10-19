plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.taajaveli.homebase"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.taajaveli.homebase"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "1.1"
    }

    // One fixed key, committed with the project, so every build can install
    // over the last one. (A launcher you have to uninstall to update is a pain.)
    signingConfigs {
        create("bundled") {
            storeFile = file("homebase.keystore")
            storePassword = "homebase"
            keyAlias = "homebase"
            keyPassword = "homebase"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.getByName("bundled")
        }
        debug {
            signingConfig = signingConfigs.getByName("bundled")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

// No dependencies on purpose: no AndroidX, no analytics, no ad SDKs.
