plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "cr.micampus.app"
    compileSdk = 36
    defaultConfig { applicationId = "cr.micampus.app"; minSdk = 26; targetSdk = 36; versionCode = 1; versionName = "1.0"; testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" }
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
    implementation(libs.androidx.compose.material3.adaptive); implementation(libs.androidx.compose.material3.navigation.suite)
    implementation(libs.gson)
    implementation(libs.mlkit.text)
    implementation(libs.genai.prompt)
    ksp(libs.androidx.room.compiler)
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
