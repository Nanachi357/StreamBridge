import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.jar.JarFile

plugins {
    java
    id("org.springframework.boot") version "4.1.1"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "dev.streambridge"
version = "0.1.0-SNAPSHOT"

// The runtime is the pilot source set below, not an empty Spring Boot application.
tasks.named("bootJar") { enabled = false }
tasks.named("jar") { enabled = false }

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("com.microsoft.playwright:playwright:1.63.0")
    // Pin the HTTP client/core pair used by the bounded, DNS-pinned acquisition spike.
    testImplementation("org.apache.httpcomponents.client5:httpclient5:5.6.5")
    testImplementation("org.apache.httpcomponents.core5:httpcore5:5.4.4")
    testImplementation("org.apache.httpcomponents.core5:httpcore5-h2:5.4.4")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // CI console logs must retain assertion values and causes when no report is uploaded.
    testLogging { exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
    systemProperty("streambridge.testClasspath", sourceSets["test"].runtimeClasspath.asPath)
    // Manual main() experiments are compiled but are not JUnit tests.
    include("**/*Test.class")
}

tasks.register<JavaExec>("installSpikeChromium") {
    group = "spike"
    description = "Install the Chromium version required by the browser spike."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.microsoft.playwright.CLI")
    args("install", "chromium", "--no-shell")
    environment("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1")
    javaLauncher.set(javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(21))
    })
}

tasks.register<JavaExec>("browserSpike") {
    group = "spike"
    description = "Open visible Chromium; press Enter in the console to close it."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("dev.streambridge.spike.BrowserSpike")
    standardInput = System.`in`
    javaLauncher.set(javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(21))
    })
}

tasks.register<JavaExec>("hlsDiscoverySpike") {
    group = "spike"
    description = "Observe a local HLS player's requests; click Play in Chromium."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("dev.streambridge.spike.HlsDiscoverySpike")
    systemProperty("streambridge.hlsFixture", layout.buildDirectory.dir("hls-fixture").get().asFile.absolutePath)
    javaLauncher.set(javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(21))
    })
}

tasks.register<JavaExec>("hlsDownloadSpike") {
    group = "spike"
    description = "Discover the local HLS fixture, remux to MP4 and verify it."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("dev.streambridge.spike.HlsDownloadSpike")
    standardInput = System.`in`
    systemProperty("streambridge.hlsFixture", layout.buildDirectory.dir("hls-fixture").get().asFile.absolutePath)
    systemProperty("streambridge.hlsAvFixture", layout.buildDirectory.dir("hls-av-fixture").get().asFile.absolutePath)
    systemProperty("streambridge.hlsOutputs", layout.buildDirectory.dir("hls-downloads").get().asFile.absolutePath)
    javaLauncher.set(javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(21))
    })
}

tasks.register<JavaExec>("hlsSelectionSpike") {
    group = "spike"
    description = "Discover HLS masters, select a video variant and separate audio, verify actual content."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("dev.streambridge.spike.HlsSelectionSpike")
    standardInput = System.`in`
    systemProperty("streambridge.hlsFixture", layout.buildDirectory.dir("hls-fixture").get().asFile.absolutePath)
    systemProperty("streambridge.selectionFixture", layout.buildDirectory.dir("hls-selection-fixture").get().asFile.absolutePath)
    systemProperty("streambridge.selectionOutputs", layout.buildDirectory.dir("hls-selections").get().asFile.absolutePath)
    javaLauncher.set(javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(21))
    })
}

tasks.register<JavaExec>("realDiscoverySpike") {
    group = "spike"
    description = "Bounded, isolated, discovery-only browser diagnostic for a supplied public page."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("dev.streambridge.spike.RealDiscoverySpike")
    standardInput = System.`in`
    javaLauncher.set(javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(21))
    })
}

tasks.register<JavaExec>("localUiSpike") {
    group = "spike"
    description = "Start a loopback-only web UI backed by isolated real discovery/download workers."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("dev.streambridge.spike.LocalUiSpike")
    javaLauncher.set(javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(21))
    })
}

tasks.register<JavaExec>("localUiLauncher") {
    group = "spike"
    description = "Check dependencies and open the Windows local UI launcher."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("dev.streambridge.spike.LocalUiLauncher")
    javaLauncher.set(javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(21))
    })
}

tasks.register<JavaExec>("hlsAcquisitionMediaSpike") {
    group = "spike"
    description = "Verify bounded acquisition against owned encoded HLS media and local-only FFmpeg inputs."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("dev.streambridge.spike.HlsAcquisitionMediaSpike")
    javaLauncher.set(javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(21))
    })
}

