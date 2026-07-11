plugins {
    kotlin("jvm")
    kotlin("plugin.serialization") version "2.0.21"
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":provider-spi"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("com.charleskorn.kaml:kaml:0.61.0")

    testImplementation(platform("org.junit:junit-bom:5.11.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

val generateVersionProperties by tasks.registering {
    val outputDir = layout.buildDirectory.dir("generated-resources/version")
    val versionValue = project.version.toString()
    inputs.property("version", versionValue)
    outputs.dir(outputDir)
    doLast {
        val file = outputDir.get().file("reviewsmith-version.properties").asFile
        file.parentFile.mkdirs()
        file.writeText("version=$versionValue\n")
    }
}

sourceSets.main {
    resources.srcDir(generateVersionProperties)
}

tasks.test {
    useJUnitPlatform()
}
