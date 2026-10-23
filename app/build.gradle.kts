plugins { id("com.android.application") }

android {
    namespace = "app.photosphere"
    compileSdk { version = release(37) }
    defaultConfig {
        applicationId = "app.photosphere"
        minSdk = 30
        targetSdk = 37
        versionCode = 2
        versionName = "0.1.1"
        testInstrumentationRunner = providers.gradleProperty("testRunner")
            .orElse("app.photosphere.DeviceSmokeInstrumentation").get()
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}

