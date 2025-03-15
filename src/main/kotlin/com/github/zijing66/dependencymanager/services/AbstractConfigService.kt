package com.github.zijing66.dependencymanager.services

import com.github.zijing66.dependencymanager.models.*
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
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

    abstract fun isTargetFile(file: File): Boolean

    abstract fun isTargetInvalidFile(file: File): Boolean

    abstract fun getTargetPackageInfo(rootDir: File, file: File): PkgData

    abstract fun scanRepository(
        dir: File,
        onDirFound: (File, String) -> Unit,
        includeSnapshot: Boolean = false,
        groupArtifact: String? = null
    )

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
                    dependencyType = getDependencyType(),
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
        object : Task.Backgroundable(project, "Cleaning up ${getDependencyType().name} downloads", true) {
            override fun run(indicator: ProgressIndicator) {
                val results = mutableListOf<CleanupResult>()
                val totalItems = selectedItems.size

                selectedItems.forEachIndexed { index, item ->
                    indicator.fraction = index.toDouble() / totalItems
                    indicator.text = "Cleaning up: ${item.packageName}"

                    val file = File(item.path)
                    var success = true
                    if (item.matchType == "invalid") {
                        // 无效的目录，删除无效文件
                        file.listFiles()?.forEach { subFile ->
                            if (isTargetInvalidFile(subFile)) {
                                success = subFile.delete() && success
                            }
                        }
                    } else {
                        // 其他情况，删除指定文件或目录
                        success = if (file.isDirectory) {
                            // 删除整个目录
                            file.deleteRecursively()
                        } else {
                            file.delete()
                        }
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

    protected fun fetchPkgMap(rootDir: File): MutableMap<String, PkgData> {
        if (!rootDir.isDirectory()) {
            return mutableMapOf()
        }
        val pathPkgDataMap = mutableMapOf<String, PkgData>()
        travelDirectory(rootDir, rootDir, pathPkgDataMap)
        return pathPkgDataMap
    }

    private fun travelDirectory(rootDir: File, startDir: File, packagePathMap: MutableMap<String, PkgData>) {
        startDir.listFiles()?.forEach { file ->
            if (file.isDirectory()) {
                travelDirectory(rootDir, file, packagePathMap) // 递归调用
            } else {
                val relativePath = file?.parentFile?.relativeTo(rootDir)?.path?.replace('\\', '/')
                if (isTargetFile(file)) {
                    if (packagePathMap[relativePath]?.invalid == true) {
                        return@forEach
                    }
                    val packageInfo = getTargetPackageInfo(rootDir, file)
                    if (!packagePathMap.containsKey(relativePath)) {
                        packagePathMap[packageInfo.relativePath] = packageInfo
                    }
                    packagePathMap[packageInfo.relativePath]?.invalid = isTargetInvalidFile(file)
                }
            }
        }
    }

    // 计算目录大小的辅助方法
    protected fun calculateDirSize(dir: File): Long {
        var size = 0L
        dir.walkTopDown().forEach { file ->
            if (file.isFile) {
                size += file.length()
            }
        }
        return size
    }

    protected fun extractPackageInfo(file: File): Triple<String, String, String> {
        val repoPath = getLocalRepository(false).replace('\\', '/')
        val filePath = file.absolutePath.replace('\\', '/')
        val relativePath = filePath.removePrefix(repoPath).trimStart('/')

        val components = relativePath.split("/").filter { it.isNotEmpty() }

        return when {
            components.size >= 3 -> {
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