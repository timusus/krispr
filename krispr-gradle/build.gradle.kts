import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    `java-gradle-plugin`
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.gradle.plugin.publish)
    id("krispr.publish")
}

description = "Krispr Gradle plugin: IR-level mutation testing for Kotlin/JVM, Android and Kotlin Multiplatform"

// KRISPR_VERSION comes from `version`, so the plugin always asks for the compiler and runtime it was
// released with.
val generateVersion = tasks.register("generateKrisprVersion") {
    val out = layout.buildDirectory.dir("generated/krisprVersion")
    val version = project.version.toString()
    inputs.property("version", version)
    outputs.dir(out)
    doLast {
        out.get().file("dev/krispr/gradle/KrisprVersion.kt").asFile.apply { parentFile.mkdirs() }.writeText(
            "package dev.krispr.gradle\n\n/** Generated from the build's `version`. */\nconst val KRISPR_VERSION = \"$version\"\n",
        )
    }
}
sourceSets.main { kotlin.srcDir(generateVersion) }

kotlin {
    jvmToolchain(21)
    compilerOptions {
        // Runs on Gradle's embedded Kotlin stdlib, which is older than the compiler we build with.
        apiVersion.set(KotlinVersion.KOTLIN_2_2)
        languageVersion.set(KotlinVersion.KOTLIN_2_2)
    }
}

dependencies {
    compileOnly(libs.kotlin.gradle.plugin.api)
    compileOnly(libs.kotlin.gradle.plugin)
    // Public Variant API only, isolated in AgpCompat.kt; loaded only when an Android plugin is applied.
    compileOnly(libs.agp.api)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.json.schema.validator)
    // AridOptionsTest reads SubpluginOption; main only needs it compileOnly (the compiler plugin API
    // is provided by whatever Kotlin Gradle plugin the consuming build applies).
    testImplementation(libs.kotlin.gradle.plugin.api)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}

gradlePlugin {
    website.set("https://github.com/timusus/krispr")
    vcsUrl.set("https://github.com/timusus/krispr")
    plugins {
        create("krispr") {
            id = "dev.krispr"
            implementationClass = "dev.krispr.gradle.KrisprGradlePlugin"
            displayName = "Krispr"
            description = project.description
            tags.set(listOf("mutation-testing", "kotlin", "android", "testing", "kotlin-multiplatform"))
        }
    }
}

// Functional tests: Gradle TestKit builds of small Android and Kotlin Multiplatform projects, which
// resolve dev.krispr and its compiler and runtime from a file repository this build lays out.
val functionalTestRepo = layout.buildDirectory.dir("functional-test-repo")
val krisprArtifacts = configurations.create("krisprArtifacts") {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
}
dependencies {
    for (variant in listOf("k2120", "k220", "k230", "k2320", "k240")) {
        krisprArtifacts(project(":krispr-compiler-$variant"))
    }
    krisprArtifacts(project(":krispr-runtime"))
}
val functionalTestRepoTask = tasks.register<Sync>("functionalTestRepo") {
    description = "Lays out the krispr jars as a Maven repository for the functional tests."
    val version = project.version.toString()
    into(functionalTestRepo)
    from(tasks.jar) { into("dev/krispr/krispr-gradle/$version") }
    from(krisprArtifacts) {
        eachFile { path = "dev/krispr/${name.removeSuffix(".jar").removeSuffix("-$version")}/$version/$name" }
    }
    includeEmptyDirs = false
}

testing {
    suites {
        register<JvmTestSuite>("functionalTest") {
            useJUnitJupiter(libs.versions.junit.asProvider())
            dependencies {
                implementation(gradleTestKit())
            }
            targets.all {
                testTask.configure {
                    description = "Builds sample Android and Kotlin Multiplatform projects with Gradle TestKit."
                    dependsOn(functionalTestRepoTask)
                    systemProperty("krispr.repo", functionalTestRepo.get().asFile.absolutePath)
                    systemProperty("krispr.version", project.version.toString())
                    // The generated projects need an Android SDK: ANDROID_HOME, or the root local.properties.
                    systemProperty("krispr.rootDir", rootDir.absolutePath)
                    // Forks are assigned whole test classes, and each test's nested Gradle/AGP build is
                    // already capped at --max-workers=2, so 2 forks fits a --max-workers=4 outer build
                    // without oversubscribing. Measured: warm wall time only drops
                    // 145s -> 138s, well short of the naive per-class-bin-packing estimate, because each
                    // fork pays its own JVM/Gradle-daemon startup and the nested AGP builds themselves
                    // are the bottleneck, not raw JUnit execution time. Kept anyway: no
                    // coverage change (still 57/57 passing) and no downside within --max-workers=4.
                    maxParallelForks = 2
                }
            }
        }
    }
}
tasks.check { dependsOn(tasks.named("functionalTest")) }
