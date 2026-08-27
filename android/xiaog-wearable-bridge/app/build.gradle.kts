plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android { namespace = "dev.linjian.wearablebridge"; compileSdk = 35
    defaultConfig { applicationId = "dev.linjian.wearablebridge"; minSdk = 26; targetSdk = 35; versionCode = 1; versionName = "0.1.0" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("androidx.health.connect:connect-client:1.1.0")
    implementation("androidx.security:security-crypto:1.1.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
