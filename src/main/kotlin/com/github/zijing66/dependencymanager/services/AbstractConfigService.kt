package com.github.zijing66.dependencymanager.services

import com.github.zijing66.dependencymanager.models.*
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import java.io.File

abstract class AbstractConfigService(project: Project) : IConfigService {

    protected val project: Project = project

    // 最大遍历深度，防止无限递归
    private val MAX_DIRECTORY_DEPTH = 20

    /**
     * 获取包名与版本号之间的分隔符
     * 默认使用冒号作为分隔符，子类可以覆盖此方法提供不同的分隔符
     * @return 分隔符字符串
     */
    protected open fun getPackageNameSeparator(): String {
        return ":"
    }

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

    /**
     * 扫描仓库目录，根据配置选项查找符合条件的包
     * @param dir 要扫描的目录
     * @param onDirFound 当找到符合条件的目录时的回调
     * @param configOptions 配置选项对象，包含过滤条件
     */
    abstract fun scanRepository(
        dir: File,
        onDirFound: (File, String, PkgData) -> Unit,
        configOptions: ConfigOptions
    )
    
    /**
     * 预览清理操作实现
     * @param configOptions 配置选项，包含 includeSnapshot, showInvalidPackages, showPlatformSpecificBinaries 等设置
     */
    override fun previewCleanup(configOptions: ConfigOptions): CleanupSummary {
        val repoDir = File(getLocalRepository(false))
        val previewItems = mutableListOf<CleanupPreview>()
        var totalSize = 0L

        // 使用新的 scanRepository 接口
        scanRepository(repoDir, { versionDir, matchType, pkgData ->
            val dirSize = calculateDirSize(versionDir)
            
            previewItems.add(
                CleanupPreview(
                    path = versionDir.absolutePath,
                    packageName = pkgData.packageName,
                    fileSize = dirSize,
                    lastModified = versionDir.lastModified(),
                    dependencyType = getDependencyType(),
                    relativePath = pkgData.relativePath,
                    matchType = matchType
                )
            )
            totalSize += dirSize
        }, configOptions)

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
        travelDirectory(rootDir, rootDir, pathPkgDataMap, 0)
        return pathPkgDataMap
    }

    /**
     * 判断文件夹是否应该被排除
     * @param dir 要检查的文件夹
     * @return 如果文件夹应该被排除则返回true
     */
    protected abstract fun shouldExcludeDirectory(dir: File): Boolean

    private fun travelDirectory(rootDir: File, startDir: File, packagePathMap: MutableMap<String, PkgData>, depth: Int = 0) {
        // 防止过深目录遍历
        if (depth > MAX_DIRECTORY_DEPTH) {
            return
        }

        startDir.listFiles()?.forEach { file ->
            if (file.isDirectory()) {
                // 使用shouldExcludeDirectory检查目录是否应被排除
                if (shouldExcludeDirectory(file)) {
                    return@forEach
                }
                travelDirectory(rootDir, file, packagePathMap, depth + 1) // 递归调用并增加深度计数
            } else {
                val relativePath = file?.parentFile?.relativeTo(rootDir)?.path?.replace('\\', '/')
                if (relativePath?.isNotBlank()!! && isTargetFile(file)) {
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
}