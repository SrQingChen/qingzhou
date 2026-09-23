plugins {
    id("qingzhou.android.library")
    id("qingzhou.android.compose")
}

android {
    namespace = "io.github.srqingchen.qingzhou.feature.home"
}

dependencies {
    implementation(project(":core:designsystem"))
    implementation(project(":core:common"))
    implementation(project(":core:data"))
    implementation(project(":core:model"))
    implementation(project(":core:network"))
    implementation(project(":core:shizuku"))
    implementation(project(":service"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)
    debugImplementation(libs.compose.ui.tooling)
}
