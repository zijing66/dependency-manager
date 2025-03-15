package com.github.zijing66.dependencymanager.services

import com.github.zijing66.dependencymanager.models.ConfigOptions
import com.github.zijing66.dependencymanager.models.DependencyType
import com.github.zijing66.dependencymanager.models.PkgData
import com.github.zijing66.dependencymanager.models.PythonEnvironmentType
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.util.*

@Service(Service.Level.PROJECT)
class PIPConfigService(project: Project) : AbstractConfigService(project) {

    private var customRepoPath: String? = null
    private var environmentType: PythonEnvironmentType = PythonEnvironmentType.SYSTEM
    private var environmentPath: String? = null

    // Python环境中常见的需要排除的目录
    private val commonPythonExclusions = listOf(
        "__pycache__",   // Python缓存目录
        ".pytest_cache",  // Pytest缓存
        "dist",          // 分发目录
        "docs",          // 文档
        "tests",         // 测试目录
        "test"           // 测试目录
    )

    /**
     * 判断Python相关的目录是否应该被排除
     * @param dir 要检查的目录
     * @return 如果目录应该被排除则返回true
     */
    override fun shouldExcludeDirectory(dir: File): Boolean {
        // 如果是site-packages目录下的文件，应该保留
        if (dir.path.contains("site-packages")) {
            // 仅排除site-packages下的__pycache__和测试目录
            return dir.name == "__pycache__" || 
                   dir.name == "tests" || 
                   dir.name == "test" || 
                   dir.name == ".pytest_cache"
        }
        
        // 排除以点开头的目录（如.venv等）
        if (dir.name.startsWith(".")) {
            return true
        }
        
        // 排除commonPythonExclusions中的目录
        if (dir.name in commonPythonExclusions) {
            return true
        }
        
        // 检查是否是符号链接
        try {
            if (Files.isSymbolicLink(Paths.get(dir.absolutePath))) {
                return true // 跳过所有符号链接目录
            }
            
            // 特别处理Python虚拟环境中的特殊情况
            when (environmentType) {
                PythonEnvironmentType.VENV, PythonEnvironmentType.PIPENV -> {
                    // 虚拟环境中的lib/python*/site-packages/pkg_resources目录可能包含大量符号链接
                    if (dir.path.contains("site-packages") && 
                        (dir.name == "pkg_resources" || dir.name == ".nspkg-patches")) {
                        return true
                    }
                }
                PythonEnvironmentType.CONDA -> {
                    // Conda环境中的pkgs目录包含缓存的包，不需要遍历
                    if (dir.name == "pkgs" && dir.path.contains("conda") && dir.path.contains("envs")) {
                        return true
                    }
                    // conda-meta目录包含元数据，不需要遍历
                    if (dir.name == "conda-meta") {
                        return true
                    }
                }
                else -> {
                    // 处理系统Python中的情况
                }
            }
            
            // 排除一些特殊工具创建的缓存目录
            if (dir.name.endsWith(".dist-info") && dir.path.contains("site-packages") &&
                Files.exists(Paths.get(dir.absolutePath, "INSTALLER")) &&
                Files.readAllLines(Paths.get(dir.absolutePath, "INSTALLER")).contains("pip")) {
                // 包含pip已安装标记的.dist-info目录不需要遍历内部文件
                return true
            }
        } catch (e: Exception) {
            // 如果无法确定是否为符号链接，出于安全考虑不排除
            return false
        }
        
        return false
    }

    init {
        // 初始化时检测Python环境类型
        detectPythonEnvironment()
    }

    private fun detectPythonEnvironment() {
        val projectPath = project.basePath ?: return
        
        // 检测项目中的虚拟环境
        when {
            // 检查venv目录
            File("$projectPath/venv").exists() || 
            File("$projectPath/.venv").exists() -> {
                environmentType = PythonEnvironmentType.VENV
                environmentPath = if (File("$projectPath/venv").exists()) "$projectPath/venv" else "$projectPath/.venv"
            }
            // 检查Pipenv
            File("$projectPath/Pipfile").exists() -> {
                environmentType = PythonEnvironmentType.PIPENV
                // 尝试获取Pipenv的虚拟环境路径
                val pipenvVenvPath = getPipenvVirtualEnvPath(projectPath)
                if (pipenvVenvPath != null) {
                    environmentPath = pipenvVenvPath
                }
            }
            // 检查Conda环境
            File("$projectPath/environment.yml").exists() || 
            File("$projectPath/conda-env.yml").exists() -> {
                environmentType = PythonEnvironmentType.CONDA
                // 尝试从conda配置文件中获取环境名称
                val condaEnvName = getCondaEnvironmentName(projectPath)
                if (condaEnvName != null) {
                    environmentPath = getCondaEnvironmentPath(condaEnvName)
                }
            }
            // 检查系统Python
            else -> {
                environmentType = PythonEnvironmentType.SYSTEM
                // 尝试查找系统Python路径
                val sitePackagesPath = findSystemPythonSitePackages()
                if (sitePackagesPath != null) {
                    environmentPath = File(sitePackagesPath).parentFile?.parentFile?.absolutePath
                }
            }
        }
    }

