plugins {
    id("qingzhou.android.library")
}

android {
    namespace = "io.github.srqingchen.qingzhou.core.shizuku"

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
    }
}

dependencies {
    api(libs.shizuku.api)
    implementation(project(":core:common"))
    implementation(libs.shizuku.provider)
    implementation(libs.androidx.annotation)
    coreLibraryDesugaring(libs.desugar.jdk.libs)
}
