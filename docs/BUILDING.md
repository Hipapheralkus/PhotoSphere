# Build requirements

The project pins stable versions rather than resolving changing version ranges. These toolchain versions were checked against official repositories on 2 November 2026:

| Component | Pinned version |
| --- | --- |
| Gradle wrapper | 9.8.2 |
| Android Gradle Plugin | 9.5.0 |
| Kotlin compiler / Gradle plugin | 2.4.21, using AGP's built-in Kotlin integration |
| Compile SDK | Android 17, API 37.2 |
| Target SDK / minimum SDK | 37 / 30 |
| Android SDK Build Tools | 37.0.0 |
| Android NDK | 30.0.16248370, r30 LTS |
| SDK-distributed CMake | 4.1.2 |
| Build JVM | Temurin JDK 27; Java and Kotlin bytecode target remains 17 |

Install Android command-line tools 23.0 or newer. They include the Android CLI; the older `sdkmanager` command forwards to it. With the command-line tools' `bin` directory on `PATH`, install the exact native toolchain and platform:

```text
android --no-metrics sdk install platforms/android-37.2 build-tools/37.0.0 ndk/30.0.16248370 cmake/4.1.2
```

Set `ANDROID_HOME`, or set `sdk.dir` in the untracked `local.properties`. Set `JAVA_HOME` to the build JDK. The local Windows toolchain uses a private `.tools/jdk-27+35` installation, verified against Adoptium's published SHA-256; it does not change Android Studio's JBR or system Java. On the audit date, the [Adoptium Windows x64 GA API](https://api.adoptium.net/v3/assets/latest/27/hotspot?architecture=x64&image_type=jdk&os=windows) still supplied 27+35; the newer 27.0.1 release did not yet contain a Windows x64 JDK asset. CI requests the current Temurin 27 patch for Linux. [Gradle supports running on Java 27 from version 9.8](https://docs.gradle.org/current/userguide/compatibility.html).

Run `gradlew.bat assembleDebug testDebugUnitTest lintDebug` on Windows, or `./gradlew assembleDebug testDebugUnitTest lintDebug` on Linux/macOS. Build results are recorded separately in [VALIDATION.md](VALIDATION.md); a version update alone does not establish that validation passed.

[AGP 9.5 requires Gradle 9.6 or newer and JDK 17 or newer](https://developer.android.com/build/releases/agp-9-5-0-release-notes). The root build file follows Google's [documented KGP dependency override](https://developer.android.com/build/releases/agp-9-0-0-release-notes#upgrade-to-a-higher-kgp-version), because updating AGP alone leaves its older Kotlin dependency in place. [Kotlin 2.4.21's published fully tested range](https://kotlinlang.org/docs/gradle-configure-project.html) ends at Gradle 9.7.0 and AGP 9.3.1; JetBrains permits later releases, but this newer combination requires the project's own build, unit and lint checks.

[Google's stable SDK repository](https://dl.google.com/android/repository/repository2-3.xml) supplies API 37.2, Build Tools 37.0.0, NDK 30 and CMake 4.1.2. CMake 4.1.2 is the newest SDK-managed version; [upstream CMake 4.4.5](https://cmake.org/download/) is newer and is not selected by this project. The [Gradle release API](https://services.gradle.org/versions/current) identifies 9.8.2, and both the wrapper JAR and distribution are checked against Gradle's official SHA-256 checksums. The runtime libraries and licenses are listed in [THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md).