    private fun getPipenvVirtualEnvPath(projectPath: String): String? {
        // 尝试运行pipenv --venv命令获取路径
        try {
            val process = ProcessBuilder("pipenv", "--venv")
                .directory(File(projectPath))
                .redirectErrorStream(true)
                .start()
            
            val output = process.inputStream.bufferedReader().readText().trim()
            val exitCode = process.waitFor()
            
            if (exitCode == 0 && output.isNotEmpty()) {
                return output
            }
        } catch (e: Exception) {
            // 命令执行失败，忽略异常
        }
        
        // 尝试检查~/.local/share/virtualenvs目录
        val userHome = System.getProperty("user.home")
        val pipenvRoot = File("$userHome/.local/share/virtualenvs")
        if (pipenvRoot.exists() && pipenvRoot.isDirectory) {
            // 查找与项目名称相关的虚拟环境
            val projectName = File(projectPath).name
            val possibleEnvs = pipenvRoot.listFiles { file -> 
                file.isDirectory && file.name.startsWith(projectName) 
            }
            
            if (possibleEnvs?.isNotEmpty() == true) {
                return possibleEnvs[0].absolutePath
            }
        }
        
        return null
    }

    private fun getCondaEnvironmentName(projectPath: String): String? {
        // 从conda环境文件中读取环境名称
        val envFile = when {
            File("$projectPath/environment.yml").exists() -> File("$projectPath/environment.yml")
            File("$projectPath/conda-env.yml").exists() -> File("$projectPath/conda-env.yml")
            else -> return null
        }
        
        val content = envFile.readText()
        val nameMatch = Regex("name:\\s*([^\\s]+)").find(content)
        return nameMatch?.groupValues?.get(1)
    }

    private fun getCondaEnvironmentPath(envName: String): String? {
        // 尝试运行conda info命令获取conda环境根目录
        try {
            val process = ProcessBuilder("conda", "info", "--json")
                .redirectErrorStream(true)
                .start()
            
            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()
            
            if (exitCode == 0 && output.isNotEmpty()) {
                // 使用简单正则表达式解析JSON输出
                val envDirsMatch = Regex("\"envs_dirs\"\\s*:\\s*\\[([^\\]]+)\\]").find(output)
                val envDirsStr = envDirsMatch?.groupValues?.get(1) ?: return null
                
                // 解析环境目录列表
                val envDirs = envDirsStr.split(",")
                    .map { it.trim().trim('"') }
                    .filter { it.isNotEmpty() }
                
                // 在每个环境目录中查找指定的环境
                for (envDir in envDirs) {
                    val fullEnvPath = File("$envDir/$envName")
                    if (fullEnvPath.exists() && fullEnvPath.isDirectory) {
                        return fullEnvPath.absolutePath
                    }
                }
            }
        } catch (e: Exception) {
            // 命令执行失败，使用默认路径
        }
        
        // 如果无法通过conda命令获取，使用默认路径
        val userHome = System.getProperty("user.home")
        val defaultCondaPath = when {
            System.getProperty("os.name").lowercase(Locale.getDefault()).contains("win") -> 
                "$userHome/anaconda3/envs/$envName"
            else -> 
                "$userHome/anaconda3/envs/$envName"
        }
        
        return if (File(defaultCondaPath).exists()) defaultCondaPath else null
    }

    private fun getPipCacheDirectory(): String {
        // 获取pip标准缓存目录（非虚拟环境）
        val osName = System.getProperty("os.name").lowercase(Locale.getDefault())
        val userHome = System.getProperty("user.home")
        
        return when {
            osName.contains("win") -> {
                // Windows: pip缓存通常在 %LOCALAPPDATA%\pip\Cache
                val localAppData = System.getenv("LOCALAPPDATA") ?: "$userHome/AppData/Local"
                "$localAppData/pip/Cache"
            }
            osName.contains("mac") -> {
                // macOS: pip缓存通常在 ~/Library/Caches/pip
                "$userHome/Library/Caches/pip"
            }
            else -> {
                // Linux/Unix: pip缓存通常在 ~/.cache/pip
                "$userHome/.cache/pip"
            }
        }
    }

    private fun getVenvCacheDirectory(venvPath: String): String {
        // 虚拟环境中的pip缓存
        return "$venvPath/pip-cache"
    }

    private fun getCondaCacheDirectory(condaPath: String): String {
        // Conda环境中的pip缓存
        return "$condaPath/pip-cache"
    }

    private fun getPipenvCacheDirectory(pipenvPath: String): String {
        // Pipenv环境中的pip缓存
        return "$pipenvPath/.cache/pip"
    }

