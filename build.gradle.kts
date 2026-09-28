import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.aethernova.nova"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.aethernova.nova"
        minSdk = 28
        targetSdk = 36
        versionCode = 12
        versionName = "1.0.1"
        ndk { abiFilters += listOf("arm64-v8a") } // all current phones incl. OPPO A6 Pro 5G (Dimensity 6300); keeps the APK small
    }

    // One fixed key for every build, so each new APK installs as an update over the old one
    // (same key = your data is kept). Back up keystore/nova-signing.keystore — lose it and
    // future versions can't update the installed app.
    signingConfigs {
        create("nova") {
            storeFile = rootProject.file("keystore/nova-signing.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        // Google Play upload key. Its passwords live in keystore/upload.properties (never committed).
        val up = rootProject.file("keystore/upload.properties")
        if (up.exists()) create("upload") {
            val p = Properties().apply { up.inputStream().use { load(it) } }
            storeFile = rootProject.file(p.getProperty("storeFile"))
            storePassword = p.getProperty("storePassword")
            keyAlias = p.getProperty("keyAlias")
            keyPassword = p.getProperty("keyPassword")
        }
    }

    // play = Google Play build (no self-updater, Play policy); full = sideloaded APK with GitHub updates
    flavorDimensions += "store"
    productFlavors {
        create("play") {
            dimension = "store"
            buildConfigField("boolean", "SELF_UPDATE", "false")
            signingConfig = signingConfigs.findByName("upload") ?: signingConfigs.getByName("nova")
        }
        create("full") {
            dimension = "store"
            buildConfigField("boolean", "SELF_UPDATE", "true")
            signingConfig = signingConfigs.getByName("nova")
        }
    }
    buildFeatures { buildConfig = true }

    // Compress native libraries inside the APK (smaller download; unpacked once at install).
    packaging {
        jniLibs { useLegacyPackaging = true }
    }

    androidResources {
        noCompress += "task"
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("nova")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    sourceSets {
        // Room schema JSONs for the migration tests (debug builds only; not in the release APK)
        getByName("debug").assets.srcDir("$projectDir/schemas")
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all { it.systemProperty("roborazzi.test.record", "true") }
    }
    buildFeatures {
        compose = true
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("androidx.fragment:fragment-ktx:1.8.5") // play-services pulls an old Fragment that breaks permission results
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

    // Encrypted local database
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    implementation("net.zetetic:sqlcipher-android:4.6.1@aar")
    implementation("androidx.sqlite:sqlite:2.4.0")

    // Reminders
    implementation("androidx.work:work-runtime-ktx:2.10.0")

    // Steps + sleep
    implementation("androidx.health.connect:connect-client:1.1.0-alpha10")

    // Play mode: camera + on-device hand gestures
    implementation("androidx.camera:camera-camera2:1.4.1")
    implementation("androidx.camera:camera-lifecycle:1.4.1")
    implementation("androidx.camera:camera-view:1.4.1")
    implementation("com.google.mediapipe:tasks-vision:0.10.26.1")

    // Barcode scanner UI from Google Play services (no camera permission needed, tiny download)
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")

    // On-device wake word ("Jarvis" built in, or your own "Hey Nova" from Picovoice Console)
    implementation("ai.picovoice:porcupine-android:3.0.2")

    // Gemini REST calls
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303") // real org.json for JVM tests (Android stubs throw)
    // Screenshot tests (render screens on the JVM, no phone needed)
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.room:room-testing:2.6.1")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("androidx.test:runner:1.6.2")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.39.0")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.39.0")
}
