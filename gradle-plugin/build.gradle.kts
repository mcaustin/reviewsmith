plugins {
    kotlin("jvm")
    `java-gradle-plugin`
    `maven-publish`
}

kotlin {
    jvmToolchain(17)
}

val cliJar: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    cliJar(project(mapOf("path" to ":cli", "configuration" to "shadow")))

    testImplementation(platform("org.junit:junit-bom:5.11.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation(gradleTestKit())
}

val bundleCli by tasks.registering(Copy::class) {
    from(cliJar) {
        rename { "reviewsmith-cli.jar" }
    }
    into(layout.buildDirectory.dir("generated-resources/cli"))
}

sourceSets.main {
    resources.srcDir(layout.buildDirectory.dir("generated-resources"))
}

tasks.named("processResources") {
    dependsOn(bundleCli)
}

gradlePlugin {
    plugins {
        create("reviewsmith") {
            id = "dev.reviewsmith"
            implementationClass = "dev.reviewsmith.gradle.ReviewsmithPlugin"
            displayName = "Reviewsmith"
            description = "AI-agent code review that reasons about intent"
        }
    }
}

tasks.test {
    useJUnitPlatform()
}
