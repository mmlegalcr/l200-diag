plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "cr.andres.l200diag"
    compileSdk = 34

    defaultConfig {
        applicationId = "cr.andres.l200diag"
        minSdk = 26
        targetSdk = 29
        versionCode = 4
        versionName = "0.4"

        // La librería de la dashcam solo existe en 32 bits: la app corre como proceso de 32 bits.
        ndk {
            abiFilters += "armeabi-v7a"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
