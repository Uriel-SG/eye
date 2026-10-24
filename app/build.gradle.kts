import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "app.eye.android"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.urielsec.eye"
        minSdk = 26
        targetSdk = 36
        versionCode = 14
        versionName = "1.8.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Simboli nativi (librerie ML Kit) nell'AAB: Play Console li usa per leggere crash/ANR.
        ndk { debugSymbolLevel = "SYMBOL_TABLE" }
    }

    // Chiave di upload FUORI dal repo: percorso da -PeyeKeystoreProps o variabile EYE_KEYSTORE_PROPS
    // (default keystore.properties, ignorato da git). Se manca, la release resta non firmata.
    val keystoreProps = (findProperty("eyeKeystoreProps") as String?)
        ?: System.getenv("EYE_KEYSTORE_PROPS")
        ?: "keystore.properties"
    val ksFile = file(keystoreProps)
    if (ksFile.exists()) {
        val p = Properties().apply { ksFile.inputStream().use { load(it) } }
        signingConfigs {
            create("upload") {
                storeFile = file(p.getProperty("storeFile"))
                storePassword = p.getProperty("storePassword")
                keyAlias = p.getProperty("keyAlias")
                keyPassword = p.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug" // coesiste con la release firmata sul telefono di prova
            versionNameSuffix = "-debug"
            // Solo debug: server locale per provare i feed (adb reverse tcp:8000 tcp:8000).
            buildConfigField("String", "FEED_BASE_OVERRIDE", "\"" + ((findProperty("eyeFeedBase") as String?) ?: "http://localhost:8000/data/") + "\"")
        }
        release {
            buildConfigField("String", "FEED_BASE_OVERRIDE", "\"\"")
            if (ksFile.exists()) signingConfig = signingConfigs.getByName("upload")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    splits {
        abi {
            // L'AAB per Play non supporta gli split: attivi solo se non si sta costruendo un bundle.
            isEnable = gradle.startParameter.taskNames.none { it.contains("bundle", ignoreCase = true) }
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = false
        }
    }
    // Il modello ONNX (~35 MB) resta non compresso nell'APK: si legge direttamente dal file.
    androidResources { noCompress += "onnx" }
    buildFeatures { compose = true; buildConfig = true }
    testOptions { unitTests.isReturnDefaultValues = true }
}


dependencies {
    implementation(project(":engine"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.work.runtime)
    implementation(libs.zxing.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.mlkit.text)
    implementation(libs.mlkit.image.labeling) // etichette dell'immagine sul telefono (modello incluso nell'app, nessuna rete)
    // ML Kit vision-internal-vkp porta okhttp 3.0.0 (2016, CVE) e ne usa le classi all'avvio (escluderlo manda in crash): forziamo una 4.x corretta
    constraints {
        implementation("com.squareup.okhttp3:okhttp:4.12.0") { because("okhttp 3.0.0 transitivo di ML Kit ha vulnerabilita' note") }
    }
    implementation(libs.mlkit.genai.prompt)
    implementation(libs.play.app.update.ktx)
    implementation(libs.onnxruntime.android)
    ksp(libs.androidx.room.compiler)
    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}
