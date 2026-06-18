group = "dev.memnos"
version = "0.1.0"

plugins {
    id("java-library")
    id("com.gradleup.shadow") version "9.4.2"
    id("xyz.jpenilla.run-paper") version "3.0.2"
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://maven.citizensnpcs.co/repo")
    maven("https://jitpack.io")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.1.2.build.+")
    implementation("org.java-websocket:Java-WebSocket:1.5.6")
    compileOnly("net.citizensnpcs:citizens-main:2.0.42-SNAPSHOT") {
        exclude(group = "*")
    }
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
}

tasks {
    runServer {
        minecraftVersion("26.2")
        jvmArgs("-Xms2G", "-Xmx2G")
    }

    processResources {
        val props = mapOf("version" to version)
        filesMatching("plugin.yml") {
            expand(props)
        }
    }

    shadowJar {
        // Relocate the bundled WebSocket client to avoid classpath conflicts
        // with other plugins shading the same library.
        relocate("org.java_websocket", "dev.memnos.controlbridge.lib.java_websocket")
        archiveClassifier.set("")
    }

    // Copy the shaded plugin JAR into the local test server after every build.
    // The path is environment-specific and stays out of the repo via a property
    // with a sensible default (see gradle.properties).
    register<Copy>("deployToTestServer") {
        from(shadowJar)
        into(providers.gradleProperty("testServerPluginsDir")
            .getOrElse("C:/dev/mc-test-server/plugins"))
    }

    // The plugin JAR users install is the shaded one.
    build {
        dependsOn(shadowJar)
        finalizedBy("deployToTestServer")
    }
}