plugins {
    id("com.android.application")
}

android {
    namespace = "com.codexapp.mobile"
    compileSdk = 34

    packaging {
        jniLibs {
            useLegacyPackaging = true
            keepDebugSymbols += setOf("**/libcodex_bin.so")
        }
    }

    defaultConfig {
        applicationId = "com.codexapp.mobile"
        minSdk = 26
        targetSdk = 34
        versionCode = 10
        versionName = "1.0.90"
    }
}
