import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// release signing comes from keystore.properties in the repository root (git-ignored):
//   storeFile=~/.android/openexactfit-release.jks   (absolute, ~ allowed, or relative to the root)
//   storePassword=...
//   keyAlias=openexactfit
//   keyPassword=...
// without the file the release apk is built unsigned.
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

android {
    namespace = "de.cmile.openexactfit"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "de.cmile.openexactfit"
        // android 13: the first version with the non-deprecated gatt callbacks and write calls,
        // and with java.time, notification runtime permission and nearby-devices permissions built in
        minSdk = 33
        targetSdk = 37
        // bump both for every release: the code is what android compares, the name is what people see
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (keystoreProperties.isNotEmpty()) {
            val path = keystoreProperties.getProperty("storeFile").replaceFirst("~", System.getProperty("user.home"))
            val store = File(path).let { if (it.isAbsolute) it else rootProject.file(path) }
            if (store.exists()) {
                create("release") {
                    storeFile = store
                    storePassword = keystoreProperties.getProperty("storePassword")
                    keyAlias = keystoreProperties.getProperty("keyAlias")
                    keyPassword = keystoreProperties.getProperty("keyPassword")
                }
            } else {
                logger.warn("keystore.properties points at $store, which does not exist: the release apk will be unsigned")
            }
        }
    }

    buildTypes {
        release {
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
            signingConfig = signingConfigs.findByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
