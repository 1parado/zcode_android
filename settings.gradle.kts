pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "zcode-android"

include(":app")
include(":core:common")
// 纯 JVM 模块：RPC 二进制协议 + V4 应用协议 + 订阅归约器（可独立单元测试）
include(":core:protocol")
include(":core:network")
include(":core:design")
// 纯 JVM 模块：流式感知 Markdown 解析（块级 + 行内 + 近似代码高亮，可独立单元测试）
include(":core:markdown")
include(":feature:connect")
include(":feature:sessions")
include(":feature:chat")
include(":feature:render")
include(":feature:uuremote")
