plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "br.movida.laudo"
    compileSdk = 35

    defaultConfig {
        applicationId = "br.movida.laudo"
        minSdk = 26
        targetSdk = 35
        // número da compilação no GitHub Actions = versão (o app usa isso para saber se há atualização)
        val build = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionCode = build
        versionName = "1.$build"
    }
    // chave fixa: todas as versões saem com a mesma assinatura, então o Android aceita atualizar por cima
    signingConfigs {
        create("laudo") {
            storeFile = file("laudo.keystore")
            storePassword = "laudoavaria"
            keyAlias = "laudo"
            keyPassword = "laudoavaria"
        }
    }
    buildTypes {
        getByName("debug") { signingConfig = signingConfigs.getByName("laudo") }
        getByName("release") { signingConfig = signingConfigs.getByName("laudo"); isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
}
