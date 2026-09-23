plugins {
    id("qingzhou.android.library")
}

android {
    namespace = "io.github.srqingchen.qingzhou.core.crypto"
}

dependencies {
    api(project(":core:common"))
    api(libs.bouncycastle.provider)
    api(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
}
