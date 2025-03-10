package com.github.zijing66.dependencymanager.services

import com.github.zijing66.dependencymanager.models.DependencyType
import com.intellij.openapi.project.Project
import java.io.File

class DependencyManagerDetector {
    fun detectDependencyType(project: Project): DependencyType {
        val projectPath = project.basePath ?: return DependencyType.UNKNOWN
        
        return when {
            File("$projectPath/pom.xml").exists() -> DependencyType.MAVEN
            File("$projectPath/build.gradle").exists() || 
            File("$projectPath/build.gradle.kts").exists() -> DependencyType.GRADLE
//            File("$projectPath/package.json").exists() -> DependencyType.NPM
            else -> DependencyType.UNKNOWN
        }
    }
} 