plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Постоянный ключ подписи приходит из CI (секреты GitHub, см. README → «Подпись»).
// Без него сборка подписывается случайным debug-ключом, и обновить приложение поверх
// предыдущей версии нельзя (INSTALL_FAILED_UPDATE_INCOMPATIBLE).
val ciKeystore: String? = System.getenv("SIGNING_KEYSTORE_FILE")
    ?.takeIf { it.isNotBlank() && file(it).exists() }

android {
    namespace = "com.dostupvpn.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.dostupvpn.app"
        minSdk = 26
        targetSdk = 35
        // Должен только расти, иначе Android откажется ставить «более старую» версию поверх новой.
        // Минуты с 1970 года: растёт монотонно и не зависит от счётчика запусков CI.
        versionCode = (System.currentTimeMillis() / 60_000L).toInt()
        versionName = "1.0.0-beta." + (System.getenv("GITHUB_RUN_NUMBER") ?: "0")
        // Ядро Xray ужимается в CI до arm64 — другие ABI не нужны.
        ndk { abiFilters += "arm64-v8a" }
    }

    signingConfigs {
        if (ciKeystore != null) {
            create("dostup") {
                storeFile = file(ciKeystore)
                storePassword = System.getenv("SIGNING_STORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            if (ciKeystore != null) signingConfig = signingConfigs.getByName("dostup")
        }
        release {
            // R8 выключен осознанно: выигрыш ~2–3 МБ не стоит риска падений, которые нельзя проверить
            // без реального устройства. Включим после периода бета-тестирования.
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName(if (ciKeystore != null) "dostup" else "debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    // Xray-core для Android (2dust/AndroidLibXrayLite). Скачивается и ужимается шагом CI
    // (см. .github/workflows/main.yml): оставляется только arm64, без geo-файлов.
    implementation(files("libs/libv2ray.aar"))

    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
