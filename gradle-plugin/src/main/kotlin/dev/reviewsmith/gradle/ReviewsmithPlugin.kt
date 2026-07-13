package dev.reviewsmith.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project

class ReviewsmithPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val extension = project.extensions.create("reviewsmith", ReviewsmithExtension::class.java)
        extension.attachToCheck.convention(false)
        extension.failOnGate.convention(true)

        val reviewTask = project.tasks.register("reviewsmith", ReviewsmithTask::class.java) { task ->
            task.group = "verification"
            task.description = "Runs Reviewsmith AI code review."
            task.scope.set(extension.scope)
            task.model.set(extension.model)
            task.failOnGate.set(extension.failOnGate)
            task.format.set(extension.format)
            task.outputFile.set(extension.outputFile)
            task.isolation.set(extension.isolation)
            task.noCache.set(extension.noCache)
            task.maxUnits.set(extension.maxUnits)
            task.reportLevel.set(extension.reportLevel)
            task.projectRootDir.set(project.layout.projectDirectory)
        }

        project.tasks.matching { it.name == "check" }.configureEach { check ->
            check.dependsOn(
                project.provider {
                    if (extension.attachToCheck.getOrElse(false)) listOf(reviewTask) else emptyList<Any>()
                },
            )
        }
    }
}
