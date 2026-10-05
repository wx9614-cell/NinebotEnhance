import java.util.Properties

plugins { id("com.android.application") }
val releaseVersion = Properties().apply { rootProject.file("version.properties").inputStream().use { load(it) } }
android {
    namespace = "dev.ichinomiya.ninebotenhance"
    compileSdk { version = release(36) { minorApiLevel = 1 } }
    buildToolsVersion = "37.0.0"
    defaultConfig {
        applicationId = "dev.ichinomiya.ninebotenhance"
        minSdk = 30
        targetSdk = 36
        versionCode = releaseVersion.getProperty("versionCode").toInt()
        versionName = releaseVersion.getProperty("versionName")
    ｝
    lint {
        abortOnError = false
    }
testOptions {
    unitTests.all {
        it.enabled = false
    }
    ｝
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    buildTypes { release { isMinifyEnabled = false } }
    // About reads our complete attribution notice from the APK; AGP excludes this name by default.
    packaging { resources.excludes.remove("/META-INF/NOTICE.txt") }
}
dependencies {
    compileOnly(files("../libs/libxposed-api-101.0.1.jar", "../libs/androidx-annotation-1.3.0.jar"))
    implementation(files("../libs/shizuku-api-13.1.5.aar", "../libs/shizuku-aidl-13.1.5.aar",
        "../libs/shizuku-shared-13.1.5.aar", "../libs/shizuku-provider-13.1.5.aar"))
}
