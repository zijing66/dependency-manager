package com.github.zijing66.dependencymanager.services

import com.github.zijing66.dependencymanager.models.CleanupPreview
import com.github.zijing66.dependencymanager.models.CleanupResult
import com.github.zijing66.dependencymanager.models.CleanupSummary
import com.github.zijing66.dependencymanager.models.DependencyType
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import java.io.File
import java.util.Properties
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.ui.Messages

@Service(Service.Level.PROJECT)
class GradleConfigService(project: Project) : AbstractConfigService(project) {

    private var customRepoPath: String? = null

    private fun resolveGradleUserHome(): String {
        // 从gradle-wrapper.properties解析GRADLE_USER_HOME
        val wrapperFile = findGradleWrapper()
        var distributionBase = System.getenv("GRADLE_USER_HOME") ?: "${System.getProperty("user.home")}/.gradle"
        if (wrapperFile != null) {
            val properties = Properties().apply { wrapperFile.inputStream().use(::load) }
            val tempDistributionBase = properties.getProperty("distributionBase")
            if (tempDistributionBase != "GRADLE_USER_HOME") {
                distributionBase = tempDistributionBase
            }
        }
        return distributionBase
    }

    private fun findGradleWrapper(): File? {
        // 获取项目的gradle-wrapper.properties文件路径
        val basePath = project.basePath ?: throw RuntimeException("Project base path is null")
        val wrapperFile = File("$basePath/gradle/wrapper/gradle-wrapper.properties")
        return if (wrapperFile.exists()) wrapperFile else null
    }

    override fun getLocalRepository(refresh: Boolean): String {
        customRepoPath?.takeIf { !refresh && isValidRepoPath(it) }?.let { return it }

        // 1. 从项目路径下的 gradle.properties 获取 org.gradle.cache.dir
        val projectPropertiesFile = File("${project.basePath}/gradle.properties")
        if (projectPropertiesFile.exists()) {
            Properties().apply {
                load(projectPropertiesFile.inputStream())
                getProperty("org.gradle.cache.dir")?.takeIf { isValidRepoPath(it) }?.let {
                    return "$it/caches/modules-2/files-2.1".replace('\\', '/')
                }
            }
        }

        // 2. 检查用户目录下的 gradle.properties
        val userPropertiesFile = File("${System.getProperty("user.home")}/.gradle/gradle.properties")
        if (userPropertiesFile.exists()) {
            Properties().apply {
                load(userPropertiesFile.inputStream())
                getProperty("org.gradle.cache.dir")?.takeIf { isValidRepoPath(it) }?.let {
                    return "$it/caches/modules-2/files-2.1".replace('\\', '/')
                }
            }
        }

        // 3. 使用环境变量 GRADLE_USER_HOME
        val gradleUserHome = resolveGradleUserHome()
        return "$gradleUserHome/caches/modules-2/files-2.1".replace('\\', '/')
    }

    private fun scanRepository(
        dir: File, onDirFound: (File) -> Unit, includeSnapshot: Boolean = false, groupArtifact: String? = null
    ) {
        val processedDirs = mutableSetOf<String>()
        val targetGroupArtifact = groupArtifact?.split(":")?.map { it.replace('.', '/') }
        val dirsToProcess = mutableSetOf<File>()

        when {
            groupArtifact != null && targetGroupArtifact != null ->
                scanByGroupArtifact(dir, targetGroupArtifact, dirsToProcess)
            includeSnapshot ->
                scanForSnapshot(dir, dirsToProcess)
            else -> {
                // 在IDEA中提示用户
                Messages.showErrorDialog(
                    project,
                    "Please choose Snapshot or input Group/Artifact",
                    "Input Error"
                )
            }
        }

        dirsToProcess.forEach { versionDir ->
            // 如果目录已经处理过，跳过
            if (!processedDirs.add(versionDir.absolutePath)) return@forEach
            if (!isValidGradleVersionDir(versionDir)) return@forEach

            val relativePath = versionDir.relativeTo(dir).path.replace('\\', '/')
            val pathComponents = relativePath.split('/')
            val isSnapshotDir = pathComponents.lastOrNull()?.contains("SNAPSHOT") == true

            val shouldInclude = when {
                includeSnapshot && !isSnapshotDir -> false
                else -> true
            }

            if (shouldInclude) onDirFound(versionDir)
        }
    }

    private fun isTargetGroupArtifact(versionDir: File, target: List<String>): Boolean {
        val parentDirs = versionDir.parentFile?.parentFile?.relativeTo(versionDir.parentFile?.parentFile?.parentFile!!)?.path
            ?.replace('\\', '/')?.split('/') ?: return false

        return when (target.size) {
            2 -> parentDirs.size >= 2 && 
                 parentDirs[parentDirs.size - 2] == target[0] && 
                 parentDirs.last() == target[1]
            1 -> parentDirs.any { it.contains(target[0]) }
            else -> false
        }
    }

