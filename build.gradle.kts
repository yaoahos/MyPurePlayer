buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        // AGP 9 起 Kotlin 由 AGP 内置，不再 apply org.jetbrains.kotlin.android。
        // 但 Compose / 序列化这类编译器插件仍按 KGP 的插件接口工作，
        // 所以把 KGP 钉到与下面插件一致的版本，避免内置 Kotlin 版本与插件版本错配。
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.21")
    }
}

plugins {
    id("com.android.application") version "9.5.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.21" apply false
}
