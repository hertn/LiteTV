plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "app.iptv"
    compileSdk = 34
    defaultConfig { applicationId = "app.iptv"; minSdk = 21; targetSdk = 34; versionCode = 10; versionName = "1.4.3" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_1_8; targetCompatibility = JavaVersion.VERSION_1_8 }
    kotlinOptions { jvmTarget = "1.8" }

}
dependencies {
    implementation("androidx.media3:media3-exoplayer:1.3.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.3.1")
    implementation("androidx.media3:media3-ui:1.3.1")
    implementation("com.google.zxing:core:3.5.3")
    implementation("androidx.core:core:1.13.1")
}
