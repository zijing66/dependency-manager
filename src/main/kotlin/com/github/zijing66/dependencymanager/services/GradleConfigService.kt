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
        dir: File, onDirFound: (File, String) -> Unit, includeSnapshot: Boolean = false, groupArtifact: String? = null
    ) {
        val targetGroupArtifact = groupArtifact?.split(":")

        if (!includeSnapshot && groupArtifact == null) {
            // 在IDEA中提示用户
            Messages.showErrorDialog(
                project,
                "Please choose Snapshot or input Group/Artifact",
                "Input Error"
            )
            return
        }

        dir.walk().forEach { versionDir ->
            // 验证这是一个有效的Maven依赖版本目录
            if (!isValidGradleVersionDir(versionDir)) {
                return@forEach
            }

            val relativePath = versionDir.relativeTo(dir).path.replace('\\', '/')
            val pathComponents = relativePath.split('/')

            // 检查是否是SNAPSHOT目录
            val isSnapshotDir = pathComponents.lastOrNull()?.contains("SNAPSHOT") == true

            // 检查是否匹配指定的group/artifact
            val isTargetGroupArtifact = when (targetGroupArtifact?.size) {
                2 -> {
                    // 完整的groupId:artifactId
                    val (group, artifact) = targetGroupArtifact
                    val artifactDir = versionDir.parentFile
                    val groupPath = artifactDir.parentFile
                    groupPath.name == group && artifactDir.name == artifact
                }
                1 -> {
                    // 只有groupId，可能是部分输入
                    val group = targetGroupArtifact[0]
                    relativePath.startsWith(group)
                }
                else -> {
                    true
                }
            }
            // 筛选逻辑
            val shouldInclude = when {
                // 如果是SNAPSHOT但目录不包含SNAPSHOT，则排除
                includeSnapshot && !isSnapshotDir -> false
                // 如果指定了groupId:artifactId但不匹配，则排除
                groupArtifact != null && !isTargetGroupArtifact -> false
                // 其他情况都包含
                else -> true
            }
            // 设置匹配类型
            val matchType = when {
                groupArtifact != null && isTargetGroupArtifact -> "matched"
                includeSnapshot && isSnapshotDir -> "snapshot"
                else -> "invalid"
            }

            if (shouldInclude) {
                onDirFound(versionDir, matchType)
            }
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

        scanRepository(repoDir, { versionDir, matchType ->
            val dirSize = calculateDirSize(versionDir)
            val (packageName, version, relativePath) = extractPackageInfo(versionDir)

            previewItems.add(
                CleanupPreview(
                    path = versionDir.absolutePath,
                    packageName = "$packageName:$version",
                    fileSize = dirSize,
                    lastModified = versionDir.lastModified(),
                    dependencyType = DependencyType.GRADLE,
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

}