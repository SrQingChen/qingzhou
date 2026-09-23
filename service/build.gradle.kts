plugins {
    id("qingzhou.android.library")
}

android {
    namespace = "io.github.srqingchen.qingzhou.service"
}

dependencies {
    api(project(":core:model"))
    api(project(":core:network"))
    implementation(project(":core:common"))
    implementation(project(":core:shizuku"))
    implementation(project(":core:data"))
    implementation(project(":core:designsystem"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.kotlinx.coroutines.android)
}
