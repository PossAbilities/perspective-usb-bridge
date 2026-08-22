plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "uk.co.perspectivestudio.usbbridge"
    compileSdk = 36
    defaultConfig { applicationId = "uk.co.perspectivestudio.usbbridge"; minSdk = 26; targetSdk = 36; versionCode = 6; versionName = "0.6.0" }
    buildFeatures { compose = true }
}
dependencies {
    implementation("androidx.core:core-ktx:1.16.0")
    implementation(platform("androidx.compose:compose-bom:2025.05.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
