pluginManagement {
    includeBuild("build-logic")
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    // PREFER_SETTINGS：settings 的 google/mavenCentral 优先，避免本地镜像 init 脚本
    //（仅含 aliyun public/central，缺 androidx/AGP）抢占仓库解析
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "QingZhou"

// 模块按里程碑生长：M0 首批 10 个，后续新增只需在此追加一行
include(":app")
include(":core:common")
include(":core:model")
include(":core:data")
include(":core:designsystem")
include(":core:crypto")
include(":core:network")
include(":core:shizuku")
include(":service")
include(":feature:home")
