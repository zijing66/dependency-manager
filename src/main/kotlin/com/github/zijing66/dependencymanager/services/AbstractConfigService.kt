package com.github.zijing66.dependencymanager.services

import com.github.zijing66.dependencymanager.models.PkgData
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
                    } else {
                        packagePathMap[packageInfo.relativePath]?.invalid = isTargetInvalidFile(file)
                    }
                }
            }
        }
    }
}