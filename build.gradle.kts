plugins {
    id("org.jetbrains.kotlin.jvm") version "2.1.0"
    id("org.jetbrains.intellij.platform") version "2.5.0"
}

group = "com.github.kiroterm"
version = "0.1.2"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        goland("2025.1")
        bundledPlugin("org.jetbrains.plugins.terminal")
    }
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

configurations.runtimeClasspath {
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-jdk8")
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-jdk7")
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-common")
}

intellijPlatform {
    // 无自定义 Settings 页时关闭，避免每次 buildPlugin 拉起无头 IDE（EmmyLua 同配置）
    buildSearchableOptions = false
}

tasks {
    patchPluginXml {
        sinceBuild.set("233")
        untilBuild.set("999.*")
    }

    val verifyPluginZip by registering {
        dependsOn("buildPlugin")
        doLast {
            val zip = layout.buildDirectory.file("distributions/kiro-cli-jetbrains-plugin-${version}.zip").get().asFile
            check(zip.isFile) {
                "Missing plugin distribution: ${zip.absolutePath}\nRun: ./gradlew buildPlugin"
            }
            logger.lifecycle("Plugin zip ready: ${zip.absolutePath} (${zip.length()} bytes)")
        }
    }

    named("build") {
        dependsOn(verifyPluginZip)
    }

    test {
        useJUnitPlatform()
    }
}
