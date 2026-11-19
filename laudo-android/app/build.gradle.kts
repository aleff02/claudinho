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
    // Chave fixa guardada nos Secrets do GitHub (não fica no repositório).
    // Todas as versões saem com a mesma assinatura, então o Android aceita atualizar por cima.
    val ks = System.getenv("LAUDO_KEYSTORE")
    val ksPass = System.getenv("LAUDO_KEY_PASS")
    if (ks != null && ksPass != null) {
        signingConfigs {
            create("laudo") {
                storeFile = file(ks)
                storePassword = ksPass
                keyAlias = "laudo"
                keyPassword = ksPass
            }
        }
        buildTypes {
            getByName("debug") { signingConfig = signingConfigs.getByName("laudo") }
            getByName("release") { signingConfig = signingConfigs.getByName("laudo") }
        }
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
