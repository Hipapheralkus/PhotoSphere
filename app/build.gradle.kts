plugins { id("com.android.application") }

android {
    namespace = "app.photosphere"
    compileSdk { version = release(37) { minorApiLevel = 2 } }
    buildToolsVersion = "37.0.0"
    ndkVersion = "30.0.16248370"
    buildFeatures { prefab = true }
    defaultConfig {
        applicationId = "app.photosphere"
        minSdk = 30
        targetSdk = 37
        versionCode = 4
        versionName = "0.3.0"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        externalNativeBuild { cmake { arguments += "-DANDROID_STL=c++_shared" } }
        testInstrumentationRunner = providers.gradleProperty("testRunner")
            .orElse("app.photosphere.DeviceSmokeInstrumentation").get()
    }
    externalNativeBuild {
        cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "4.1.2" }
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
    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.all { it.useJUnitPlatform() }
    }
}

dependencies {
    implementation("org.opencv:opencv:5.0.0.1")
    implementation("androidx.exifinterface:exifinterface:1.4.2")
    implementation(platform("org.jetbrains.kotlin:kotlin-bom:2.4.21"))
    constraints {
        implementation("androidx.annotation:annotation:1.11.0")
        implementation("androidx.annotation:annotation-jvm:1.11.0")
        implementation("org.jetbrains:annotations:26.1.0")
        implementation("org.jspecify:jspecify:1.0.1")
    }
    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

