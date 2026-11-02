buildscript {
    repositories { mavenCentral() }
    dependencies {
        // Keep AGP's built-in Kotlin integration, with the current stable compiler.
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.21")
    }
}

plugins {
    id("com.android.application") version "9.5.0" apply false
}

