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
        // Single source of truth for `uukanshu-{version}.apk`.
        versionName = "1.0.34"
        // Derived (not manual) so code/name cannot drift: 1.0.34 -> 10034.
        // Monotonic from the legacy 34 (10034 > 34), so side-load updates
        // never see a downgrade. Never hand-edit versionCode.
        versionCode = run {
            val name = versionName ?: "0.0.0"
            val parts = name.split(".").map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
            val (major, minor, patch) = Triple(parts.getOrElse(0) { 0 }, parts.getOrElse(1) { 0 }, parts.getOrElse(2) { 0 })
            major * 10000 + minor * 100 + patch
        }
    }

    // Release signing: local `release.keystore` (dev key, gitignored) by
    // default; official releases override via UUKANSHU_KEYSTORE_* env.
    // Without this the APK is unsigned and Android refuses to install it
    // ("package appears to be invalid"). Fail fast when no key exists so a
    // debug-signed "release" can never masquerade as official (it would
    // require uninstall to update). Opt out explicitly for throwaway local
    // builds with -PallowDebugSigning or UUKANSHU_ALLOW_DEBUG_SIGNING=1.
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
                val allowDebug = (project.findProperty("allowDebugSigning") as? String == "true") ||
                    System.getenv("UUKANSHU_ALLOW_DEBUG_SIGNING") == "1"
                if (!allowDebug) {
                    throw GradleException(
                        "No release keystore found at ${ksFile} (or \$UUKANSHU_KEYSTORE_FILE). " +
                            "Run `mise run setup-signing` for a local dev key, export UUKANSHU_KEYSTORE_* " +
                            "for official releases, or rebuild with -PallowDebugSigning for a throwaway debug-signed APK."
                    )
                }
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
    implementation(libs.androidx.paging.runtime)
    implementation(libs.androidx.paging.compose)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.datastore)
    implementation(libs.okhttp)
    implementation(libs.jsoup)
    implementation(libs.opencc4j)

    ksp(libs.androidx.room.compiler)

    testImplementation(libs.junit)
}
