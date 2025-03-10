package com.github.zijing66.dependencymanager.services

import com.intellij.openapi.project.Project
import java.io.File

abstract class AbstractConfigService(project: Project) : IConfigService {

    protected val project: Project = project

    protected fun isValidRepoPath(path: String): Boolean {
        return try {
            val file = File(path)
            file.isDirectory && file.canWrite()
        } catch (e: SecurityException) {
            false
        }
    }
}