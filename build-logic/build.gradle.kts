plugins {
    `kotlin-dsl`
}

dependencies {
    compileOnly(libs.android.gradle.plugin)
    compileOnly(libs.kotlin.gradle.plugin)
    compileOnly(libs.compose.gradle.plugin)
}

gradlePlugin {
    plugins {
        register("androidApplication") {
            id = "qingzhou.android.application"
            implementationClass = "io.github.srqingchen.qingzhou.buildlogic.AndroidApplicationConventionPlugin"
        }
        register("androidLibrary") {
            id = "qingzhou.android.library"
            implementationClass = "io.github.srqingchen.qingzhou.buildlogic.AndroidLibraryConventionPlugin"
        }
        register("androidCompose") {
            id = "qingzhou.android.compose"
            implementationClass = "io.github.srqingchen.qingzhou.buildlogic.AndroidComposeConventionPlugin"
        }
    }
}
