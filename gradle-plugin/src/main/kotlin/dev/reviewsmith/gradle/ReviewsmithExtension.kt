package dev.reviewsmith.gradle

import org.gradle.api.provider.Property

abstract class ReviewsmithExtension {
    abstract val scope: Property<String>
    abstract val model: Property<String>
    abstract val attachToCheck: Property<Boolean>

    /** When true (default), a triggered review gate (CLI exit 3) fails the Gradle build. */
    abstract val failOnGate: Property<Boolean>
}
