plugins { id("com.android.application") }

android {
    namespace = "app.photosphere"
    compileSdk { version = release(37) }
    ndkVersion = "28.2.13676358"
    buildFeatures { prefab = true }
    defaultConfig {
        applicationId = "app.photosphere"
        minSdk = 30
        targetSdk = 37
        versionCode = 3
        versionName = "0.2.0"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        externalNativeBuild { cmake { arguments += "-DANDROID_STL=c++_shared" } }
        testInstrumentationRunner = providers.gradleProperty("testRunner")
            .orElse("app.photosphere.DeviceSmokeInstrumentation").get()
    }
    externalNativeBuild {
        cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" }
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
    implementation("org.opencv:opencv:4.13.0")
    implementation("androidx.exifinterface:exifinterface:1.4.2")
    testImplementation("junit:junit:4.13.2")
}

