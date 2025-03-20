package com.github.zijing66.dependencymanager.services

import com.github.zijing66.dependencymanager.models.DependencyType
import com.intellij.openapi.project.Project
import java.io.File

class DependencyManagerDetector {
    fun detectDependencyType(project: Project): DependencyType {
        val projectPath = project.basePath ?: return DependencyType.UNKNOWN
        
        return when {
            File("$projectPath/pom.xml").exists() ||
            File("$projectPath/.mvn").exists() ||
            File("$projectPath/mvnw").exists() ||
            File("$projectPath/mvnw.cmd").exists() -> DependencyType.MAVEN

            File("$projectPath/build.gradle").exists() || 
            File("$projectPath/build.gradle.kts").exists() || 
            File("$projectPath/settings.gradle").exists() || 
            File("$projectPath/settings.gradle.kts").exists() -> DependencyType.GRADLE

            File("$projectPath/package.json").exists() -> DependencyType.NPM
            
            File("$projectPath/requirements.txt").exists() || 
            File("$projectPath/setup.py").exists() || 
            File("$projectPath/environment.yml").exists() || 
            File("$projectPath/conda-env.yml").exists() || 
            File("$projectPath/pyproject.toml").exists() -> DependencyType.PIP
            else -> DependencyType.UNKNOWN
        }
    }
} 