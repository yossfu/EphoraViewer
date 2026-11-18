plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.ephora.viewer"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.ephora.viewer"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")

    // Filament 1.77.3 — motor de render PBR de Google.
    // Verificado en Maven Central el 2026-11-18: los tres artefactos existen
    // en la versión 1.77.3 (también la fija SceneView 4.67.0 en su POM).
    implementation("com.google.android.filament:filament-android:1.77.3")
    // filament-utils-android aún no se usa en el código de M0; queda declarado
    // para el manipulador de órbita (com.google.android.filament.utils.Manipulator)
    // previsto en hitos posteriores.
    implementation("com.google.android.filament:filament-utils-android:1.77.3")
    implementation("com.google.android.filament:gltfio-android:1.77.3")
}
