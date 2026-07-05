import java.util.Properties
import java.io.FileInputStream

plugins {
    alias(libs.plugins.android.application)
}

val appProps = Properties().apply {
    load(FileInputStream(rootProject.file("app.properties")))
}
val appName: String = appProps.getProperty("app.name")
val appPackage: String = appProps.getProperty("app.package")

// versionCode/versionName are normally passed in from CI as project properties
// (-PappVersionCode=... -PappVersionName=...), derived from the run number and
// release tag respectively. Falls back to app.properties for local/dev builds
// where nobody passes those flags — versionCode 1 is fine locally since it's
// never uploaded anywhere.
val ciVersionCode = (project.findProperty("appVersionCode") as String?)?.toIntOrNull()
val ciVersionName = project.findProperty("appVersionName") as String?

android {
    namespace = appPackage
    compileSdk = 36

    defaultConfig {
        applicationId = appPackage
        minSdk = 26
        targetSdk = 36
        versionCode = ciVersionCode ?: 1
        versionName = ciVersionName ?: error("appVersionName must be set via -PappVersionName")
        resValue("string", "app_name", appName)
    }

    buildTypes {
        debug {
            isDebuggable = true
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.create("release") {
                storeFile = file("${System.getProperty("user.home")}/.android/debug.keystore")
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        resValues = true
    }

    // Bundle the strudel dist into assets
    sourceSets {
        getByName("main") {
            assets.srcDirs("src/main/assets")
        }
    }
}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    // implementation(libs.okhttp) // temporarily disabled to bisect the release-build crash
}
