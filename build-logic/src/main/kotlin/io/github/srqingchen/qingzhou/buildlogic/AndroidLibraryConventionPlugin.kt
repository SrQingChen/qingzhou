package io.github.srqingchen.qingzhou.buildlogic

import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.withType
import org.gradle.api.tasks.testing.Test

class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("com.android.library")

            extensions.configure<LibraryExtension> {
                compileSdk = QzBuildConfig.compileSdk
                defaultConfig {
                    minSdk = QzBuildConfig.minSdk
                }
                compileOptions {
                    sourceCompatibility = JavaVersion.VERSION_17
                    targetCompatibility = JavaVersion.VERSION_17
                }
            }

            // 本机 PATH 含畸形引号/全角分号条目（如 jdk-23 附近的游离 `"`），
            // 会破坏 Gradle 测试 worker 的命令行引号配对导致“找不到主类”。
            // 仅在测试 JVM 环境里清洗，不改动系统 PATH。
            tasks.withType<Test>().configureEach {
                val raw = (environment["PATH"] as? String) ?: System.getenv("PATH") ?: ""
                environment("PATH", raw.replace("\"", "").replace("；", ";"))
            }
        }
    }
}
