plugins {
    alias(libs.plugins.kotlin.jvm)
}

// 修复依据(Release run 39016400720):纯 JVM 模块在 CI JDK 21 下 compileJava 默认 21,
// 而 Kotlin 侧设为 17,触发 "Inconsistent JVM Target Compatibility" 校验失败。
// 两侧都显式钉在 17,与 android 模块的 compileOptions 保持一致。
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    testImplementation(libs.junit)
}
