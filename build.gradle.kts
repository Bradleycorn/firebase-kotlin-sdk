import org.apache.tools.ant.taskdefs.condition.Os
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent

repositories {
    google()
    mavenCentral()
}

plugins {
    kotlin("multiplatform") version "1.8.20" apply false
    kotlin("native.cocoapods") version "1.8.20" apply false
    id("base")
    id("com.github.ben-manes.versions") version "0.42.0"
}

buildscript {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        maven {
            url = uri("https://plugins.gradle.org/m2/")
        }
    }
    dependencies {
        classpath("com.android.tools.build:gradle:7.2.2")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:1.8.20-RC")
        classpath("com.adarshr:gradle-test-logger-plugin:3.2.0")
    }
}

val targetSdkVersion by extra(32)
val minSdkVersion by extra(19)

tasks {
    val updateVersions by registering {
        dependsOn(
            "firebase-app:updateVersion", "firebase-app:updateDependencyVersion",
            "firebase-auth:updateVersion", "firebase-auth:updateDependencyVersion",
            "firebase-common:updateVersion", "firebase-common:updateDependencyVersion",
            "firebase-config:updateVersion", "firebase-config:updateDependencyVersion",
            "firebase-database:updateVersion", "firebase-database:updateDependencyVersion",
            "firebase-firestore:updateVersion", "firebase-firestore:updateDependencyVersion",
            "firebase-functions:updateVersion", "firebase-functions:updateDependencyVersion",
            "firebase-installations:updateVersion", "firebase-installations:updateDependencyVersion",
            "firebase-perf:updateVersion", "firebase-perf:updateDependencyVersion"
        )
    }
}

