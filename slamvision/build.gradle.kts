plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.taajaveli.slamvision"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.taajaveli.slamvision"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        create("bundled") {
            storeFile = file("../app/homebase.keystore")
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
