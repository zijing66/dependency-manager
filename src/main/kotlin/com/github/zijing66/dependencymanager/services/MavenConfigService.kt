package com.github.zijing66.dependencymanager.services

import com.github.zijing66.dependencymanager.models.CleanupPreview
import com.github.zijing66.dependencymanager.models.CleanupResult
import com.github.zijing66.dependencymanager.models.CleanupSummary
import com.github.zijing66.dependencymanager.models.DependencyType
import com.intellij.openapi.components.Service
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

@Service
class MavenConfigService {
    private fun getMavenHome(): String {
        // 首先检查环境变量
        System.getenv("MAVEN_HOME")?.let { return it }
        System.getenv("M2_HOME")?.let { return it }
        
        // 检查默认安装位置
        val defaultPaths = listOf(
            "/usr/local/maven",
            "/opt/maven",
            System.getProperty("user.home") + "/.m2"
        )
        
        for (path in defaultPaths) {
            if (File(path).exists()) return path
        }
        
        return ""
    }
    
    fun getLocalRepository(): String {
        val settingsFile = File(getMavenHome(), "conf/settings.xml")
        if (!settingsFile.exists()) {
            return "${System.getProperty("user.home")}/.m2/repository"
        }

        // Parse settings.xml to get local repository path
        val dbFactory = DocumentBuilderFactory.newInstance()
        val dBuilder = dbFactory.newDocumentBuilder()
        val doc = dBuilder.parse(settingsFile)
        
        val localRepoNodes = doc.getElementsByTagName("localRepository")
        if (localRepoNodes.length > 0) {
            return localRepoNodes.item(0).textContent
        }
        
        return "${System.getProperty("user.home")}/.m2/repository"
    }
    
    fun previewCleanup(): CleanupSummary {
        val repoDir = File(getLocalRepository())
        val previewItems = mutableListOf<CleanupPreview>()
        var totalSize = 0L
        
        scanForFailedDownloads(repoDir) { file ->
            previewItems.add(CleanupPreview(
                path = file.absolutePath,
                packageName = file.parentFile.name,
                fileSize = file.length(),
                lastModified = file.lastModified(),
                dependencyType = DependencyType.MAVEN
            ))
            totalSize += file.length()
        }
        
        return CleanupSummary(
            totalFiles = previewItems.size,
            totalSize = totalSize,
            previewItems = previewItems
        )
    }
    
    fun cleanupFailedDownloads(
        project: Project,
        selectedItems: List<CleanupPreview>,
        onProgress: (Int, Int) -> Unit,
        onComplete: (List<CleanupResult>) -> Unit
    ) {
        object : Task.Backgroundable(project, "Cleaning up failed downloads", true) {
            override fun run(indicator: ProgressIndicator) {
                val results = mutableListOf<CleanupResult>()
                val totalItems = selectedItems.size
                
                selectedItems.forEachIndexed { index, item ->
                    indicator.fraction = index.toDouble() / totalItems
                    indicator.text = "Cleaning up: ${item.packageName}"
                    
                    val file = File(item.path)
                    val success = file.delete()
                    
                    results.add(CleanupResult(
                        path = item.path,
                        packageName = item.packageName,
                        dependencyType = item.dependencyType,
                        success = success,
                        errorMessage = if (!success) "Failed to delete file" else null
                    ))
                    
                    onProgress(index + 1, totalItems)
                }
                
                onComplete(results)
            }
        }.queue()
    }
    
    private fun scanForFailedDownloads(dir: File, onFileFound: (File) -> Unit) {
        dir.listFiles()?.forEach { file ->
            when {
                file.isDirectory -> scanForFailedDownloads(file, onFileFound)
                file.name.endsWith(".jar.lastUpdated") || 
                file.name.endsWith(".pom.lastUpdated") -> onFileFound(file)
            }
        }
    }
}