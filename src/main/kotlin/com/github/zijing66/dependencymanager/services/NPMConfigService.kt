package com.github.zijing66.dependencymanager.services

import com.github.zijing66.dependencymanager.models.ConfigOptions
import com.github.zijing66.dependencymanager.models.DependencyType
import com.github.zijing66.dependencymanager.models.NPMPackageManager
import com.github.zijing66.dependencymanager.models.PkgData
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.util.*

@Service(Service.Level.PROJECT)
class NPMConfigService(project: Project) : AbstractConfigService(project) {

    private var customRepoPath: String? = null
    private var packageManager: NPMPackageManager = NPMPackageManager.NPM
    
    // NPM生态系统中常见的需要排除的目录
    private val commonNpmExclusions = listOf(
        "examples",      // 示例目录
        "docs",          // 文档目录
        "test",          // 测试目录
        "tests",         // 测试目录
        "__tests__",     // Jest测试目录
        "coverage"       // 测试覆盖率报告
    )

    /**
     * 判断NPM相关的目录是否应该被排除
     * @param dir 要检查的目录
     * @return 如果目录应该被排除则返回true
     */
    override fun shouldExcludeDirectory(dir: File): Boolean {
        // 首先检查是否是缓存目录的主要路径
        val isInCacheRoot = dir.path.contains("npm-cache") || 
                            dir.path.contains("Yarn/Cache") || 
                            dir.path.contains(".pnpm-store")
        
        // 如果是在缓存根目录下，我们应该保留主要的缓存文件夹
        if (isInCacheRoot) {
            // 仅排除一些明确不需要的文件夹
            if (dir.name == "examples" || 
                dir.name == "docs" || 
                dir.name == "test" || 
                dir.name == "tests" || 
                dir.name == "__tests__" || 
                dir.name == "coverage") {
                return true
            }
            return false
        }
        
        // 排除以点开头的目录（如.bin, .pnpm等）
        if (dir.name.startsWith(".") && dir.name != ".pnpm") {
            return true
        }
        
        // 排除commonNpmExclusions中的目录
        if (dir.name in commonNpmExclusions) {
            return true
        }
        
        // 检查是否是符号链接（特别是pnpm创建的链接）
        try {
            if (Files.isSymbolicLink(Paths.get(dir.absolutePath))) {
                // 如果是在node_modules目录下的符号链接，可能需要保留
                if (!dir.path.contains("node_modules")) {
                    return true // 非node_modules下的符号链接跳过
                }
            }
            
            // 特别处理pnpm的虚拟存储结构，但允许扫描主要的依赖目录
            if (dir.path.contains("node_modules") && packageManager == NPMPackageManager.PNPM) {
                // 只排除深层嵌套目录
                if (dir.path.contains("node_modules/node_modules/node_modules")) {
                    return true
                }
            }
            
            // 对于yarn berry（pnp模式），我们需要保留缓存目录
            if (dir.path.contains(".yarn/cache") && dir.path.contains("node_modules")) {
                return false // 确保扫描
            }
        } catch (e: Exception) {
            // 如果无法确定是否为符号链接，出于安全考虑不排除
            return false
        }
        
        return false
    }

    private fun getNpmCacheDirectory(): String {
        // 获取npm缓存目录
        val osName = System.getProperty("os.name").lowercase(Locale.getDefault())
        val userHome = System.getProperty("user.home")
        
        return when {
            osName.contains("win") -> {
                // Windows: npm缓存通常在 %APPDATA%/npm-cache
                val appData = System.getenv("APPDATA") ?: "$userHome/AppData/Roaming"
                "$appData/npm-cache"
            }
            osName.contains("mac") -> {
                // macOS: npm缓存通常在 ~/.npm
                "$userHome/.npm"
            }
            else -> {
                // Linux/Unix: npm缓存通常在 ~/.npm
                "$userHome/.npm"
            }
        }
    }

