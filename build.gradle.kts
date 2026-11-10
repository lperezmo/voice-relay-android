// AGP 9 compiles Kotlin itself (built-in Kotlin) and brings in the Kotlin Gradle
// plugin it needs. Its floor is older than the Kotlin this app is written against,
// so the Kotlin version is pinned here.
buildscript {
    repositories {
        mavenCentral()
    }
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.3.21")
    }
}

plugins {
    id("com.android.application") version "9.5.1" apply false
}
