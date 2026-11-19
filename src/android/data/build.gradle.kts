import java.util.Properties

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

// [perf 2026-11-19] The Room layer and the models it stores, as their own
// module.
//
// Why: the app is one module with 875 Kotlin files, so every change made
// Room's KSP processor re-analyze the whole module (6.7 s on an incremental
// build, 24.7 s on a cold one) even when no entity, DAO or query was
// touched. With the database in its own module, KSP runs over 36 files
// instead, and only when one of them changes.
//
// The package names are unchanged (com.openminis.app.data.db /
// com.openminis.app.data.model), so nothing in the app had to be edited to
// move the code here.
android {
    namespace = "com.openminis.app.data"

    compileSdk = 36

    defaultConfig {
        // The app's floor; the library must not raise it.
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

// The exported schema JSON is COMMITTED (see the app module's copy of this
// note): MigrationTestHelper replays it to prove every upgrade - and the
// no-op downgrade - still produces the schema the entities expect.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    testImplementation("junit:junit:4.13.2")
}
