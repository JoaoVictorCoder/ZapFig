import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Assinatura: keystore.properties na raiz (NÃO vai pro Git) ou variáveis de ambiente (CI).
val ksProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun signProp(key: String, env: String): String? = ksProps.getProperty(key) ?: System.getenv(env)

android {
    namespace = "com.zapfig.app"
    compileSdk = 35
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "com.zapfig.app"
        minSdk = 28
        targetSdk = 35
        versionCode = 2
        versionName = "1.0.0"
        buildConfigField("String", "UPDATE_REPO", "\"JoaoVictorCoder/ZapFig\"")
        externalNativeBuild {
            cmake { arguments += listOf("-DANDROID_STL=none") }
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = true
        }
    }

    signingConfigs {
        create("release") {
            val store = signProp("storeFile", "ZAPFIG_KEYSTORE")
            if (store != null) {
                storeFile = rootProject.file(store)
                storePassword = signProp("storePassword", "ZAPFIG_KEYSTORE_PASSWORD")
                keyAlias = signProp("keyAlias", "ZAPFIG_KEY_ALIAS")
                keyPassword = signProp("keyPassword", "ZAPFIG_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (signProp("storeFile", "ZAPFIG_KEYSTORE") != null)
                signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildFeatures { compose = true; buildConfig = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    packaging { jniLibs { useLegacyPackaging = false } }
}

// Nomes de arquivo bonitos: ZapFig-1.0.0-arm64-v8a.apk etc.
android.applicationVariants.all {
    outputs.all {
        val o = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
        val abi = o.getFilter(com.android.build.OutputFile.ABI) ?: "universal"
        o.outputFileName = "ZapFig-${versionName}-$abi.apk"
    }
}

dependencies {
    // Motor em Go (whatsmeow) gerado com gomobile bind (ver README)
    implementation(files("libs/engine.aar"))

    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
