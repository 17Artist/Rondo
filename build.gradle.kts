/*
 * Copyright 2026 17Artist
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

plugins {
    kotlin("jvm") version "2.1.10"
    id("priv.seventeen.artist.blink") version "1.3.14"
    id("com.gradleup.shadow") version "8.3.11"
    `maven-publish`
}

group = "priv.seventeen.artist"
version = providers.gradleProperty("version").getOrElse("1.1.0-SNAPSHOT")

blink {
    name.set("Rondo")
    version.set(project.version.toString())
    authors.set(listOf("17Artist"))
    apiVersion.set("1.18")
    packageName.set("priv.seventeen.artist.rondo")
    description.set("A universal multi-currency economy system")
    softDepend.set(listOf("Vault", "PlaceholderAPI"))
    logPrefix.set("§6♦ §eRondo")
    libraries.set(listOf(
        "com.zaxxer:HikariCP:5.1.0",
        "com.mysql:mysql-connector-j:8.4.0",
        "org.xerial:sqlite-jdbc:3.44.1.0",
        "redis.clients:jedis:5.1.0",
        "org.apache.commons:commons-pool2:2.12.0"
    ))
}

repositories {
    exclusiveContent {
        forRepository {
            maven("https://repo.arcartx.com/repository/maven-public/") {
                name = "ArcartX"
            }
        }
        filter {
            includeGroupByRegex("priv\\.seventeen\\.artist(\\..*)?")
        }
    }
    maven("https://hub.spigotmc.org/nexus/content/repositories/snapshots/") {
        content {
            includeGroup("org.spigotmc")
        }
    }
    maven("https://repo.extendedclip.com/content/repositories/placeholderapi/") {
        content {
            includeGroup("me.clip")
        }
    }
    maven("https://jitpack.io") {
        content {
            includeGroup("com.github.MilkBowl")
        }
    }
    mavenCentral()
}

dependencies {
    implementation("priv.seventeen.artist.blink:blink-common:1.3.14")
    compileOnly("org.spigotmc:spigot-api:1.18.2-R0.1-SNAPSHOT")
    compileOnly("com.github.MilkBowl:VaultAPI:1.7.1")
    compileOnly("me.clip:placeholderapi:2.11.6")
    compileOnly("com.zaxxer:HikariCP:5.1.0")
    compileOnly("com.mysql:mysql-connector-j:8.4.0")
    compileOnly("org.xerial:sqlite-jdbc:3.44.1.0")
    compileOnly("redis.clients:jedis:5.1.0")
    compileOnly("org.apache.commons:commons-pool2:2.12.0")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.4")
    testRuntimeOnly("org.xerial:sqlite-jdbc:3.44.1.0")
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_1_8)
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_1_8)
    }
}

tasks.shadowJar {
    archiveClassifier.set("")
    mergeServiceFiles()
}

tasks.processResources {
    from(rootProject.file("LICENSE")) {
        into("META-INF")
    }
}

tasks.named("build") {
    dependsOn("shadowJar")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

tasks.withType<AbstractArchiveTask>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

val repoPassword: String = System.getenv("repo") ?: ""

publishing {
    publications {
        create<MavenPublication>("shadow") {
            artifact(tasks.shadowJar.get().archiveFile) {
                classifier = null
            }
            artifactId = rootProject.name.lowercase()
            version = project.version.toString()
        }
    }
    repositories {
        maven {
            val targetUrl = project.findProperty("mavenRepoUrl") as? String
                ?: error("Missing mavenRepoUrl Gradle property")
            url = uri(targetUrl)
            isAllowInsecureProtocol = targetUrl.startsWith("http://")
            credentials {
                username = project.findProperty("mavenRepoUser") as? String ?: ""
                password = repoPassword
            }
        }
    }
}

tasks.register("deploy") {
    group = "publishing"
    description = "Publish shadow jar to Maven repository"
    dependsOn("publish")
}