// Package the existing pilot without promoting experiments to the production API
// or shipping JUnit/Mockito/Spring test libraries. Sources remain shared, not copied.
val pilot = sourceSets.create("pilot") {
    java.srcDir("src/test/java")
    java.exclude("**/*Test.java", "**/LocalUiWorkerFixture.java")
    resources.srcDir("src/test/resources")
    resources.include("spike/ui/**")
}
dependencies {
    add(pilot.implementationConfigurationName, "com.microsoft.playwright:playwright:1.63.0")
    add(pilot.implementationConfigurationName, "org.apache.httpcomponents.client5:httpclient5:5.6.5")
    add(pilot.implementationConfigurationName, "org.apache.httpcomponents.core5:httpcore5:5.4.4")
    add(pilot.implementationConfigurationName, "org.apache.httpcomponents.core5:httpcore5-h2:5.4.4")
    add(pilot.implementationConfigurationName, "tools.jackson.core:jackson-databind")
}
val pilotLibraries = configurations.named(pilot.runtimeClasspathConfigurationName)
val pilotJar = tasks.register<Jar>("pilotJar") {
    archiveFileName.set("streambridge.jar")
    destinationDirectory.set(layout.buildDirectory.dir("pilot-jar"))
    from(pilot.output)
    doFirst {
        manifest.attributes(
            "Main-Class" to "dev.streambridge.spike.LocalUiLauncher",
            "Class-Path" to pilotLibraries.get().files.sortedBy { it.name }.joinToString(" ") { "lib/${it.name}" }
        )
    }
}
tasks.named("assemble") { dependsOn(pilotJar) }
val verifyPilotJar = tasks.register("verifyPilotJar") {
    group = "verification"
    description = "Check launcher/resources and prevent test code from entering the pilot JAR."
    dependsOn(pilotJar)
    doLast {
        JarFile(pilotJar.get().archiveFile.get().asFile).use { jar ->
            val names = jar.entries().asSequence().map { it.name }.toSet()
            check(jar.manifest.mainAttributes.getValue("Main-Class") == "dev.streambridge.spike.LocalUiLauncher")
            for (required in listOf("dev/streambridge/spike/LocalUiLauncher.class", "spike/ui/index.html", "spike/ui/app.js", "spike/ui/app.css")) {
                check(required in names) { "Missing pilot entry: $required" }
            }
            check(names.none { it.endsWith("Test.class") || it.contains("Test$") || it.contains("LocalUiWorkerFixture") }) {
                "Test classes must not be packaged"
            }
            val libraries = pilotLibraries.get().files.map { it.name }
            check(libraries.none { it.contains("junit") || it.contains("mockito") || it.contains("spring-test") }) {
                "Test dependencies must not be shipped"
            }
        }
    }
}
tasks.named("check") { dependsOn(verifyPilotJar) }
val stagePilot = tasks.register<Sync>("stagePilot") {
    dependsOn(verifyPilotJar)
    into(layout.buildDirectory.dir("pilot-input"))
    from(pilotJar)
    from(pilotLibraries) { into("lib") }
    from(rootProject.file("../LICENSE"))
    from(rootProject.file("../docs/third-party.md"))
    from("packaging/README.txt")
    from("packaging/tools.example.properties")
}
val packageJdk = javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) }
tasks.register<Exec>("packageWindowsApp") {
    group = "distribution"
    description = "Build a new Windows app-image with StreamBridge.exe and a private Java 21 runtime."
    dependsOn(stagePilot)
    // Every invocation creates a fresh image. Never overwrite a running app or user data.
    val destination = layout.buildDirectory.dir("windows-app/" +
        LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")))
    doFirst {
        check(System.getProperty("os.name").startsWith("Windows")) { "Build the Windows image on Windows." }
        val jdk = packageJdk.get().metadata.installationPath.asFile
        commandLine(
            jdk.resolve("bin/jpackage.exe").absolutePath,
            "--type", "app-image", "--name", "StreamBridge", "--app-version", "0.1.0",
            "--vendor", "Vladyslav Ponomarenko", "--description", "Local StreamBridge pilot",
            "--input", layout.buildDirectory.dir("pilot-input").get().asFile.absolutePath,
            "--dest", destination.get().asFile.absolutePath,
            "--main-jar", "streambridge.jar", "--main-class", "dev.streambridge.spike.LocalUiLauncher",
            // Playwright needs zipfs; HttpClient 5 uses jdk.net.Sockets when opening connections.
            "--add-modules", "java.se,jdk.httpserver,jdk.crypto.ec,jdk.unsupported,jdk.charsets,jdk.zipfs,jdk.net",
            // Child workers need runtime/bin/java[w].exe; jpackage's default strips these.
            "--jlink-options", "--strip-debug --no-man-pages --no-header-files",
            "--java-options", "-Dfile.encoding=UTF-8"
        )
    }
    doLast {
        val image = destination.get().asFile.resolve("StreamBridge")
        layout.buildDirectory.file("windows-app/latest-path.txt").get().asFile.writeText(image.absolutePath)
        logger.lifecycle("Application image: ${image.absolutePath}")
        logger.lifecycle("Launch StreamBridge.exe; keep the whole folder, including app and runtime.")
    }
}
