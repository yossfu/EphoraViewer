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

    // Filament 1.56.0 — motor de render PBR de Google.
    // Bajado de 1.77.3 (run #1): 1.77.3 exige compilar contra API 37 o
    // superior, y el proyecto usa compileSdk 34 con AGP 8.1.4. La 1.56.0
    // es de noviembre de 2024, época de API 34; si el run #2 sigue
    // pidiendo más API, habrá que subir el toolchain en vez de Filament.
    implementation("com.google.android.filament:filament-android:1.56.0")
    // filament-utils-android aún no se usa en el código de M0; queda declarado
    // para el manipulador de órbita (com.google.android.filament.utils.Manipulator)
    // previsto en hitos posteriores.
    implementation("com.google.android.filament:filament-utils-android:1.56.0")
    implementation("com.google.android.filament:gltfio-android:1.56.0")
}