    override fun getLocalRepository(refresh: Boolean): String {
        customRepoPath?.takeIf { !refresh && isValidRepoPath(it) }?.let { return it }

        // 首先检查项目目录中的site-packages目录
        val projectPath = project.basePath
        if (projectPath != null) {
            // 根据环境类型查找包目录
            val sitePackagesPath = when (environmentType) {
                PythonEnvironmentType.VENV -> {
                    val venvPath = environmentPath ?: 
                                   if (File("$projectPath/venv").exists()) "$projectPath/venv" 
                                   else "$projectPath/.venv"
                    
                    // 检查标准路径结构
                    val libDir = if (File("$venvPath/lib").exists()) "lib" else "Lib"
                    val pythonDirs = File("$venvPath/$libDir").listFiles { file -> 
                        file.isDirectory && file.name.startsWith("python") 
                    }
                    
                    if (pythonDirs?.isNotEmpty() == true) {
                        "${pythonDirs[0].absolutePath}/site-packages"
                    } else {
                        // 如果没有找到标准结构，尝试更简单的路径
                        if (File("$venvPath/Lib/site-packages").exists()) {
                            "$venvPath/Lib/site-packages"
                        } else if (File("$venvPath/lib/site-packages").exists()) {
                            "$venvPath/lib/site-packages"
                        } else {
                            null
                        }
                    }
                }
                PythonEnvironmentType.CONDA -> {
                    val condaPath = environmentPath
                    if (condaPath != null) {
                        // 检查标准路径结构
                        val libDir = if (File("$condaPath/lib").exists()) "lib" else "Lib"
                        val pythonDirs = File("$condaPath/$libDir").listFiles { file -> 
                            file.isDirectory && file.name.startsWith("python") 
                        }
                        
                        if (pythonDirs?.isNotEmpty() == true) {
                            "${pythonDirs[0].absolutePath}/site-packages"
                        } else {
                            // 如果没有找到标准结构，尝试更简单的路径
                            val possiblePaths = listOf(
                                "$condaPath/Lib/site-packages",
                                "$condaPath/lib/site-packages",
                                "$condaPath/lib/python3.8/site-packages",  // 常见的Python版本路径
                                "$condaPath/lib/python3.9/site-packages", 
                                "$condaPath/lib/python3.10/site-packages",
                                "$condaPath/lib/python3.11/site-packages"
                            )
                            
                            possiblePaths.find { File(it).exists() && File(it).isDirectory } ?: null
                        }
                    } else {
                        null
                    }
                }
                PythonEnvironmentType.PIPENV -> {
                    val pipenvPath = environmentPath
                    if (pipenvPath != null) {
                        // 检查标准路径结构
                        val libDir = if (File("$pipenvPath/lib").exists()) "lib" else "Lib"
                        val pythonDirs = File("$pipenvPath/$libDir").listFiles { file -> 
                            file.isDirectory && file.name.startsWith("python") 
                        }
                        
                        if (pythonDirs?.isNotEmpty() == true) {
                            "${pythonDirs[0].absolutePath}/site-packages"
                        } else {
                            // 如果没有找到标准结构，尝试更简单的路径
                            val possiblePaths = listOf(
                                "$pipenvPath/Lib/site-packages",
                                "$pipenvPath/lib/site-packages",
                                "$pipenvPath/lib/python3.8/site-packages",
                                "$pipenvPath/lib/python3.9/site-packages",
                                "$pipenvPath/lib/python3.10/site-packages",
                                "$pipenvPath/lib/python3.11/site-packages"
                            )
                            
                            possiblePaths.find { File(it).exists() && File(it).isDirectory } ?: null
                        }
                    } else {
                        null
                    }
                }
                else -> null
            }
            
            if (sitePackagesPath != null && File(sitePackagesPath).exists() && isValidRepoPath(sitePackagesPath)) {
                return sitePackagesPath
            }
        }

        // 从环境变量获取pip缓存目录
        System.getenv("PIP_CACHE_DIR")?.takeIf { isValidRepoPath(it) }?.let { return it }

        // 根据环境类型获取缓存目录
        val cacheDir = when (environmentType) {
            PythonEnvironmentType.VENV -> {
                val venvPath = environmentPath
                if (venvPath != null) getVenvCacheDirectory(venvPath) else getPipCacheDirectory()
            }
            PythonEnvironmentType.CONDA -> {
                val condaPath = environmentPath
                if (condaPath != null) getCondaCacheDirectory(condaPath) else getPipCacheDirectory()
            }
            PythonEnvironmentType.PIPENV -> {
                val pipenvPath = environmentPath
                if (pipenvPath != null) getPipenvCacheDirectory(pipenvPath) else getPipCacheDirectory()
            }
            else -> {
                // 检查pip.conf或pip.ini中的配置
                extractPipConfigCacheDir() ?: getPipCacheDirectory()
            }
        }
        
        return cacheDir
    }

    private fun extractPipConfigCacheDir(): String? {
        // 检查pip.conf或pip.ini中的配置
        val pipConfigFile = when {
            System.getProperty("os.name").lowercase(Locale.getDefault()).contains("win") -> 
                File("${System.getenv("APPDATA") ?: "${System.getProperty("user.home")}/AppData/Roaming"}/pip/pip.ini")
            else -> 
                File("${System.getProperty("user.home")}/.config/pip/pip.conf")
        }

        if (pipConfigFile.exists()) {
            val cacheDir = extractCacheDirFromPipConfig(pipConfigFile)
            if (cacheDir != null && isValidRepoPath(cacheDir)) {
                return cacheDir
            }
        }
        
        return null
    }

