import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Signing: a GitHub secret (see README) takes precedence; otherwise the public key in /signing is used,
// so every build of this repository has the same signature and installs as an update over the previous one.
val keystorePath: String = System.getenv("SIGNING_STORE_FILE") ?: rootProject.file("signing/varfit-public.keystore").path
val keystorePassword: String = System.getenv("SIGNING_STORE_PASSWORD") ?: "varfit-public"
val keyAliasName: String = System.getenv("SIGNING_KEY_ALIAS") ?: "varfit"
val keyPasswordValue: String = System.getenv("SIGNING_KEY_PASSWORD") ?: keystorePassword

android {
    namespace = "com.varfit.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.varfit.app"
        minSdk = 26
        targetSdk = 35
        versionCode = (System.getenv("VERSION_CODE") ?: "1").toInt()
        versionName = System.getenv("VERSION_NAME") ?: "1.0.0-local"
    }

    signingConfigs {
        create("release") {
            storeFile = file(keystorePath)
            storePassword = keystorePassword
            keyAlias = keyAliasName
            keyPassword = keyPasswordValue
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // The app itself has no third-party dependencies.
    testImplementation("junit:junit:4.13.2")
}
