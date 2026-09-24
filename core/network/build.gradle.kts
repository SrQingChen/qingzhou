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
    // USB 网络链路需要经 Shizuku 以 shell 身份执行 svc usb（无循环依赖：shizuku 仅依赖 common）
    implementation(project(":core:shizuku"))
    api(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.annotation)
    testImplementation(libs.junit)
}
