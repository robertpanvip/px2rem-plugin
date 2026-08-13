// 仓库策略：
//   pluginManagement 和 dependencyResolutionManagement 在此直接使用腾讯云镜像作为首选（Gradle 8.x 要求 pluginManagement/DRM 必须在 settings 中才能生效，init 脚本的 settingsEvaluated 时机在某些环境下不稳定）
//   JetBrains 专用 releases/snapshots 仓仍由根目录的 _local_init.gradle.kts 在 afterEvaluate 阶段追加（按 includeGroupByRegex 限定只服务 IntelliJ Platform SDK 相关组）
//   本地与 CI 都建议使用 init 脚本：./gradlew --init-script _local_init.gradle.kts <task>

pluginManagement {
    repositories {
        maven {
            name = "TencentMavenPublic"
            url = uri("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/")
        }
        maven {
            name = "TencentGradlePlugins"
            url = uri("https://mirrors.cloud.tencent.com/nexus/repository/gradle-plugins/")
        }
        // 兜底：腾讯镜像中同步不及时时再从官方取
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_PROJECT)
    repositories {
        maven {
            name = "TencentMavenPublicDrm"
            url = uri("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/")
        }
        maven {
            name = "TencentGradlePluginsDrm"
            url = uri("https://mirrors.cloud.tencent.com/nexus/repository/gradle-plugins/")
        }
        maven {
            name = "TencentGoogleDrm"
            url = uri("https://mirrors.cloud.tencent.com/nexus/repository/google/")
        }
        mavenCentral()
    }
}

rootProject.name = "px2rem-plugin"
