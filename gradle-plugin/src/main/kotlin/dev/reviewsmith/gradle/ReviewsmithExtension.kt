package dev.reviewsmith.gradle

import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property

abstract class ReviewsmithExtension {
    abstract val scope: Property<String>
    abstract val model: Property<String>
    abstract val attachToCheck: Property<Boolean>

    /** When true (default), a triggered review gate (CLI exit 3) fails the Gradle build. */
    abstract val failOnGate: Property<Boolean>

    /** Output format: console (default) | json | sarif. */
    abstract val format: Property<String>

    /** When set with a json/sarif format, the report is written here instead of stdout. */
    abstract val outputFile: RegularFileProperty

    /** Agent isolation: strict (default, hermetic) | local. */
    abstract val isolation: Property<String>

    /** When true, bypass the response cache for this run. */
    abstract val noCache: Property<Boolean>
}
