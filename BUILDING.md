# Building Soneme Recorder

Soneme Recorder targets Android 11 (API 30) on the Sonim XP3900 and compiles against Android API 34.

Requirements:

- JDK 17 or newer
- Android SDK platform 34 / build-tools
- Android NDK 27.0.12077973 and CMake 3.22.1 (the Android SDK manager can install these)
- Git (the native CMake build fetches the Android-adjusted LAME 3.100 source tree on first configure)
- `curl` or `wget`, plus `unzip`, if Gradle 8.9 is not already bootstrapped

Build a debug APK:

```sh
./gradlew assembleDebug
```

The first build needs internet access for Gradle/Android dependencies and the LAME source fetch.

For a signed release, use the same environment variables as the other Soneme apps:

```sh
export SONEME_KEYSTORE=/path/to/keystore.jks
export SONEME_STORE_PASSWORD='...'
export SONEME_KEY_PASSWORD='...'
./gradlew assembleRelease
```

The configured key alias is `soneme`.
