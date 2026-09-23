package io.github.srqingchen.qingzhou.buildlogic

import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/** 为任意 Android 模块启用 Compose（Kotlin 2.x compose compiler 插件）。 */
class AndroidComposeConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("org.jetbrains.kotlin.plugin.compose")

            plugins.withId("com.android.application") {
                extensions.configure<ApplicationExtension> {
                    buildFeatures { compose = true }
                }
            }
            plugins.withId("com.android.library") {
                extensions.configure<LibraryExtension> {
                    buildFeatures { compose = true }
                }
            }
        }
    }
}