    private fun getPnpmCacheDirectory(): String {
        // 获取pnpm缓存目录
        val osName = System.getProperty("os.name").lowercase(Locale.getDefault())
        val userHome = System.getProperty("user.home")
        
        return when {
            osName.contains("win") -> {
                // Windows: pnpm存储通常在 %LOCALAPPDATA%/pnpm/store
                val localAppData = System.getenv("LOCALAPPDATA") ?: "$userHome/AppData/Local"
                "$localAppData/pnpm/store"
            }
            else -> {
                // macOS/Linux: pnpm存储通常在 ~/.pnpm-store
                "$userHome/.pnpm-store"
            }
        }
    }

    private fun getYarnCacheDirectory(): String {
        // 获取yarn缓存目录
        val osName = System.getProperty("os.name").lowercase(Locale.getDefault())
        val userHome = System.getProperty("user.home")
        
        return when {
            osName.contains("win") -> {
                // Windows: yarn缓存通常在 %LOCALAPPDATA%/Yarn/Cache
                val localAppData = System.getenv("LOCALAPPDATA") ?: "$userHome/AppData/Local"
                "$localAppData/Yarn/Cache"
            }
            osName.contains("mac") -> {
                // macOS: yarn缓存通常在 ~/Library/Caches/Yarn
                "$userHome/Library/Caches/Yarn"
            }
            else -> {
                // Linux: yarn缓存通常在 ~/.cache/yarn
                "$userHome/.cache/yarn"
            }
        }
    }

    private fun getCnpmCacheDirectory(): String {
        // cnpm缓存默认使用npm缓存
        return getNpmCacheDirectory()
    }

    private fun detectPackageManager(): NPMPackageManager {
        // 检测项目使用的包管理器
        val projectPath = project.basePath ?: return NPMPackageManager.NPM
        
        return when {
            File("$projectPath/yarn.lock").exists() -> NPMPackageManager.YARN
            File("$projectPath/pnpm-lock.yaml").exists() -> NPMPackageManager.PNPM
            File("$projectPath/.npmrc").exists() && 
                File("$projectPath/.npmrc").readText().contains("registry.cnpmjs.org") -> NPMPackageManager.CNPM
            else -> NPMPackageManager.NPM
        }
    }

    init {
        // 初始化时检测包管理器类型
        packageManager = detectPackageManager()
    }

    override fun getLocalRepository(refresh: Boolean): String {
        customRepoPath?.takeIf { !refresh && isValidRepoPath(it) }?.let { return it }

        // 首先检查node_modules目录
        val projectPath = project.basePath
        if (projectPath != null) {
            val nodeModulesPath = "$projectPath/node_modules"
            if (File(nodeModulesPath).exists() && isValidRepoPath(nodeModulesPath)) {
                return nodeModulesPath
            }
        }

        // 根据不同的包管理器获取缓存目录
        val cacheDir = when (packageManager) {
            NPMPackageManager.NPM -> {
                // 从环境变量或.npmrc获取npm缓存目录
                System.getenv("NPM_CACHE_DIR")?.takeIf { isValidRepoPath(it) }
                    ?: extractNpmCacheDir()
                    ?: getNpmCacheDirectory()
            }
            NPMPackageManager.YARN -> {
                // 从环境变量获取yarn缓存目录
                System.getenv("YARN_CACHE_FOLDER")?.takeIf { isValidRepoPath(it) }
                    ?: getYarnCacheDirectory()
            }
            NPMPackageManager.PNPM -> {
                // 从环境变量获取pnpm存储目录
                System.getenv("PNPM_STORE_DIR")?.takeIf { isValidRepoPath(it) }
                    ?: getPnpmCacheDirectory()
            }
            NPMPackageManager.CNPM -> {
                // cnpm通常使用npm的缓存
                System.getenv("CNPM_CACHE_DIR")?.takeIf { isValidRepoPath(it) }
                    ?: getCnpmCacheDirectory()
            }
        }
        
        return cacheDir
    }

