plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

val configuredVersionCode = providers.gradleProperty("versionCode")
    .map { value ->
        value.toIntOrNull()?.takeIf { it > 0 }
            ?: error("versionCode must be a positive integer.")
    }
    .getOrElse(1)
val configuredVersionName = providers.gradleProperty("versionName").getOrElse("1.0")

val releaseKeystorePath = System.getenv("RELEASE_KEYSTORE_PATH")
val releaseStorePassword = System.getenv("RELEASE_STORE_PASSWORD")
val releaseKeyAlias = System.getenv("RELEASE_KEY_ALIAS")
val releaseKeyPassword = System.getenv("RELEASE_KEY_PASSWORD")
val releaseSigningValues = listOf(
    releaseKeystorePath,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
)
val releaseSigningConfigured = releaseSigningValues.all { !it.isNullOrBlank() }

if (releaseSigningValues.any { !it.isNullOrBlank() } && !releaseSigningConfigured) {
    error("Release signing requires RELEASE_KEYSTORE_PATH, RELEASE_STORE_PASSWORD, RELEASE_KEY_ALIAS, and RELEASE_KEY_PASSWORD.")
}

android {
    namespace = "cr.micampus.app"
    compileSdk = 37
    defaultConfig { applicationId = "cr.micampus.app"; minSdk = 26; targetSdk = 36; versionCode = configuredVersionCode; versionName = configuredVersionName; testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" }
    signingConfigs {
        if (releaseSigningConfigured) {
            create("release") {
                storeFile = file(requireNotNull(releaseKeystorePath))
                storePassword = requireNotNull(releaseStorePassword)
                keyAlias = requireNotNull(releaseKeyAlias)
                keyPassword = requireNotNull(releaseKeyPassword)
            }
        }
    }
    buildTypes {
        getByName("release") {
            if (releaseSigningConfigured) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    buildFeatures { compose = true; buildConfig = true }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}
kotlin { jvmToolchain(17) }
dependencies {
    implementation(libs.androidx.core.ktx); implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom)); implementation(libs.bundles.compose)
    implementation(libs.androidx.navigation.compose); implementation(libs.androidx.room.runtime); implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.datastore); implementation(libs.androidx.work.runtime); implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.lifecycle.viewmodel.compose); implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.compose.material3.adaptive)
    implementation(libs.gson)
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.mlkit.text)
    implementation(libs.genai.prompt)
    ksp(libs.androidx.room.compiler)
    ksp(libs.genai.schema.compiler)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.androidx.work.testing)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.ext.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
