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
import java.io.IOException
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
    private fun scanForFailedDownloads(
        dir: File,
        onFileFound: (File) -> Unit,
        includeSnapshot: Boolean = false,
        groupArtifact: String? = null
    ) {
        val processedDirs = mutableSetOf<String>() // 防止重复处理目录
        val targetGroupArtifact = groupArtifact?.split(":")?.map{it.replace('.', '/', true)}

        dir.walk().forEach { file ->
            when {
                file.isDirectory -> {
                    // 预扫描SNAPSHOT和group/artifact目录
                    if (includeSnapshot || groupArtifact != null) {
                        val relativePath = file.relativeTo(dir).path.replace('\\', '/')
                        val pathComponents = relativePath.split('/')

                        // 匹配SNAPSHOT目录结构：.../version-SNAPSHOT
                        val isSnapshotDir = includeSnapshot &&
                                pathComponents.last().contains("SNAPSHOT") &&
                                pathComponents.size >= 3 // 确保是版本目录

                        // 匹配group/artifact目录结构
                        val isTargetGroupArtifact: Boolean = if (targetGroupArtifact?.size == 2) {
                            targetGroupArtifact.let { (group, artifact) ->
                                relativePath == "$group/$artifact"
                            }
                        } else {
                            targetGroupArtifact?.let { (group) ->
                                relativePath.startsWith(group)
                            } ?: false
                        }

                        if ((isSnapshotDir || isTargetGroupArtifact) &&
                            processedDirs.add(file.absolutePath)
                        ) {
                            onFileFound(file)
                        }
                    }
                }

                file.isFile && (file.name.endsWith(".jar.lastUpdated") ||
                        file.name.endsWith(".pom.lastUpdated")) -> {
                    onFileFound(file)
                }
            }
        }
    }

    fun previewCleanup(
        includeSnapshot: Boolean = false,
        groupArtifact: String? = null
    ): CleanupSummary {
        val repoDir = File(getLocalRepository())
        val previewItems = mutableListOf<CleanupPreview>()
        var totalSize = 0L

        scanForFailedDownloads(repoDir, { file ->
            val (packageName, version, relativePath) = extractPackageInfo(file)
            previewItems.add(
                CleanupPreview(
                    path = file.absolutePath,
                    packageName = "$packageName:$version",
                    fileSize = file.length(),
                    lastModified = file.lastModified(),
                    dependencyType = DependencyType.MAVEN,
                    relativePath = relativePath  // 添加相对路径到预览项
                )
            )
            totalSize += file.length()
        }, includeSnapshot, groupArtifact)

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
        object : Task.Backgroundable(project, "Cleaning up maven downloads", true) {
            override fun run(indicator: ProgressIndicator) {
                val results = mutableListOf<CleanupResult>()
                val totalItems = selectedItems.size

                selectedItems.forEachIndexed { index, item ->
                    indicator.fraction = index.toDouble() / totalItems
                    indicator.text = "Cleaning up: ${item.packageName}"

                    val file = File(item.path)
                    val success = if (file.isDirectory) {
                        // 删除目录
                        file.deleteRecursively()
                    } else {
                        file.delete()
                    }

                    results.add(
                        CleanupResult(
                            path = item.path,
                            packageName = item.packageName,
                            dependencyType = item.dependencyType,
                            success = success,
                            errorMessage = if (!success) "Failed to delete file" else null
                        )
                    )

                    onProgress(index + 1, totalItems)
                }
                onComplete(results)
            }
        }.queue()
    }

    private fun extractPackageInfo(file: File): Triple<String, String, String> {
        val repoPath = getLocalRepository().replace('\\', '/')
        val parentPath = if (file.isDirectory) file.absolutePath.replace('\\', '/') else file.parentFile.absolutePath.replace('\\', '/')
        val relativePath = parentPath.removePrefix(repoPath).trimStart('/')

        val components = relativePath.split("/").filter { it.isNotEmpty() }

        return when {
            components.size >= 3 -> {
                val groupId = components.dropLast(2).joinToString(".")
                val artifactId = components[components.size - 2]
                val version = components.last()
                Triple("$groupId:$artifactId", version, relativePath)
            }

            components.size == 2 -> {
                Triple(components[0], components[1], relativePath)
            }

            else -> {
                Triple(file.parentFile.name, file.name.substringBefore(".lastUpdated"), relativePath)
            }
        }
    }
}