    private fun extractCacheDirFromPipConfig(pipConfigFile: File): String? {
        val content = pipConfigFile.readText()
        val cacheDirMatch = Regex("cache-dir\\s*=\\s*(.+)").find(content)
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
        // 检查是否是pip包文件
        return when {
            // 标准分发格式
            file.isFile && (
                file.name.endsWith(".whl") ||       // Wheel 格式
                file.name.endsWith(".tar.gz") ||    // 源码分发包 (sdist)
                file.name.endsWith(".zip") ||       // Zip 格式分发包
                file.name.endsWith(".egg") ||       // Egg 二进制分发包
                file.name.endsWith(".tar.bz2") ||   // BZ2 压缩的源码包
                file.name.endsWith(".tar.xz")       // XZ 压缩的源码包
            ) -> true
            
            // 包元数据目录及文件
            file.name.endsWith(".egg-info") -> true        // 旧式 Egg 元数据
            file.name.endsWith(".dist-info") -> true       // 新式 Dist-info 元数据目录
            
            // 重要的元数据文件
            (file.name == "RECORD" || file.name == "METADATA" || file.name == "WHEEL") 
                && file.path.contains("dist-info") -> true  // Dist-info 中的关键文件
            
            file.name == "PKG-INFO" && (
                file.path.contains("egg-info") || 
                file.path.contains(".dist-info") || 
                file.parentFile?.name == "EGG-INFO"
            ) -> true  // 包信息文件
            
            // 安装目录中的关键文件
            file.name == "setup.py" && 
                (file.parentFile?.name?.contains("-") == true) -> true  // 源码包中的安装文件
            
            file.name == "direct_url.json" && 
                file.path.contains(".dist-info") -> true   // PEP 610 直接引用信息
                
            file.name == "installed-files.txt" && 
                file.path.contains(".egg-info") -> true    // 已安装文件列表
                
            file.name == "requires.txt" && 
                (file.path.contains(".egg-info") || file.path.contains(".dist-info")) -> true  // 依赖要求

            // 其他情况
            else -> false
        }
    }

    override fun isTargetInvalidFile(file: File): Boolean {
        // 检查是否有失效的包文件
        return file.isFile && (
            file.name.endsWith(".incomplete") || 
            file.name.endsWith(".whl.part") ||
            file.name.endsWith(".tar.gz.part")
        )
    }