    private fun extractNpmCacheDir(): String? {
        // 从项目下的.npmrc文件获取缓存目录
        val projectNpmrcFile = File("${project.basePath}/.npmrc")
        if (projectNpmrcFile.exists()) {
            val cacheDir = extractCacheDirFromNpmrc(projectNpmrcFile)
            if (cacheDir != null && isValidRepoPath(cacheDir)) {
                return cacheDir
            }
        }

        // 从用户主目录下的.npmrc文件获取缓存目录
        val userNpmrcFile = File("${System.getProperty("user.home")}/.npmrc")
        if (userNpmrcFile.exists()) {
            val cacheDir = extractCacheDirFromNpmrc(userNpmrcFile)
            if (cacheDir != null && isValidRepoPath(cacheDir)) {
                return cacheDir
            }
        }
        
        return null
    }

    private fun extractCacheDirFromNpmrc(npmrcFile: File): String? {
        val content = npmrcFile.readText()
        val cacheDirMatch = Regex("cache=(.+)").find(content)
        return cacheDirMatch?.groupValues?.get(1)?.trim()
    }

    override fun updateLocalRepository(newPath: String) {
        if (isValidRepoPath(newPath)) {
            customRepoPath = newPath
        } else {
            throw IllegalArgumentException("Invalid repository path: $newPath")
        }
    }

    override fun isTargetFile(file: File): Boolean {
        // 检查是否是npm相关文件
        return when (packageManager) {
            NPMPackageManager.NPM, NPMPackageManager.CNPM -> 
                file.isFile && (file.name.endsWith(".tgz") || file.name == "package.json")
            NPMPackageManager.YARN -> 
                file.isFile && (file.name.endsWith(".tgz") || file.name == "package.json" || 
                              file.name.endsWith(".zip"))
            NPMPackageManager.PNPM -> 
                file.isFile && (file.name.endsWith(".tgz") || file.name == "package.json" || 
                              file.name.contains(Regex("^[0-9a-f]{40}$"))) // pnpm内容寻址存储的哈希文件
        }
    }

    override fun isTargetInvalidFile(file: File): Boolean {
        // 检查是否有失效的包文件
        // 例如：package-lock.json与yarn.lock同时存在可能表示混合使用了多个包管理器
        val parentDir = file.parentFile
        return when {
            file.name == "package-lock.json" && File(parentDir, "yarn.lock").exists() -> true
            file.name == "yarn.lock" && File(parentDir, "pnpm-lock.yaml").exists() -> true
            // 检查损坏的包（例如下载未完成的包）
            file.name.endsWith(".tgz.tmp") || file.name.endsWith(".tgz.downloading") -> true
            else -> false
        }
    }

    override fun getTargetPackageInfo(rootDir: File, file: File): PkgData {
        // 获取包信息
        // 需要区分不同的目录结构和包管理器
        
        when (packageManager) {
            NPMPackageManager.NPM, NPMPackageManager.CNPM -> {
                return getNpmPackageInfo(rootDir, file)
            }
            NPMPackageManager.YARN -> {
                return getYarnPackageInfo(rootDir, file)
            }
            NPMPackageManager.PNPM -> {
                return getPnpmPackageInfo(rootDir, file)
            }
        }
    }
    
