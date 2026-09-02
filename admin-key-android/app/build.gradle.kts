plugins {
    id("com.android.application")
}

android {
    namespace = "com.trungkien.licenseadmin"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.trungkien.licenseadmin"
        minSdk = 24
        targetSdk = 36
        versionCode = 1301
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
