plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

android {
    namespace = "cc.uukanshu"
    compileSdk = 34

    defaultConfig {
        applicationId = "cc.uukanshu"
        // Locked by plan Rev.4: Android 12+ floor.
        minSdk = 31
        targetSdk = 34
        versionCode = 20
        // Single source of truth for `uukanshu-{version}.apk`.
        versionName = "1.0.19"
    }

    // Release signing: local `release.keystore` (dev key, gitignored) by
    // default; official releases override via UUKANSHU_KEYSTORE_* env.
    // Without this the APK is unsigned and Android refuses to install it
    // ("package appears to be invalid").
    signingConfigs {
        create("release") {
            val ksProp = System.getenv("UUKANSHU_KEYSTORE_FILE")
                ?: project.findProperty("UUKANSHU_KEYSTORE_FILE") as? String
            val ksFile = if (ksProp != null) file(ksProp) else rootProject.file("release.keystore")
            if (ksFile.exists()) {
                storeFile = ksFile
                storePassword = System.getenv("UUKANSHU_KEYSTORE_PASSWORD")
                    ?: (project.findProperty("UUKANSHU_KEYSTORE_PASSWORD") as? String)
                    ?: "uukanshu"
                keyAlias = System.getenv("UUKANSHU_KEY_ALIAS")
                    ?: (project.findProperty("UUKANSHU_KEY_ALIAS") as? String)
                    ?: "uukanshu"
                keyPassword = System.getenv("UUKANSHU_KEY_PASSWORD")
                    ?: (project.findProperty("UUKANSHU_KEY_PASSWORD") as? String)
                    ?: "uukanshu"
            } else {
                // Fresh clone without the local key: sign like debug so the
                // APK is at least installable (never for official releases).
                initWith(getByName("debug"))
            }
            // APK Signature Scheme v2 (supported since Android 7.0, and
            // our minSdk is 31) — this signature is what makes the APK
            // installable; unsigned builds are rejected as invalid.
            enableV2Signing = true
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    // Acceptance: build output must be `uukanshu-{version}.apk`.
    applicationVariants.all {
        val variant = this
        variant.outputs
            .map { it as com.android.build.gradle.internal.api.BaseVariantOutputImpl }
            .forEach { output ->
                output.outputFileName = "uukanshu-${variant.versionName}.apk"
            }
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.datastore)
    implementation(libs.androidx.workmanager)
    implementation(libs.okhttp)
    implementation(libs.jsoup)
    implementation(libs.opencc4j)

    ksp(libs.androidx.room.compiler)

    testImplementation(libs.junit)
}