    private fun getNpmPackageInfo(rootDir: File, file: File): PkgData {
        val packageDir = if (file.name == "package.json") file.parentFile else file.parentFile
        val relativePath = packageDir?.relativeTo(rootDir)?.path?.replace('\\', '/')
        
        // 分析node_modules目录结构
        if (file.path.contains("node_modules")) {
            // node_modules目录下的包，可能是直接依赖也可能是嵌套依赖
            val pathParts = file.path.split(File.separator)
            val nodeModulesIndex = pathParts.indexOf("node_modules")
            
            if (nodeModulesIndex >= 0 && nodeModulesIndex < pathParts.size - 1) {
                // 处理作用域包 @scope/package-name
                var packageName = pathParts[nodeModulesIndex + 1]
                
                // 检查是否是作用域包（以@开头）
                if (packageName.startsWith("@") && nodeModulesIndex + 2 < pathParts.size) {
                    // 作用域包的完整路径：node_modules/@scope/package-name
                    packageName = "$packageName/${pathParts[nodeModulesIndex + 2]}"
                }
                
                // 尝试从package.json读取版本
                val packageJsonFile = File(packageDir, "package.json")
                val version = if (packageJsonFile.exists()) {
                    val content = packageJsonFile.readText()
                    val versionMatch = Regex("\"version\"\\s*:\\s*\"([^\"]+)\"").find(content)
                    versionMatch?.groupValues?.get(1) ?: "unknown"
                } else {
                    "unknown"
                }
                
                return PkgData(
                    relativePath = relativePath ?: "",
                    packageName = "$packageName${getPackageNameSeparator()}$version",
                    packageDir = packageDir
                )
            }
        }
        
        // 常规的缓存文件处理
        val packageName: String
        val version: String
        
        if (file.name == "package.json") {
            // 从package.json文件中提取名称和版本
            val content = file.readText()
            val nameMatch = Regex("\"name\"\\s*:\\s*\"([^\"]+)\"").find(content)
            val versionMatch = Regex("\"version\"\\s*:\\s*\"([^\"]+)\"").find(content)
            
            packageName = nameMatch?.groupValues?.get(1) ?: "unknown"
            version = versionMatch?.groupValues?.get(1) ?: "unknown"
        } else {
            // 从tgz文件名中提取名称和版本
            // 格式通常是: {name}-{version}.tgz
            val tgzNamePattern = Regex("(.*?)-(\\d+\\.\\d+\\.\\d+.*)?\\.tgz")
            val matchResult = tgzNamePattern.find(file.name)
            
            if (matchResult != null) {
                packageName = matchResult.groupValues[1]
                version = matchResult.groupValues[2]
            } else {
                packageName = file.nameWithoutExtension
                version = "unknown"
            }
        }
        
        return PkgData(
            relativePath = relativePath ?: "",
            packageName = "$packageName${getPackageNameSeparator()}$version",
            packageDir = packageDir
        )
    }
    
