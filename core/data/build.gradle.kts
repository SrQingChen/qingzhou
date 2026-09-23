plugins {
    id("qingzhou.android.library")
}

android {
    namespace = "io.github.srqingchen.qingzhou.core.data"
}

dependencies {
    api(project(":core:model"))
    api(project(":core:common"))
    api(libs.kotlinx.coroutines.core)
}
