plugins {
    kotlin("jvm")
    `java-gradle-plugin`
    `maven-publish`
    id("com.gradle.plugin-publish") version "1.3.1"
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

tasks.withType<AbstractArchiveTask>().configureEach {
    dependsOn(bundleCli)
}

gradlePlugin {
    website.set("https://github.com/mcaustin/reviewsmith")
    vcsUrl.set("https://github.com/mcaustin/reviewsmith.git")
    plugins {
        create("reviewsmith") {
            id = "io.github.mcaustin.reviewsmith"
            implementationClass = "dev.reviewsmith.gradle.ReviewsmithPlugin"
            displayName = "Reviewsmith"
            description = "AI-agent code review that reasons about intent — a linter for what static analysis can't express."
            tags.set(listOf("code-review", "static-analysis", "ai", "linter", "kotlin"))
        }
    }
}

tasks.test {
    useJUnitPlatform()
}
