plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
import java.util.Properties

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "app.agentichydra"
    compileSdk = 35
    defaultConfig {
        applicationId = "app.agentichydra"
        minSdk = 26
        targetSdk = 35
        versionCode = 4
        versionName = "1.3.0"
    }
    signingConfigs {
        create("release") {
            storeFile = file(localProps.getProperty("hydra.keystore", System.getenv("HYDRA_KEYSTORE") ?: "../hydra-release.keystore"))
            storePassword = localProps.getProperty("hydra.storePassword", System.getenv("HYDRA_STORE_PASSWORD") ?: "hydra-preview")
            keyAlias = localProps.getProperty("hydra.keyAlias", "hydra")
            keyPassword = localProps.getProperty("hydra.keyPassword", localProps.getProperty("hydra.storePassword", System.getenv("HYDRA_STORE_PASSWORD") ?: "hydra-preview"))
        }
    }
    buildTypes { release { isMinifyEnabled = false; signingConfig = signingConfigs.getByName("release") } }
    buildFeatures { compose = true }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all { test ->
            // Isolate Robolectric's native windows and Compose clocks between UI classes.
            test.forkEvery = 1
            listOf("http.proxyHost", "http.proxyPort", "https.proxyHost", "https.proxyPort").forEach { key ->
                System.getProperty(key)?.let { test.systemProperty(key, it) }
            }
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.compose.material3:material3")
    implementation("com.google.android.material:material:1.13.0")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jsoup:jsoup:1.18.3")
    implementation("androidx.documentfile:documentfile:1.0.1")
    // WorkManager 2.10 supports this project's compileSdk 35 toolchain.
    //noinspection GradleDependency
    implementation("androidx.work:work-runtime-ktx:2.10.1")
    implementation("io.github.rosemoe:editor:0.23.7")
    implementation("io.github.rosemoe:language-textmate:0.23.7")
    implementation("io.noties.markwon:core:4.6.2")
    implementation("io.noties.markwon:ext-tables:4.6.2")
    implementation("io.noties.markwon:ext-strikethrough:4.6.2")
    implementation("io.noties.markwon:ext-tasklist:4.6.2")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.robolectric:robolectric:4.14.1")
    //noinspection GradleDependency
    testImplementation("androidx.work:work-testing:2.10.1")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