    private fun getYarnPackageInfo(rootDir: File, file: File): PkgData {
        // Yarn的缓存目录结构与npm略有不同
        // Yarn 1.x: .yarn-cache/[package-name]/[version]/[hash]/
        // Yarn 2.x+: .yarn/cache/[package-name]-[hash].zip
        
        val packageDir = file.parentFile
        val relativePath = packageDir?.relativeTo(rootDir)?.path?.replace('\\', '/')
        
        if (file.path.contains("node_modules")) {
            // 处理node_modules目录，与npm类似
            return getNpmPackageInfo(rootDir, file)
        }
        
        val packageName: String
        val version: String
        
        if (file.name.endsWith(".zip")) {
            // Yarn 2.x+的缓存文件，处理作用域包
            // 标准包格式: package-name-npm_version-npm-hash.zip
            // 作用域包格式: scope-package-name-npm_version-npm-hash.zip
            val zipNamePattern = Regex("(.*?)-(\\d+\\.\\d+\\.\\d+.*?)-(npm-[a-f0-9]+)\\.zip")
            val matchResult = zipNamePattern.find(file.name)
            
            if (matchResult != null && matchResult.groupValues.size >= 3) {
                val rawPackageName = matchResult.groupValues[1]
                version = matchResult.groupValues[2]
                
                // 检查是否是作用域包 (格式通常为 scope-package-name 或 @scope-package-name)
                if (rawPackageName.startsWith("@")) {
                    // 已经有@前缀的情况
                    val dashIndex = rawPackageName.indexOf('-')
                    if (dashIndex > 1) {
                        // 替换第一个'-'为'/'
                        packageName = rawPackageName.substring(0, dashIndex) + "/" + rawPackageName.substring(dashIndex + 1)
                    } else {
                        packageName = rawPackageName
                    }
                } else {
                    // 获取第一个'-'的位置，检查是否可能是作用域包
                    val firstDashIndex = rawPackageName.indexOf('-')
                    if (firstDashIndex > 0 && firstDashIndex < rawPackageName.length - 1) {
                        // 检查scope部分是否像作用域 (不太可靠，但可以作为启发式方法)
                        val possibleScope = rawPackageName.substring(0, firstDashIndex)
                        if (possibleScope.length <= 20 && !possibleScope.contains(".")) {
                            // 可能是作用域包
                            packageName = "@$possibleScope/${rawPackageName.substring(firstDashIndex + 1)}"
                        } else {
                            packageName = rawPackageName
                        }
                    } else {
                        packageName = rawPackageName
                    }
                }
            } else {
                // 如果没有匹配，尝试更简单的模式
                val simplePattern = Regex("(.*?)-(\\d+\\.\\d+\\.\\d+.*?)\\.zip")
                val simpleMatch = simplePattern.find(file.name)
                
                if (simpleMatch != null) {
                    packageName = simpleMatch.groupValues[1]
                    version = simpleMatch.groupValues[2]
                } else {
                    packageName = file.nameWithoutExtension
                    version = "unknown"
                }
            }
        } else if (file.name == "package.json") {
            // 从package.json读取信息
            val content = file.readText()
            val nameMatch = Regex("\"name\"\\s*:\\s*\"([^\"]+)\"").find(content)
            val versionMatch = Regex("\"version\"\\s*:\\s*\"([^\"]+)\"").find(content)
            
            packageName = nameMatch?.groupValues?.get(1) ?: "unknown"
            version = versionMatch?.groupValues?.get(1) ?: "unknown"
        } else {
            // 处理其他情况，如Yarn 1.x的缓存文件
            val parentDirs = generateSequence(packageDir) { it.parentFile }
                .take(4)
                .toList()
                .reversed()
            
            if (parentDirs.size >= 2) {
                // 检查是否是作用域包结构
                if (parentDirs.size >= 3 && parentDirs[0].name.startsWith("@")) {
                    packageName = "${parentDirs[0].name}/${parentDirs[1].name}"
                    version = parentDirs[2].name
                } else {
                    packageName = parentDirs[0].name
                    version = parentDirs[1].name
                }
            } else {
                packageName = packageDir.name
                version = "unknown"
            }
        }
        
        return PkgData(
            relativePath = relativePath ?: "",
            packageName = "$packageName${getPackageNameSeparator()}$version",
            packageDir = packageDir
        )
    }
    