    override fun getTargetPackageInfo(rootDir: File, file: File): PkgData {
        // 获取包信息
        val packageDir = file.parentFile
        val relativePath = packageDir?.relativeTo(rootDir)?.path?.replace('\\', '/')
        
        // 解析包名和版本
        var packageName: String
        var version: String
        
        when {
            // Wheel 格式
            file.name.endsWith(".whl") -> {
                // 从wheel文件名解析包名和版本
                // 格式通常是: {package_name}-{version}-{python_tag}-{abi_tag}-{platform_tag}.whl
                // 示例: numpy-1.24.3-cp39-cp39-win_amd64.whl
                val wheelPattern = Regex("(.*?)-(\\d+(?:\\.\\d+)*(?:[._-]?(?:dev|a|alpha|b|beta|rc|c|pre|preview|post|rev|r)\\d*)?(?:\\+[a-zA-Z0-9.]*)?)-(.+?)\\.whl$")
                val matchResult = wheelPattern.find(file.name)
                
                if (matchResult != null) {
                    packageName = matchResult.groupValues[1]
                    version = matchResult.groupValues[2]
                } else {
                    // 兼容性处理：尝试基础拆分
                    val parts = file.nameWithoutExtension.split("-")
                    if (parts.size >= 2) {
                        packageName = parts[0]
                        // 检查第二部分是否像个版本号
                        version = if (parts[1].matches(Regex("\\d+(?:\\.\\d+)*.*"))) {
                            parts[1]
                        } else {
                            "unknown"
                        }
                    } else {
                        packageName = file.nameWithoutExtension
                        version = "unknown"
                    }
                }
            }
            // 源码分发包和其他压缩包格式
            file.name.endsWith(".tar.gz") || file.name.endsWith(".zip") || 
            file.name.endsWith(".tar.bz2") || file.name.endsWith(".tar.xz") -> {
                // 从分发包文件名解析包名和版本
                // 格式通常是: {package_name}-{version}.扩展名
                // 示例: requests-2.28.1.tar.gz, django-4.2.0.zip
                
                // 获取基本文件名（去掉所有压缩扩展名）
                val baseName = file.name
                    .replace(Regex("\\.tar\\.gz$|\\.tar\\.bz2$|\\.tar\\.xz$|\\.zip$"), "")
                
                val sdistPattern = Regex("(.*?)-(\\d+(?:\\.\\d+)*(?:[._-]?(?:dev|a|alpha|b|beta|rc|c|pre|preview|post|rev|r)\\d*)?(?:\\+[a-zA-Z0-9.]*)?)")
                val matchResult = sdistPattern.find(baseName)
                
                if (matchResult != null) {
                    packageName = matchResult.groupValues[1]
                    version = matchResult.groupValues[2]
                } else {
                    // 兼容模式：尝试更简单的拆分
                    val nameParts = baseName.split("-")
                    if (nameParts.size >= 2 && nameParts.last().matches(Regex("\\d+(?:\\.\\d+)*.*"))) {
                        packageName = nameParts.dropLast(1).joinToString("-")
                        version = nameParts.last()
                    } else {
                        packageName = baseName
                        version = "unknown"
                    }
                }
            }
            // .egg 文件处理
            file.name.endsWith(".egg") -> {
                // .egg 文件格式: {package_name}-{version}-{python_tag}.egg
                // 示例: SQLAlchemy-1.4.46-py3.9.egg
                val eggPattern = Regex("(.*?)-(\\d+(?:\\.\\d+)*(?:[._-]?(?:dev|a|alpha|b|beta|rc|c|pre|preview|post|rev|r)\\d*)?(?:\\+[a-zA-Z0-9.]*)?)-py\\d\\.\\d+\\.egg$")
                val matchResult = eggPattern.find(file.name)
                
                if (matchResult != null) {
                    packageName = matchResult.groupValues[1]
                    version = matchResult.groupValues[2]
                } else {
                    // 尝试更简单的匹配模式
                    val simpleParts = file.nameWithoutExtension.split("-")
                    if (simpleParts.size >= 2) {
                        // 检查倒数第二部分是否是版本号
                        val possibleVersionIndex = if (simpleParts.last().startsWith("py")) 
                            simpleParts.size - 2 else simpleParts.size - 1
                        
                        if (possibleVersionIndex >= 1 && 
                            simpleParts[possibleVersionIndex].matches(Regex("\\d+(?:\\.\\d+)*.*"))) {
                            packageName = simpleParts.subList(0, possibleVersionIndex).joinToString("-")
                            version = simpleParts[possibleVersionIndex]
                        } else {
                            packageName = file.nameWithoutExtension
                            version = "unknown"
                        }
                    } else {
                        packageName = file.nameWithoutExtension
                        version = "unknown"
                    }
                }
            }
            // egg-info 目录或文件
            file.name.endsWith(".egg-info") -> {
                // 解析egg-info目录名
                // 格式通常是: {package_name}-{version}.egg-info
                // 示例: SQLAlchemy-1.4.46.egg-info
                val eggInfoPattern = Regex("(.*?)-(\\d+(?:\\.\\d+)*(?:[._-]?(?:dev|a|alpha|b|beta|rc|c|pre|preview|post|rev|r)\\d*)?(?:\\+[a-zA-Z0-9.]*)?)\\.egg-info$")
                val matchResult = eggInfoPattern.find(file.name)
                
                if (matchResult != null) {
                    packageName = matchResult.groupValues[1]
                    version = matchResult.groupValues[2]
                } else {
                    // 检查是否有PKG-INFO文件，可以从中读取更准确的信息
                    val pkgInfoFile = File(packageDir, "PKG-INFO") 
                    if (pkgInfoFile.exists()) {
                        try {
                            val pkgInfoContent = pkgInfoFile.readText()
                            val nameMatch = Regex("Name:\\s*([^\r\n]+)").find(pkgInfoContent)
                            val versionMatch = Regex("Version:\\s*([^\r\n]+)").find(pkgInfoContent)
                            
                            if (nameMatch != null) {
                                packageName = nameMatch.groupValues[1].trim()
                            } else {
                                packageName = file.name.removeSuffix(".egg-info")
                            }
                            
                            if (versionMatch != null) {
                                version = versionMatch.groupValues[1].trim()
                            } else {
                                version = "unknown"
                            }
                        } catch (e: Exception) {
                            // 如果读取失败，继续使用文件名解析
                            // 兼容模式：尝试简单拆分
                            val nameParts = file.name.removeSuffix(".egg-info").split("-")
                            if (nameParts.size >= 2 && nameParts.last().matches(Regex("\\d+(?:\\.\\d+)*.*"))) {
                                packageName = nameParts.dropLast(1).joinToString("-")
                                version = nameParts.last()
                            } else {
                                packageName = file.name.removeSuffix(".egg-info")
                                version = "unknown"
                            }
                        }
                    } else {
                        // 兼容模式：尝试简单拆分
                        val nameParts = file.name.removeSuffix(".egg-info").split("-")
                        if (nameParts.size >= 2 && nameParts.last().matches(Regex("\\d+(?:\\.\\d+)*.*"))) {
                            packageName = nameParts.dropLast(1).joinToString("-")
                            version = nameParts.last()
                        } else {
                            packageName = file.name.removeSuffix(".egg-info")
                            version = "unknown"
                        }
                    }
                }
            }
            // dist-info 目录处理
            file.name.endsWith(".dist-info") || (file.parentFile != null && file.parentFile.name.endsWith(".dist-info")) -> {
                // 从dist-info目录名解析包信息
                // 格式通常是: {package_name}-{version}.dist-info
                // 示例: flask-2.2.3.dist-info
                val dirName = if (file.name.endsWith(".dist-info")) file.name else file.parentFile.name
                val distInfoPattern = Regex("(.*?)-(\\d+(?:\\.\\d+)*(?:[._-]?(?:dev|a|alpha|b|beta|rc|c|pre|preview|post|rev|r)\\d*)?(?:\\+[a-zA-Z0-9.]*)?)\\.dist-info$")
                val matchResult = distInfoPattern.find(dirName)
                
                if (matchResult != null) {
                    packageName = matchResult.groupValues[1]
                    version = matchResult.groupValues[2]
                } else {
                    // 尝试从METADATA文件获取信息
                    val metadataFile = if (file.name.endsWith(".dist-info")) {
                        File(file, "METADATA")
                    } else { 
                        File(file.parentFile, "METADATA")
                    }
                    
                    if (metadataFile.exists()) {
                        try {
                            val metadataContent = metadataFile.readText()
                            val nameMatch = Regex("Name:\\s*([^\r\n]+)").find(metadataContent)
                            val versionMatch = Regex("Version:\\s*([^\r\n]+)").find(metadataContent)
                            
                            if (nameMatch != null) {
                                packageName = nameMatch.groupValues[1].trim()
                            } else {
                                packageName = dirName.removeSuffix(".dist-info")
                            }
                            
                            if (versionMatch != null) {
                                version = versionMatch.groupValues[1].trim()
                            } else {
                                version = "unknown"
                            }
                        } catch (e: Exception) {
                            // 如果读取失败，继续使用目录名解析
                            // 兼容模式：尝试简单拆分
                            val nameParts = dirName.removeSuffix(".dist-info").split("-")
                            if (nameParts.size >= 2 && nameParts.last().matches(Regex("\\d+(?:\\.\\d+)*.*"))) {
                                packageName = nameParts.dropLast(1).joinToString("-")
                                version = nameParts.last()
                            } else {
                                packageName = dirName.removeSuffix(".dist-info")
                                version = "unknown"
                            }
                        }
                    } else {
                        // 兼容模式：尝试简单拆分
                        val nameParts = dirName.removeSuffix(".dist-info").split("-")
                        if (nameParts.size >= 2 && nameParts.last().matches(Regex("\\d+(?:\\.\\d+)*.*"))) {
                            packageName = nameParts.dropLast(1).joinToString("-")
                            version = nameParts.last()
                        } else {
                            packageName = dirName.removeSuffix(".dist-info")
                            version = "unknown"
                        }
                    }
                }
            }
            // dist-info 目录中的元数据文件
            file.name == "RECORD" || file.name == "METADATA" || file.name == "WHEEL" -> {
                if (file.parentFile != null && file.parentFile.name.endsWith(".dist-info")) {
                    // 使用父目录解析
                    val dirName = file.parentFile.name
                    val distInfoPattern = Regex("(.*?)-(\\d+(?:\\.\\d+)*(?:[._-]?(?:dev|a|alpha|b|beta|rc|c|pre|preview|post|rev|r)\\d*)?(?:\\+[a-zA-Z0-9.]*)?)\\.dist-info$")
                    val matchResult = distInfoPattern.find(dirName)
                    
                    if (matchResult != null) {
                        packageName = matchResult.groupValues[1]
                        version = matchResult.groupValues[2]
                    } else if (file.name == "METADATA") {
                        // 尝试从METADATA文件获取信息
                        try {
                            val metadataContent = file.readText()
                            val nameMatch = Regex("Name:\\s*([^\r\n]+)").find(metadataContent)
                            val versionMatch = Regex("Version:\\s*([^\r\n]+)").find(metadataContent)
                            
                            packageName = nameMatch?.groupValues?.get(1)?.trim() ?: dirName.removeSuffix(".dist-info")
                            version = versionMatch?.groupValues?.get(1)?.trim() ?: "unknown"
                        } catch (e: Exception) {
                            // 解析失败，使用目录名
                            packageName = dirName.removeSuffix(".dist-info")
                            version = "unknown"
                        }
                    } else {
                        // 其他文件，使用目录名
                        val nameParts = dirName.removeSuffix(".dist-info").split("-")
                        if (nameParts.size >= 2 && nameParts.last().matches(Regex("\\d+(?:\\.\\d+)*.*"))) {
                            packageName = nameParts.dropLast(1).joinToString("-")
                            version = nameParts.last()
                        } else {
                            packageName = dirName.removeSuffix(".dist-info")
                            version = "unknown"
                        }
                    }
                } else {
                    // 无法确定包信息
                    packageName = file.nameWithoutExtension
                    version = "unknown"
                }
            }
            // PKG-INFO 文件
            file.name == "PKG-INFO" -> {
                // 尝试从PKG-INFO文件内容解析
                try {
                    val pkgInfoContent = file.readText()
                    val nameMatch = Regex("Name:\\s*([^\r\n]+)").find(pkgInfoContent)
                    val versionMatch = Regex("Version:\\s*([^\r\n]+)").find(pkgInfoContent)
                    
                    packageName = nameMatch?.groupValues?.get(1)?.trim() ?: file.parentFile.name
                    version = versionMatch?.groupValues?.get(1)?.trim() ?: "unknown"
                } catch (e: Exception) {
                    // 尝试从父目录获取信息
                    if (file.parentFile != null) {
                        val parentName = file.parentFile.name
                        if (parentName.endsWith(".egg-info") || parentName == "EGG-INFO") {
                            val eggInfoPattern = Regex("(.*?)-(\\d+(?:\\.\\d+)*(?:[._-]?(?:dev|a|alpha|b|beta|rc|c|pre|preview|post|rev|r)\\d*)?(?:\\+[a-zA-Z0-9.]*)?)\\.egg-info$")
                            val matchResult = eggInfoPattern.find(parentName)
                            
                            if (matchResult != null) {
                                packageName = matchResult.groupValues[1]
                                version = matchResult.groupValues[2]
                            } else {
                                packageName = parentName.removeSuffix(".egg-info")
                                version = "unknown"
                            }
                        } else {
                            // 尝试从目录名解析
                            val dirParts = parentName.split("-")
                            if (dirParts.size >= 2 && dirParts.last().matches(Regex("\\d+(?:\\.\\d+)*.*"))) {
                                packageName = dirParts.dropLast(1).joinToString("-")
                                version = dirParts.last()
                            } else {
                                packageName = parentName
                                version = "unknown"
                            }
                        }
                    } else {
                        packageName = "unknown"
                        version = "unknown"
                    }
                }
            }
            // setup.py 文件
            file.name == "setup.py" && file.parentFile?.name?.contains("-") == true -> {
                // 尝试从目录名解析
                val dirName = file.parentFile.name
                val setupPattern = Regex("(.*?)-(\\d+(?:\\.\\d+)*(?:[._-]?(?:dev|a|alpha|b|beta|rc|c|pre|preview|post|rev|r)\\d*)?(?:\\+[a-zA-Z0-9.]*)?)")
                val matchResult = setupPattern.find(dirName)
                
                if (matchResult != null) {
                    packageName = matchResult.groupValues[1]
                    version = matchResult.groupValues[2]
                } else {
                    // 尝试解析源代码
                    try {
                        val setupContent = file.readText()
                        val nameMatch = Regex("name\\s*=\\s*['\"]([^'\"]+)['\"]").find(setupContent)
                        val versionMatch = Regex("version\\s*=\\s*['\"]([^'\"]+)['\"]").find(setupContent)
                        
                        packageName = nameMatch?.groupValues?.get(1)?.trim() ?: dirName
                        version = versionMatch?.groupValues?.get(1)?.trim() ?: "unknown"
                    } catch (e: Exception) {
                        val dirParts = dirName.split("-")
                        if (dirParts.size >= 2 && dirParts.last().matches(Regex("\\d+(?:\\.\\d+)*.*"))) {
                            packageName = dirParts.dropLast(1).joinToString("-")
                            version = dirParts.last()
                        } else {
                            packageName = dirName
                            version = "unknown"
                        }
                    }
                }
            }
            // 其他特殊文件
            file.name == "requires.txt" || file.name == "installed-files.txt" || file.name == "direct_url.json" -> {
                // 从父目录尝试解析
                if (file.parentFile != null) {
                    val parentName = file.parentFile.name
                    
                    if (parentName.endsWith(".egg-info")) {
                        val eggInfoPattern = Regex("(.*?)-(\\d+(?:\\.\\d+)*(?:[._-]?(?:dev|a|alpha|b|beta|rc|c|pre|preview|post|rev|r)\\d*)?(?:\\+[a-zA-Z0-9.]*)?)\\.egg-info$")
                        val matchResult = eggInfoPattern.find(parentName)
                        
                        if (matchResult != null) {
                            packageName = matchResult.groupValues[1]
                            version = matchResult.groupValues[2]
                        } else {
                            packageName = parentName.removeSuffix(".egg-info")
                            version = "unknown"
                        }
                    } else if (parentName.endsWith(".dist-info")) {
                        val distInfoPattern = Regex("(.*?)-(\\d+(?:\\.\\d+)*(?:[._-]?(?:dev|a|alpha|b|beta|rc|c|pre|preview|post|rev|r)\\d*)?(?:\\+[a-zA-Z0-9.]*)?)\\.dist-info$")
                        val matchResult = distInfoPattern.find(parentName)
                        
                        if (matchResult != null) {
                            packageName = matchResult.groupValues[1]
                            version = matchResult.groupValues[2]
                        } else {
                            packageName = parentName.removeSuffix(".dist-info")
                            version = "unknown"
                        }
                    } else {
                        packageName = parentName
                        version = "unknown"
                    }
                } else {
                    packageName = file.nameWithoutExtension
                    version = "unknown"
                }
            }
            // 其他未知类型
            else -> {
                // 如果是顶级目录，尝试从子目录查找元数据
                val metadataFile = File(packageDir, "PKG-INFO")
                if (metadataFile.exists()) {
                    try {
                        val pkgInfoContent = metadataFile.readText()
                        val nameMatch = Regex("Name:\\s*([^\r\n]+)").find(pkgInfoContent)
                        val versionMatch = Regex("Version:\\s*([^\r\n]+)").find(pkgInfoContent)
                        
                        packageName = nameMatch?.groupValues?.get(1)?.trim() ?: file.nameWithoutExtension
                        version = versionMatch?.groupValues?.get(1)?.trim() ?: "unknown"
                    } catch (e: Exception) {
                        packageName = file.nameWithoutExtension
                        version = "unknown"
                    }
                } else {
                    // 尝试从目录名解析
                    if (packageDir != null && packageDir.name.contains("-")) {
                        val dirPattern = Regex("(.*?)-(\\d+(?:\\.\\d+)*(?:[._-]?(?:dev|a|alpha|b|beta|rc|c|pre|preview|post|rev|r)\\d*)?(?:\\+[a-zA-Z0-9.]*)?)")
                        val matchResult = dirPattern.find(packageDir.name)
                        
                        if (matchResult != null) {
                            packageName = matchResult.groupValues[1]
                            version = matchResult.groupValues[2]
                        } else {
                            packageName = file.nameWithoutExtension
                            version = "unknown"
                        }
                    } else {
                        packageName = file.nameWithoutExtension
                        version = "unknown"
                    }
                }
            }
        }
        
        // 规范化包名（PEP 503：把连字符、下划线、点都转换为连字符）
        val normalizedPackageName = packageName.lowercase().replace(Regex("[_.-]+"), "-")
        
        return PkgData(
            relativePath = relativePath ?: "",
            packageName = "$normalizedPackageName${getPackageNameSeparator()}$version",
            packageDir = packageDir
        )
    }

