import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Версия — в version.properties (её увеличивает build.ps1 при каждой сборке).
// Ключ подписи release — в keystore.properties (создаёт build.ps1); если файла нет, release подписывается debug-ключом.
fun loadProps(name: String) = Properties().apply {
    val f = rootProject.file(name)
    if (f.exists()) f.inputStream().use { load(it) }
}
val versionProps = loadProps("version.properties")
val appVersionCode = versionProps.getProperty("VERSION_CODE", "1").trim().toInt()
val appVersionName = versionProps.getProperty("VERSION_NAME", "1.0.0").trim()
val keystoreProps = loadProps("keystore.properties")

android {
    namespace = "ru.myt2lite"
    compileSdk = 35

    defaultConfig {
        applicationId = "ru.myt2lite"
        minSdk = 26
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName
    }

    signingConfigs {
        if (keystoreProps.getProperty("storeFile") != null) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
            // R8: выкидывает неиспользуемый код библиотек и ресурсы (APK 1.1.0 без R8 был 21,4 МБ)
            isMinifyEnabled = true
            isShrinkResources = true
            // Только для своего телефона (Pixel 7 Pro, arm64). Без этого SQLCipher из Webim кладётся ещё для armv7/x86/x86_64 (+9 МБ).
            ndk { abiFilters += listOf("arm64-v8a") }
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
    buildFeatures {
        compose = true
    }
}

// Имя APK с версией: app/build/outputs/apk/release/t2lite-1.0.4-release.apk
base {
    archivesName.set("t2lite-$appVersionName")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    // Значки нижних вкладок и пунктов меню (Icons.Filled.*), версия из Compose BOM
    implementation("androidx.compose.material:material-icons-core")
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.retrofit)
    implementation(libs.retrofit.gson)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.gson)
    implementation(libs.kotlinx.coroutines.android)
    // Чат поддержки: тот же Webim SDK, что в оригинальном «Мой t2» 6.52.0 (Maven Central)
    implementation("ru.webim.sdk:webimclientsdkandroid:3.39.0")
    // Виджет домашнего экрана (ru.myt2lite.widget): Jetpack Glance + фоновое обновление WorkManager
    implementation("androidx.glance:glance-appwidget:1.1.1")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    debugImplementation(libs.androidx.ui.tooling)
}
