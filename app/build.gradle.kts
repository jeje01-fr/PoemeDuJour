plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android { namespace = "fr.poemedujour.app"; compileSdk = 35
    defaultConfig { applicationId = "fr.poemedujour.app"; minSdk = 26; targetSdk = 35; versionCode = 1; versionName = "1.0" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.ui:ui:1.7.8")
    implementation("androidx.compose.material3:material3:1.3.1")
    implementation("androidx.compose.ui:ui-tooling-preview:1.7.8")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
}
