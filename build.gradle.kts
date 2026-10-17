// Top-level build file where you can add configuration options common to all sub-projects/modules.
// 修复原因：子模块按 alias 引用插件时，根工程必须先以 apply false 把全部插件钉在同一版本，
// 否则 app 模块解析 kotlin.android 时报 "already on the classpath with an unknown version"。
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.ksp) apply false
}