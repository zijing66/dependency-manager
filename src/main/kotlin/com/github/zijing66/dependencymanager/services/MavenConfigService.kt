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

@Service(Service.Level.PROJECT)
class MavenConfigService(project: Project) : AbstractConfigService(project) {

    // 添加自定义仓库路径存储
    private var customRepoPath: String? = null

    private fun getMavenHome(): String {
        // 首先检查环境变量
        System.getenv("MAVEN_HOME")?.let { return it }
        System.getenv("M2_HOME")?.let { return it }

        // 检查默认安装位置
        val defaultPaths = listOf(
            "/usr/local/maven", "/opt/maven", System.getProperty("user.home") + "/.m2"
        )

        for (path in defaultPaths) {
            if (File(path).exists()) return path
        }

        return ""
    }

    override fun getLocalRepository(refresh: Boolean): String {
        customRepoPath?.takeIf { !refresh && isValidRepoPath(it) }?.let { return it }

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
    override fun updateLocalRepository(newPath: String) {
        if (isValidRepoPath(newPath)) {
            customRepoPath = newPath
        } else {
            throw IllegalArgumentException("Invalid repository path: $newPath")
        }
    }

    // 检查是否是有效的Maven版本目录
    private fun isValidMavenVersionDir(dir: File): Boolean {
        // 检查是否是目录
        if (!dir.isDirectory) return false

        val files = dir.listFiles() ?: emptyArray()

        // 检查是否包含jar或pom文件，或者lastUpdated文件
        val hasValidFiles = files.any { file ->
            file.isFile && (file.name.endsWith(".jar") || file.name.endsWith(".pom") || file.name.endsWith(".jar.lastUpdated") || file.name.endsWith(".pom.lastUpdated"))
        }

        // 检查目录结构：至少应该有两级父目录（groupId/artifactId/version）
        val hasValidStructure = dir.parentFile?.parentFile != null

        return hasValidFiles && hasValidStructure
    }

    // 修改scanRepository方法，传递文件数组
    private fun scanRepository(
        dir: File, onDirFound: (File, String) -> Unit, includeSnapshot: Boolean = false, groupArtifact: String? = null
    ) {
        val targetGroupArtifact = groupArtifact?.split(":")?.map { it.replace('.', '/', true) }

        // 使用单次遍历记录所有目录信息
        dir.walk().forEach { versionDir ->
            // 验证这是一个有效的Maven依赖版本目录
            if (!isValidMavenVersionDir(versionDir)) {
                return@forEach
            }

            val relativePath = versionDir.relativeTo(dir).path.replace('\\', '/')
            val pathComponents = relativePath.split('/')

            // 检查是否是SNAPSHOT目录
            val isSnapshotDir = pathComponents.lastOrNull()?.contains("SNAPSHOT") == true

            // 检查是否匹配指定的group/artifact
            val isTargetGroupArtifact = when (targetGroupArtifact?.size) {
                2 -> {
                    val (group, artifact) = targetGroupArtifact
                    val artifactDir = versionDir.parentFile
                    val groupPath = artifactDir?.parentFile?.relativeTo(dir)?.path?.replace('\\', '/')
                    groupPath == group && artifactDir.name == artifact
                }
                1 -> {
                    val group = targetGroupArtifact[0]
                    relativePath.startsWith(group)
                }
                else -> {
                    true
                }
            }

            // 检查是否有lastUpdated文件（失效包）
            val hasLastUpdated = hasLastUpdatedFiles(versionDir)

            // 筛选逻辑
            val shouldInclude = when {
                // 如果是SNAPSHOT但目录不包含SNAPSHOT，则排除
                includeSnapshot && !isSnapshotDir -> false
                // 如果指定了groupId:artifactId但不匹配，则排除
                groupArtifact != null && !isTargetGroupArtifact -> false
                // 如果既不包含SNAPSHOT也不指定groupId:artifactId也不包含lastUpdated，则排除
                !includeSnapshot && groupArtifact == null && !hasLastUpdated -> false
                else -> true
            }

            // 设置匹配类型
            val matchType = when {
                hasLastUpdated -> "invalid"
                groupArtifact != null && isTargetGroupArtifact -> "matched"
                includeSnapshot && isSnapshotDir -> "snapshot"
                else -> "invalid"
            }

            if (shouldInclude) {
                onDirFound(versionDir, matchType)
            }
        }
    }

    // 检查目录是否包含lastUpdated文件
    private fun hasLastUpdatedFiles(dir: File): Boolean {
        return dir.listFiles()?.any {
            it.isFile && (it.name.endsWith(".jar.lastUpdated") || it.name.endsWith(".pom.lastUpdated"))
        } ?: false
    }

    // 只扫描失效包
    private fun scanForFailedDownloadsOnly(dir: File, dirsToProcess: MutableSet<File>) {
        // 使用更高效的方式查找lastUpdated文件
        dir.walk()
            .filter { it.isFile && (it.name.endsWith(".jar.lastUpdated") || it.name.endsWith(".pom.lastUpdated")) }
            .forEach { file ->
                val versionDir = file.parentFile
                if (versionDir != null) {
                    dirsToProcess.add(versionDir)
                }
            }
    }

    // 递归查找所有Maven版本目录
    private fun findAllMavenVersionDirs(dir: File, result: MutableSet<File>) {
        if (!dir.isDirectory) return

        // 检查当前目录是否是有效的Maven版本目录
        if (isValidMavenVersionDir(dir)) {
            result.add(dir)
            return // 如果是版本目录，不再向下递归
        }

        // 递归查找子目录
        dir.listFiles()?.filter { it.isDirectory }?.forEach { subDir ->
            findAllMavenVersionDirs(subDir, result)
        }
    }

    // 查找匹配的groupId目录
    private fun findMatchingGroupDirectories(baseDir: File, groupParts: List<String>): List<File> {
        // 如果是空列表，返回空结果
        if (groupParts.isEmpty()) return emptyList()

        // 递归查找匹配的目录
        return findMatchingDirectoriesRecursive(baseDir, groupParts, 0)
    }

    // 递归查找匹配的目录
    private fun findMatchingDirectoriesRecursive(currentDir: File, parts: List<String>, index: Int): List<File> {
        // 如果已经处理完所有部分，返回当前目录
        if (index >= parts.size) {
            return listOf(currentDir)
        }

        val currentPart = parts[index]
        val results = mutableListOf<File>()

        // 获取当前目录下的所有子目录
        val subDirs = currentDir.listFiles()?.filter { it.isDirectory } ?: return emptyList()

        // 查找匹配当前部分的目录
        val matchingDirs = subDirs.filter {
            // 如果是最后一部分，使用前缀匹配；否则使用精确匹配
            if (index == parts.size - 1) {
                it.name.startsWith(currentPart)
            } else {
                it.name == currentPart
            }
        }

        // 对于每个匹配的目录，继续递归查找
        for (dir in matchingDirs) {
            if (index == parts.size - 1) {
                // 如果是最后一部分，添加当前目录
                results.add(dir)
            } else {
                // 否则继续递归
                results.addAll(findMatchingDirectoriesRecursive(dir, parts, index + 1))
            }
        }

        return results
    }

    override fun previewCleanup(includeSnapshot: Boolean, groupArtifact: String?): CleanupSummary {
        val repoDir = File(getLocalRepository(false))
        val previewItems = mutableListOf<CleanupPreview>()
        var totalSize = 0L

        scanRepository(repoDir, { versionDir, matchType ->
            val dirSize = calculateDirSize(versionDir)
            val (packageName, version, relativePath) = extractPackageInfo(versionDir)

            previewItems.add(
                CleanupPreview(
                    path = versionDir.absolutePath,
                    packageName = "$packageName:$version",
                    fileSize = dirSize,
                    lastModified = versionDir.lastModified(),
                    dependencyType = DependencyType.MAVEN,
                    relativePath = relativePath,
                    matchType = matchType
                )
            )
            totalSize += dirSize
        }, includeSnapshot, groupArtifact)

        return CleanupSummary(
            totalFiles = previewItems.size, totalSize = totalSize, previewItems = previewItems
        )
    }

    // 计算目录大小的辅助方法
    private fun calculateDirSize(dir: File): Long {
        var size = 0L
        dir.walkTopDown().forEach { file ->
            if (file.isFile) {
                size += file.length()
            }
        }
        return size
    }

    override fun cleanupRepository(
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
                        // 删除整个目录
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
                            errorMessage = if (!success) "Failed to delete directory" else null
                        )
                    )

                    onProgress(index + 1, totalItems)
                }
                onComplete(results)
            }
        }.queue()
    }

    private fun extractPackageInfo(file: File): Triple<String, String, String> {
        val repoPath = getLocalRepository(false).replace('\\', '/')
        val filePath = file.absolutePath.replace(
                '\\',
                '/'
            )
        val relativePath = filePath.removePrefix(repoPath).trimStart('/')

        val components = relativePath.split("/").filter { it.isNotEmpty() }

        return when {
            components.size >= 3 -> {
                // 正常的Maven目录结构：groupId/artifactId/version
                val version = components.last()
                val artifactId = components[components.size - 2]
                val groupId = components.dropLast(2).joinToString(".")
                Triple("$groupId:$artifactId", version, relativePath)
            }

            components.size == 2 -> {
                // 简化的结构：artifactId/version
                Triple(components[0], components[1], relativePath)
            }

            else -> {
                // 异常情况，使用目录名作为标识
                Triple(file.parentFile?.name ?: "unknown", file.name, relativePath)
            }
        }
    }
}