    override fun getDependencyType(): DependencyType {
        return DependencyType.PIP
    }

    /**
     * 实现新的 scanRepository 方法，使用 ConfigOptions 对象
     */
    override fun scanRepository(
        dir: File, 
        onDirFound: (File, String, PkgData) -> Unit, 
        configOptions: ConfigOptions
    ) {
        val targetPackageName = configOptions.targetPackage.takeIf { it.isNotEmpty() }
        val separator = getPackageNameSeparator()

        val pathPkgDataMap = fetchPkgMap(dir)

        pathPkgDataMap.forEach { (path, pkgData) ->
            // 检查是否是失效的包文件
            val isInvalidPackage = pkgData.invalid

            // 检查是否是snapshot版本（预发布版本）
            // PEP 440定义的预发布标识符
            val isSnapshotVersion = pkgData.packageName.contains(Regex("\\d+\\.\\d+\\.\\d+(-dev\\d*|-a\\d+|-alpha\\d*|-b\\d+|-beta\\d*|-rc\\d+|-pre\\d*|\\.dev\\d*|\\.post\\d*)"))
            
            // 检查是否匹配指定的包名
            val isTargetPackage = targetPackageName != null && 
                                 (pkgData.packageName.startsWith("$targetPackageName$separator") || 
                                  (pkgData.packageName.contains(separator) && 
                                   pkgData.packageName.split(separator)[0] == targetPackageName))
            
            // 检查是否包含版本号
            val hasVersion = pkgData.packageName.contains(separator) && 
                            !pkgData.packageName.endsWith("${separator}unknown") && 
                            !pkgData.packageName.endsWith(separator)
            
            // 特殊处理平台特定目录，例如.cp38-win_amd64等后缀
            val isNativeBinary = pkgData.packageDir.name.matches(Regex(".*\\.(cp\\d+|py\\d+)-(win|linux|darwin|macosx|manylinux)_(x86_64|amd64|arm64|i686).*"))
            
            // 如果是平台特定目录，并且有效，补充包名信息
            val adjustedPkgData = if (isNativeBinary && configOptions.showPlatformSpecificBinaries) {
                // 从目录名中提取信息
                val parts = pkgData.packageName.split(separator)
                val name = parts[0]
                val version = if (parts.size > 1) parts[1] else "unknown"
                
                // 添加平台信息
                PkgData(
                    relativePath = pkgData.relativePath,
                    packageName = "$name${separator}$version (${pkgData.packageDir.name})",
                    packageDir = pkgData.packageDir,
                    invalid = pkgData.invalid
                )
            } else pkgData
            
            // 筛选逻辑：包含有明确匹配类型的包（指定的目标包名、快照版本、无效包或平台特定二进制文件）
            val shouldInclude = when {
                targetPackageName != null && isTargetPackage -> true
                configOptions.showPlatformSpecificBinaries && isNativeBinary -> true
                configOptions.includeSnapshot && isSnapshotVersion -> true
                configOptions.showInvalidPackages && isInvalidPackage -> true
                else -> false
            }

            // 设置匹配类型
            val matchType = when {
                targetPackageName != null && isTargetPackage -> "matched"
                configOptions.showPlatformSpecificBinaries && isNativeBinary -> "native"
                configOptions.includeSnapshot && isSnapshotVersion -> "prerelease"
                configOptions.showInvalidPackages && isInvalidPackage -> "invalid"
                else -> "unknown" // 理论上不应该有unknown类型被包含
            }

            if (shouldInclude) {
                onDirFound(adjustedPkgData.packageDir, matchType, adjustedPkgData)
            }
        }
    }

