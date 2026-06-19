plugins {
    kotlin("jvm") version "1.8.22"
    id("priv.seventeen.artist.blink") version "1.3.8"
    id("com.github.johnrengelman.shadow") version "8.1.1"
    `maven-publish`
}

group = "priv.seventeen.artist"
version = "1.0.0"

blink {
    name.set("Rondo")
    version.set("1.0.0")
    authors.set(listOf("17Artist"))
    apiVersion.set("1.18")
    packageName.set("priv.seventeen.artist.rondo")
    description.set("A universal multi-currency economy system")
    softDepend.set(listOf("Vault", "PlaceholderAPI"))
    logPrefix.set("§6✦ §eRondo")
    libraries.set(listOf(
        "com.zaxxer:HikariCP:5.1.0",
        "org.xerial:sqlite-jdbc:3.44.1.0",
        "redis.clients:jedis:5.1.0"
    ))
}

repositories {
    maven("https://repo.arcartx.com/repository/maven-public/")
    maven("https://hub.spigotmc.org/nexus/content/repositories/snapshots/")
    maven("https://repo.extendedclip.com/content/repositories/placeholderapi/")
    maven("https://jitpack.io")
    mavenCentral()
}

dependencies {
    implementation("priv.seventeen.artist.blink:blink-common:1.3.8")
    compileOnly("org.spigotmc:spigot-api:1.18.2-R0.1-SNAPSHOT")
    compileOnly("com.github.MilkBowl:VaultAPI:1.7.1")
    compileOnly("me.clip:placeholderapi:2.11.6")
    compileOnly("com.zaxxer:HikariCP:5.1.0")
    compileOnly("org.xerial:sqlite-jdbc:3.44.1.0")
    compileOnly("redis.clients:jedis:5.1.0")
}

kotlin {
    jvmToolchain(17)
}

tasks.shadowJar {
    archiveClassifier.set("")
}

tasks.named("build") {
    dependsOn("shadowJar")
}

val repoPassword: String = System.getenv("repo") ?: ""

publishing {
    publications {
        create<MavenPublication>("shadow") {
            artifact(tasks.shadowJar.get().archiveFile) {
                classifier = null
            }
            artifactId = rootProject.name.lowercase()
            val buildNumber = System.getenv("BUILD_NUMBER")
            version = if (buildNumber != null) "${project.version}.$buildNumber" else project.version.toString()
        }
    }
    repositories {
        maven {
            url = uri(project.findProperty("mavenRepoUrl") as? String ?: "")
            isAllowInsecureProtocol = true
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
