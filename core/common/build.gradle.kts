plugins {
    id("qingzhou.android.library")
}

android {
    namespace = "io.github.srqingchen.qingzhou.core.common"
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
}
