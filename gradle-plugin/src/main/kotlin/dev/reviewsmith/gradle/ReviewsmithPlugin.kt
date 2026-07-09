package dev.reviewsmith.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project

class ReviewsmithPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val extension = project.extensions.create("reviewsmith", ReviewsmithExtension::class.java)

        project.tasks.register("reviewsmith", ReviewsmithTask::class.java) { task ->
            task.group = "verification"
            task.description = "Runs Reviewsmith AI code review (advisory)."
            task.scope.set(extension.scope)
            task.model.set(extension.model)
            task.projectRootDir.set(project.layout.projectDirectory)
        }
    }
}