    private fun getPnpmPackageInfo(rootDir: File, file: File): PkgData {
        // pnpm使用内容寻址存储，格式为.pnpm-store/[hash]
        // 或者是 node_modules/.pnpm/[package-name]@[version]/node_modules/[package-name]
        
        val packageDir = file.parentFile
        val relativePath = packageDir?.relativeTo(rootDir)?.path?.replace('\\', '/')
        
        // 分析是否在node_modules目录下
        if (file.path.contains("node_modules")) {
            // 检查是否是pnpm特有的结构
            // 处理标准包: node_modules/.pnpm/package-name@version/
            // 处理作用域包: node_modules/.pnpm/@scope+package-name@version/
            val pnpmPatternStandard = Regex("node_modules/\\.pnpm/([^@/]+)@([^/]+)/")
            val pnpmPatternScoped = Regex("node_modules/\\.pnpm/(@[^+]+)\\+([^@]+)@([^/]+)/")
            
            val pnpmPathNormalized = file.path.replace("\\", "/")
            val matchStandard = pnpmPatternStandard.find(pnpmPathNormalized)
            val matchScoped = pnpmPatternScoped.find(pnpmPathNormalized)
            
            if (matchScoped != null) {
                // 作用域包: @scope+package-name@version
                val scope = matchScoped.groupValues[1] // @scope
                val packageName = matchScoped.groupValues[2] // package-name
                val version = matchScoped.groupValues[3] // version
                
                return PkgData(
                    relativePath = relativePath ?: "",
                    packageName = "$scope/$packageName${getPackageNameSeparator()}$version",
                    packageDir = packageDir
                )
            } else if (matchStandard != null) {
                // 标准包: package-name@version
                val packageName = matchStandard.groupValues[1]
                val version = matchStandard.groupValues[2]
                
                return PkgData(
                    relativePath = relativePath ?: "",
                    packageName = "$packageName${getPackageNameSeparator()}$version",
                    packageDir = packageDir
                )
            }
            
            // 如果不是pnpm特有结构，则按常规node_modules处理
            return getNpmPackageInfo(rootDir, file)
        }
        
        // 处理pnpm存储中的文件
        val packageName: String
        val version: String
        
        if (file.name == "package.json") {
            // 从package.json读取信息
            val content = file.readText()
            val nameMatch = Regex("\"name\"\\s*:\\s*\"([^\"]+)\"").find(content)
            val versionMatch = Regex("\"version\"\\s*:\\s*\"([^\"]+)\"").find(content)
            
            packageName = nameMatch?.groupValues?.get(1) ?: "unknown"
            version = versionMatch?.groupValues?.get(1) ?: "unknown"
        } else if (file.name.matches(Regex("^[0-9a-f]{40}$"))) {
            // 对于内容寻址存储的哈希文件，尝试找到相关的package.json
            val packageJsonFile = File(packageDir, "package.json")
            if (packageJsonFile.exists()) {
                return getPnpmPackageInfo(rootDir, packageJsonFile)
            }
            
            // 如果找不到package.json，则使用哈希作为包名
            packageName = "content-addressed"
            version = file.name
        } else {
            // 处理其他情况（如.tgz文件）
            val tgzNamePattern = Regex("(.*?)-(\\d+\\.\\d+\\.\\d+.*)?\\.tgz")
            val matchResult = tgzNamePattern.find(file.name)
            
            if (matchResult != null) {
                packageName = matchResult.groupValues[1]
                version = matchResult.groupValues[2]
            } else {
                packageName = file.nameWithoutExtension
                version = "unknown"
            }
        }
        
        return PkgData(
            relativePath = relativePath ?: "",
            packageName = "$packageName${getPackageNameSeparator()}$version",
            packageDir = packageDir
        )
    }

    override fun getDependencyType(): DependencyType {
        return DependencyType.NPM
    }