subprojects {

    group = "dev.gitlive"

    apply(plugin = "com.adarshr.test-logger")

    repositories {
        mavenLocal()
        google()
        mavenCentral()
    }

    tasks.withType<Sign>().configureEach {
        onlyIf { !project.gradle.startParameter.taskNames.contains("publishToMavenLocal") }
    }

    val skipPublishing = project.name == "test-utils" // skip publishing for test utils

    tasks {
        withType<Test> {
            testLogging {
                showExceptions = true
                exceptionFormat = TestExceptionFormat.FULL
                showStandardStreams = true
                showCauses = true
                showStackTraces = true
                events = setOf(
                    TestLogEvent.STARTED,
                    TestLogEvent.FAILED,
                    TestLogEvent.PASSED,
                    TestLogEvent.SKIPPED,
                    TestLogEvent.STANDARD_OUT,
                    TestLogEvent.STANDARD_ERROR
                )
            }
        }

        if (skipPublishing) return@tasks

        val updateVersion by registering(Exec::class) {
            commandLine("npm", "--allow-same-version", "--prefix", projectDir, "version", "${project.property("${project.name}.version")}")
        }

        val updateDependencyVersion by registering(Copy::class) {
            mustRunAfter("updateVersion")
            val from = file("package.json")
            from.writeText(
                from.readText()
                    .replace("version\": \"([^\"]+)".toRegex(), "version\": \"${project.property("${project.name}.version")}")
                    .replace("firebase-common\": \"([^\"]+)".toRegex(), "firebase-common\": \"${project.property("firebase-common.version")}")
                    .replace("firebase-app\": \"([^\"]+)".toRegex(), "firebase-app\": \"${project.property("firebase-app.version")}")
            )
        }

        val copyReadMe by registering(Copy::class) {
            from(rootProject.file("README.md"))
            into(file("$buildDir/node_module"))
        }

        val copyPackageJson by registering(Copy::class) {
            from(file("package.json"))
            into(file("$buildDir/node_module"))
        }

        val unzipJar by registering(Copy::class) {
            val zipFile = File("$buildDir/libs", "${project.name}-js-${project.version}.jar")
            from(this.project.zipTree(zipFile))
            into("$buildDir/classes/kotlin/js/main/")
        }

        val copyJS by registering {
            mustRunAfter("unzipJar", "copyPackageJson")
            doLast {
                val from = File("$buildDir/classes/kotlin/js/main/${rootProject.name}-${project.name}.js")
                val into = File("$buildDir/node_module/${project.name}.js")
                into.createNewFile()
                into.writeText(
                    from.readText()
                        .replace("require('firebase-kotlin-sdk-", "require('@gitlive/")
//                        .replace("require('kotlinx-serialization-kotlinx-serialization-runtime')", "require('@gitlive/kotlinx-serialization-runtime')")
                )
            }
        }

        val copySourceMap by registering(Copy::class) {
            from(file("$buildDir/classes/kotlin/js/main/${project.name}.js.map"))
            into(file("$buildDir/node_module"))
        }

        val prepareForNpmPublish by registering {
            dependsOn(
                unzipJar,
                copyPackageJson,
                copySourceMap,
                copyReadMe,
                copyJS
            )
        }

        val publishToNpm by creating(Exec::class) {
            workingDir("$buildDir/node_module")
            isIgnoreExitValue = true
            if(Os.isFamily(Os.FAMILY_WINDOWS)) {
                commandLine("cmd", "/c", "npm publish")
            } else {
                commandLine("npm", "publish")
            }
        }
    }

    afterEvaluate  {
        // create the projects node_modules if they don't exist
        if(!File("$buildDir/node_module").exists()) {
            mkdir("$buildDir/node_module")
        }

        dependencies {
            "commonMainImplementation"("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.6.4")
            "androidMainImplementation"("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.6.4")
            "androidMainImplementation"(platform("com.google.firebase:firebase-bom:31.4.0"))
            "commonTestImplementation"(kotlin("test-common"))
            "commonTestImplementation"(kotlin("test-annotations-common"))
            "commonTestImplementation"("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.6.4")
            "commonTestImplementation"("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.6.4")
            if (this@afterEvaluate.name != "firebase-crashlytics") {
                "jsTestImplementation"(kotlin("test-js"))
            }
            "androidAndroidTestImplementation"(kotlin("test-junit"))
            "androidAndroidTestImplementation"("junit:junit:4.13.2")
            "androidAndroidTestImplementation"("androidx.test:core:1.4.0")
            "androidAndroidTestImplementation"("androidx.test.ext:junit:1.1.3")
            "androidAndroidTestImplementation"("androidx.test:runner:1.4.0")
        }
    }

    if (skipPublishing) return@subprojects

    apply(plugin = "maven-publish")
    apply(plugin = "signing")


    configure<PublishingExtension> {

        repositories {
            maven {
                url = uri("https://oss.sonatype.org/service/local/staging/deploy/maven2")
                credentials {
                    username = project.findProperty("sonatypeUsername") as String? ?: System.getenv("sonatypeUsername")
                    password = project.findProperty("sonatypePassword") as String? ?: System.getenv("sonatypePassword")
                }
            }
        }

        publications.all {
            this as MavenPublication

            pom {
                name.set("firebase-kotlin-sdk")
                description.set("The Firebase Kotlin SDK is a Kotlin-first SDK for Firebase. It's API is similar to the Firebase Android SDK Kotlin Extensions but also supports multiplatform projects, enabling you to use Firebase directly from your common source targeting iOS, Android or JS.")
                url.set("https://github.com/GitLiveApp/firebase-kotlin-sdk")
                inceptionYear.set("2019")

                scm {
                    url.set("https://github.com/GitLiveApp/firebase-kotlin-sdk")
                    connection.set("scm:git:https://github.com/GitLiveApp/firebase-kotlin-sdk.git")
                    developerConnection.set("scm:git:https://github.com/GitLiveApp/firebase-kotlin-sdk.git")
                    tag.set("HEAD")
                }

                issueManagement {
                    system.set("GitHub Issues")
                    url.set("https://github.com/GitLiveApp/firebase-kotlin-sdk/issues")
                }

                developers {
                    developer {
                        name.set("Nicholas Bransby-Williams")
                        email.set("nbransby@gmail.com")
                    }
                }

                licenses {
                    license {
                        name.set("The Apache Software License, Version 2.0")
                        url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
                        distribution.set("repo")
                        comments.set("A business-friendly OSS license")
                    }
                }
            }
        }

    }
}

tasks.withType<com.github.benmanes.gradle.versions.updates.DependencyUpdatesTask> {

    fun isNonStable(version: String): Boolean {
        val stableKeyword = listOf("RELEASE", "FINAL", "GA").any { version.toUpperCase().contains(it) }
        val versionMatch = "^[0-9,.v-]+(-r)?$".toRegex().matches(version)

        return (stableKeyword || versionMatch).not()
    }

    rejectVersionIf {
        isNonStable(candidate.version)
    }

    checkForGradleUpdate = true
    outputFormatter = "plain,html"
    outputDir = "build/dependency-reports"
    reportfileName = "dependency-updates"
}
// check for latest dependencies - ./gradlew dependencyUpdates -Drevision=release
