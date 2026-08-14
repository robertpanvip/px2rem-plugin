import org.jetbrains.changelog.Changelog
import org.jetbrains.changelog.markdownToHTML

fun properties(key: String) = project.findProperty(key).toString()

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "1.9.10"
    id("org.jetbrains.intellij") version "1.17.1"
    id("org.jetbrains.changelog") version "2.2.0"
}

group = properties("pluginGroup")
version = properties("pluginVersion")

repositories {
    // 真实 URL 会由 _local_init.gradle.kts 在 afterEvaluate 阶段替换为腾讯云镜像 + 追加 JetBrains releases/snapshots
    mavenCentral()
    gradlePluginPortal()
}

intellij {
    pluginName.set(properties("pluginName"))
    version.set(properties("platformVersion"))
    type.set(properties("platformType"))
    downloadSources.set(properties("platformDownloadSources").toBoolean())
    updateSinceUntilBuild.set(true)
    plugins.set(listOf(
        "JavaScript",
        "com.intellij.css"
    ))
}

changelog {
    groups.empty()
    repositoryUrl.set("https://github.com/reactunitconverter/plugin")
}

tasks {
    properties("javaVersion").let {
        withType<JavaCompile> {
            sourceCompatibility = it
            targetCompatibility = it
        }
        withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
            kotlinOptions.jvmTarget = it
            kotlinOptions.freeCompilerArgs = listOf("-Xjvm-default=all")
        }
    }

    wrapper {
        gradleVersion = properties("gradleVersion")
    }

    patchPluginXml {
        version.set(properties("pluginVersion"))
        sinceBuild.set("232")
        untilBuild.set("242.*")

        pluginDescription.set(
            file("DESCRIPTION.md").takeIf { it.exists() }?.readText()?.let { markdownToHTML(it) }
                ?: "A JetBrains IDE plugin for converting px to rem/vw in React inline styles and extracting inline styles to CSS Modules."
        )

        changeNotes.set(
            provider {
                with(changelog) {
                    renderItem(
                        getOrNull(properties("pluginVersion")) ?: runCatching { getLatest() }.getOrElse { getUnreleased() },
                        Changelog.OutputType.HTML
                    )
                }
            }
        )
    }

    signPlugin {
        certificateChain.set(System.getenv("CERTIFICATE_CHAIN"))
        privateKey.set(System.getenv("PRIVATE_KEY"))
        password.set(System.getenv("PRIVATE_KEY_PASSWORD"))
    }

    publishPlugin {
        dependsOn("patchChangelog")
        token.set(System.getenv("PUBLISH_TOKEN"))
        channels.set(listOf(properties("pluginVersion").split('-').getOrElse(1) { "default" }.split('.').first()))
    }

    runIde {
        jvmArgs("-Xmx2048m")
    }

    test {
        useJUnitPlatform()
    }
}

dependencies {
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("org.yaml:snakeyaml:2.2")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testImplementation("org.mockito:mockito-core:5.5.0")
}
