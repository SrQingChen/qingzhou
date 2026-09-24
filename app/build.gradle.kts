plugins {
    id("qingzhou.android.application")
    id("qingzhou.android.compose")
}

android {
    namespace = "io.github.srqingchen.qingzhou.app"

    defaultConfig {
        applicationId = "io.github.srqingchen.qingzhou"
        versionCode = 2
        versionName = "0.2.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"

        // BouncyCastle：精简 JAR 内的签名信息，避免与系统冲突
        resources.excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
    }
}

dependencies {
    implementation(project(":feature:home"))
    implementation(project(":service"))
    implementation(project(":core:network"))
    implementation(project(":core:crypto"))
    implementation(project(":core:shizuku"))
    implementation(project(":core:data"))
    implementation(project(":core:designsystem"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    coreLibraryDesugaring(libs.desugar.jdk.libs)
}