    /**
     * 尝试查找系统Python的site-packages目录
     */
    private fun findSystemPythonSitePackages(): String? {
        val osName = System.getProperty("os.name").lowercase(Locale.getDefault())
        val userHome = System.getProperty("user.home")
        
        // 常见的系统Python site-packages位置
        val possiblePaths = when {
            osName.contains("win") -> listOf(
                "${System.getenv("LOCALAPPDATA") ?: "$userHome/AppData/Local"}/Programs/Python/Python3*/Lib/site-packages",
                "C:/Program Files/Python3*/Lib/site-packages",
                "C:/Python3*/Lib/site-packages"
            )
            osName.contains("mac") -> listOf(
                "/Library/Frameworks/Python.framework/Versions/3.*/lib/python3.*/site-packages",
                "/usr/local/lib/python3.*/site-packages",
                "$userHome/Library/Python/3.*/lib/python/site-packages"
            )
            else -> listOf( // 假设是Linux/Unix系统
                "/usr/lib/python3/dist-packages",
                "/usr/lib/python3.*/site-packages",
                "/usr/local/lib/python3.*/site-packages",
                "$userHome/.local/lib/python3.*/site-packages"
            )
        }
        
        // 尝试找到第一个存在的路径
        for (pattern in possiblePaths) {
            // 使用简单的通配符匹配
            val globPattern = pattern.replace("*", "?").replace("?", "*")
            val matchingDirs = File(pattern.substringBeforeLast("/")).listFiles { file ->
                file.isDirectory && file.name.matches(Regex(globPattern.substringAfterLast("/").replace("*", ".*")))
            }
            
            if (matchingDirs?.isNotEmpty() == true) {
                return matchingDirs[0].absolutePath
            }
        }
        
        return null
    }

    /**
     * 覆盖父类方法，返回@作为PIP包的分隔符
     */
    override fun getPackageNameSeparator(): String {
        return "@"
    }
} 