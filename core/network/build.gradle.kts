plugins {
    id("qingzhou.android.library")
}

android {
    namespace = "io.github.srqingchen.qingzhou.core.network"
}

dependencies {
    api(project(":core:model"))
    api(project(":core:common"))
    api(project(":core:crypto"))
    api(project(":core:data"))
    api(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.annotation)
}
