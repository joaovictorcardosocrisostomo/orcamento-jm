plugins {
    // AGP 9 já compila Kotlin sozinho; só falta o plugin do Compose
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.victor.orcamento"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.victor.orcamento"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        // celulares atuais são todos arm64
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    // Chave fixa: assim cada versão nova instala por cima da anterior
    signingConfigs {
        create("fixa") {
            storeFile = file("assinatura.jks")
            storePassword = "orcamentojm"
            keyAlias = "orcamento"
            keyPassword = "orcamentojm"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("fixa")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
}

dependencies {
    // llama.cpp empacotado para Kotlin (roda o NuExtract .gguf no celular)
    implementation("com.llamatik:core:2.4.0")

    implementation(platform("androidx.compose:compose-bom:2025.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
}