    private fun isValidGradleVersionDir(dir: File): Boolean {
        if (!dir.isDirectory) return false

        // 检查第二级目录中的文件是否为jar或pom文件
        val validFilesExist = dir.listFiles()?.any { versionDir ->
            versionDir.isDirectory && versionDir.listFiles()?.any { file ->
                file.isFile && (file.name.endsWith(".jar") || file.name.endsWith(".pom"))
            } == true
        } ?: false

        // 检查目录结构：至少应该有两级父目录（groupId/artifactId/version）
        val hasValidStructure = dir.parentFile?.parentFile != null

        return validFilesExist && hasValidStructure
    }

    override fun updateLocalRepository(newPath: String) {
        if (isValidRepoPath(newPath)) {
            customRepoPath = newPath
        } else {
            throw IllegalArgumentException("Invalid repository path: $newPath")
        }
    }

    override fun previewCleanup(includeSnapshot: Boolean, groupArtifact: String?): CleanupSummary {
        val repoDir = File(getLocalRepository(false))
        val previewItems = mutableListOf<CleanupPreview>()
        var totalSize = 0L

        scanRepository(repoDir, { versionDir ->
            val dirSize = calculateDirSize(versionDir)
            val (packageName, version, relativePath) = extractPackageInfo(versionDir)

            previewItems.add(
                CleanupPreview(
                    path = versionDir.absolutePath,
                    packageName = "$packageName:$version",
                    fileSize = dirSize,
                    lastModified = versionDir.lastModified(),
                    dependencyType = DependencyType.GRADLE,
                    relativePath = relativePath
                )
            )
            totalSize += dirSize
        }, includeSnapshot, groupArtifact)

        return CleanupSummary(
            totalFiles = previewItems.size, totalSize = totalSize, previewItems = previewItems
        )
    }

    override fun cleanupRepository(
        project: Project,
        selectedItems: List<CleanupPreview>,
        onProgress: (Int, Int) -> Unit,
        onComplete: (List<CleanupResult>) -> Unit
    ) {
        object : Task.Backgroundable(project, "Cleaning up gradle downloads", true) {
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

    private fun calculateDirSize(dir: File): Long {
        var size = 0L
        dir.walkTopDown().forEach { file ->
            if (file.isFile) {
                size += file.length()
            }
        }
        return size
    }

    private fun extractPackageInfo(file: File): Triple<String, String, String> {
        val repoPath = getLocalRepository(false).replace('\\', '/')
        val filePath = file.absolutePath.replace('\\', '/')
        val relativePath = filePath.removePrefix(repoPath).trimStart('/')

        val components = relativePath.split("/").filter { it.isNotEmpty() }

        return when {
            components.size >= 3 -> {
                // 正常的Gradle目录结构：groupId/artifactId/version
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

    private fun scanByGroupArtifact(dir: File, targetGroupArtifact: List<String>, dirsToProcess: MutableSet<File>) {
        when (targetGroupArtifact.size) {
            2 -> {
                // 完整的groupId:artifactId
                val (group, artifact) = targetGroupArtifact
                if (group.isNotEmpty() && artifact.isNotEmpty()) {
                    val targetDir = File(dir, "$group${File.separator}$artifact")
                    if (targetDir.exists() && targetDir.isDirectory) {
                        // 只添加包含jar或pom文件的版本目录
                        targetDir.listFiles()?.filter { it.isDirectory }?.forEach { versionDir ->
                            if (isValidGradleVersionDir(versionDir)) {
                                dirsToProcess.add(versionDir)
                            }
                        }
                    }
                }
            }

            1 -> {
                // 只有groupId，可能是部分输入
                val group = targetGroupArtifact[0]
                if (group.isNotEmpty()) {
                    // 遍历目录，查找以group开头的文件夹
                    dir.listFiles()?.filter { it.isDirectory && it.name.startsWith(group) }?.forEach { groupDir ->
                        // 对于每个匹配的groupId目录，递归查找所有子目录下的Gradle版本目录
                        findAllGradleVersionDirs(groupDir, dirsToProcess)
                    }
                }
            }
        }
    }

    private fun scanForSnapshot(dir: File, dirsToProcess: MutableSet<File>) {
        // 再查找SNAPSHOT目录，但使用更高效的方式
        dir.walk().filter {
            it.isDirectory && it.name.contains("SNAPSHOT")
                    // 确保是版本目录，而不是中间目录
                    && isValidGradleVersionDir(it)
        }.forEach { dirsToProcess.add(it) }
    }

    private fun findMatchingGroupDirectories(baseDir: File, groupParts: List<String>): List<File> {
        // 如果是空列表，返回空结果
        if (groupParts.isEmpty()) return emptyList()

        // 递归查找匹配的目录
        return findMatchingDirectoriesRecursive(baseDir, groupParts, 0)
    }

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

    private fun findAllGradleVersionDirs(dir: File, result: MutableSet<File>) {
        if (!dir.isDirectory) return

        // 检查当前目录是否是有效的Gradle版本目录
        if (isValidGradleVersionDir(dir)) {
            result.add(dir)
            return // 如果是版本目录，不再向下递归
        }

        // 递归查找子目录
        dir.listFiles()?.filter { it.isDirectory }?.forEach { subDir ->
            findAllGradleVersionDirs(subDir, result)
        }
    }
}