    /**
     * 实现新的 scanRepository 方法，使用 ConfigOptions 对象
     */
    override fun scanRepository(
        dir: File, onDirFound: (File, String, PkgData) -> Unit, configOptions: ConfigOptions
    ) {
        val targetPackageName = configOptions.targetPackage.takeIf { it.isNotEmpty() }
        val separator = getPackageNameSeparator()

        val pathPkgDataMap = fetchPkgMap(dir)

        pathPkgDataMap.forEach { (path, pkgData) ->
            // 检查是否是invalid文件（损坏或未完成的下载）
            val isInvalidPackage = pkgData.invalid
            
            // 检查是否是snapshot版本（预发布版本）
            // npm预发布版本通常带有后缀如: alpha, beta, rc, dev等
            val isSnapshotVersion = pkgData.packageName.contains(Regex("\\d+\\.\\d+\\.\\d+-(alpha|beta|rc|dev|next|canary|experimental|snapshot|preview)"))
            
            // 检查是否匹配指定的包名
            val isTargetPackage = when {
                targetPackageName == null -> false // 如果没有指定包名，则不视为匹配
                pkgData.packageName.startsWith("$targetPackageName$separator") -> true
                pkgData.packageName.contains(separator) && 
                    pkgData.packageName.split(separator)[0] == targetPackageName -> true
                // 处理带有'/'的包名 - 作用域包的情况，比如@scope/package-name
                pkgData.packageName.contains("/") && pkgData.packageName.contains(separator) &&
                    pkgData.packageName.split(separator)[0].split("/").last() == targetPackageName -> true
                else -> false
            }
            
            // 检查是否包含版本号
            val hasVersion = pkgData.packageName.contains(separator) && 
                            !pkgData.packageName.endsWith("${separator}unknown") && 
                            !pkgData.packageName.endsWith(separator) &&
                            pkgData.packageName.split(separator).size > 1

            // 特殊处理win32-x64等平台特定目录
            val isNativeBinary = pkgData.packageDir.name.matches(Regex("(win32|darwin|linux)-(x64|arm64|ia32)"))
            
            // 处理平台特定目录中的二进制文件
            val hasBinaryFiles = if (isNativeBinary) {
                val binaryFiles = pkgData.packageDir.listFiles { file -> 
                    file.isFile && (file.name.endsWith(".exe") || file.name.endsWith(".dll") || 
                                  file.name.endsWith(".so") || file.name.endsWith(".dylib") ||
                                  file.extension.isEmpty() && file.canExecute()) 
                }
                binaryFiles?.isNotEmpty() ?: false
            } else false
            
            // 如果是平台特定目录，并且有效，补充包名信息
            val adjustedPkgData = if (isNativeBinary && (hasBinaryFiles || pkgData.packageDir.listFiles()?.isNotEmpty() == true) && configOptions.showPlatformSpecificBinaries) {
                // 尝试从上级目录确定包名
                val parentDirPath = pkgData.packageDir.parentFile?.path ?: ""
                val packageRoot = File(parentDirPath)
                val packageJsonFile = File(packageRoot, "package.json")
                
                if (packageJsonFile.exists()) {
                    // 从package.json读取包名和版本
                    val content = packageJsonFile.readText()
                    val nameMatch = Regex("\"name\"\\s*:\\s*\"([^\"]+)\"").find(content)
                    val versionMatch = Regex("\"version\"\\s*:\\s*\"([^\"]+)\"").find(content)
                    
                    val actualPackageName = nameMatch?.groupValues?.get(1) ?: pkgData.packageName.split(separator)[0]
                    val actualVersion = versionMatch?.groupValues?.get(1) ?: 
                                      if (pkgData.packageName.contains(separator)) pkgData.packageName.split(separator)[1] else "unknown"
                    
                    // 创建新的PkgData并附加平台信息
                    PkgData(
                        relativePath = pkgData.relativePath,
                        packageName = "$actualPackageName${separator}$actualVersion (${pkgData.packageDir.name})",
                        packageDir = pkgData.packageDir,
                        invalid = pkgData.invalid
                    )
                } else {
                    // 如果找不到package.json，保留原始包名但标记为平台特定版本
                    val parts = pkgData.packageName.split(separator)
                    val name = parts[0]
                    val version = if (parts.size > 1) parts[1] else "unknown"
                    
                    PkgData(
                        relativePath = pkgData.relativePath,
                        packageName = "$name${separator}$version (${pkgData.packageDir.name})",
                        packageDir = pkgData.packageDir,
                        invalid = pkgData.invalid
                    )
                }
            } else pkgData
            
            // 筛选逻辑：包含符合条件的包（指定的目标包名、快照版本、平台特定或无效包）
            val shouldInclude = when {
                configOptions.targetPackage.isNotEmpty() && isTargetPackage -> true
                configOptions.showPlatformSpecificBinaries && isNativeBinary -> true
                configOptions.includeSnapshot && isSnapshotVersion -> true
                configOptions.showInvalidPackages && isInvalidPackage -> true
                else -> false
            }

            // 设置匹配类型 - 调整展示优先级
            val matchType = when {
                isInvalidPackage -> "invalid"
                isTargetPackage -> "matched"
                isNativeBinary -> "native"
                isSnapshotVersion -> "prerelease"
                else -> "unknown" // 理论上不应该有unknown类型被包含
            }

            if (shouldInclude) {
                onDirFound(adjustedPkgData.packageDir, matchType, adjustedPkgData)
            }
        }
    }

    /**
     * 覆盖父类方法，返回@作为NPM包的分隔符
     */
    override fun getPackageNameSeparator(): String {
        return "@"
    }
} 