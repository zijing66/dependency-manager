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

    // 添加自定义仓库路径存储
    private var customRepoPath: String? = null

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
        customRepoPath?.takeIf { isValidRepoPath(it) }?.let { return it }
        
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
    
    // 新增路径更新方法
    fun updateLocalRepository(newPath: String) {
        if (isValidRepoPath(newPath)) {
            customRepoPath = newPath
        } else {
            throw IllegalArgumentException("Invalid repository path: $newPath")
        }
    }
    
    // 新增路径验证方法
    private fun isValidRepoPath(path: String): Boolean {
        return try {
            val file = File(path)
            file.isDirectory && file.canWrite()
        } catch (e: SecurityException) {
            false
        }
    }
    
    // 修改扫描方法使用当前仓库路径
    private fun scanForFailedDownloads(dir: File, onFileFound: (File) -> Unit) {
        val repoDir = File(getLocalRepository()) // 使用动态获取路径
        dir.listFiles()?.forEach { file ->
            when {
                file.isDirectory -> scanForFailedDownloads(file, onFileFound)
                file.name.endsWith(".jar.lastUpdated") || 
                file.name.endsWith(".pom.lastUpdated") -> {
                    onFileFound(file)
                }
            }
        }
    }
    private fun extractPackageInfo(file: File): Triple<String, String, String> {
        val repoPath = getLocalRepository().replace('\\', '/')
        // 获取父目录的相对路径（排除文件名）
        val parentPath = file.parentFile.absolutePath.replace('\\', '/')
        val relativePath = parentPath.removePrefix(repoPath).trimStart('/')
        
        val componentsPaths = relativePath.split("/").filter { it.isNotEmpty() }
        if (componentsPaths.size >= 3) {
            val groupId = componentsPaths.dropLast(2).joinToString(".")
            val artifactId = componentsPaths[componentsPaths.size - 2]
            // 直接从路径组件获取版本号
            val version = componentsPaths.last()
            
            return Triple(
                "$groupId:$artifactId",
                version,
                relativePath  // 新增相对路径返回
            )
        }
        
        return Triple(
            file.parentFile.name,
            file.name.substringBefore(".lastUpdated"),
            relativePath  // 新增相对路径返回
        )
    }
    fun previewCleanup(): CleanupSummary {
        val repoDir = File(getLocalRepository())
        val previewItems = mutableListOf<CleanupPreview>()
        var totalSize = 0L
        
        scanForFailedDownloads(repoDir) { file ->
            val (packageName, version, relativePath) = extractPackageInfo(file)
            previewItems.add(CleanupPreview(
                path = file.absolutePath,
                packageName = "$packageName:$version",
                fileSize = file.length(),
                lastModified = file.lastModified(),
                dependencyType = DependencyType.MAVEN,
                relativePath = relativePath  // 添加相对路径到预览项
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
}