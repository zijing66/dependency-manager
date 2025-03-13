package com.github.zijing66.dependencymanager.ui

import com.github.zijing66.dependencymanager.models.CleanupPreview
import com.github.zijing66.dependencymanager.models.CleanupSummary
import com.github.zijing66.dependencymanager.models.DependencyType
import com.github.zijing66.dependencymanager.services.*
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.*
import javax.swing.*
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.TableCellRenderer

private val LOG = logger<DependencyCleanupToolWindow>()

class DependencyCleanupToolWindow : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        LOG.info("Creating tool window content")
        // 使用兼容性工具类创建内容
        CompatibilityUtil.createContent(
            toolWindow.contentManager,
            DependencyCleanupPanel(project),
            "",
            false
        )
    }

    override fun shouldBeAvailable(project: Project) = true
}

private class PreviewTableModel : AbstractTableModel() {
    private val columns = arrayOf("", "Package Name", "Match Type", "Size", "Last Modified")

    private var data: List<CleanupPreview> = emptyList()

    fun setData(newData: List<CleanupPreview>) {
        // 默认不选中所有项
        data = newData.map { it.copy(selected = false) }
        fireTableDataChanged()
    }

    fun getItemAt(rowIndex: Int): CleanupPreview {
        return data[rowIndex]
    }

    fun getSelectedItems(): List<CleanupPreview> {
        return data.filter { it.selected }
    }

    override fun getRowCount() = data.size
    override fun getColumnCount() = columns.size
    override fun getColumnName(column: Int) = columns[column]

    override fun getColumnClass(columnIndex: Int) = when (columnIndex) {
        0 -> Boolean::class.java
        else -> String::class.java
    }

    override fun isCellEditable(rowIndex: Int, columnIndex: Int) = columnIndex == 0

    private fun formatDate(timestamp: Long): String {
        return SimpleDateFormat("yyyy-MM-dd HH:mm").format(Date(timestamp))
    }

    private fun formatFileSize(size: Long): String {
        val df = DecimalFormat("#.##")
        return when {
            size < 1024 -> "$size B"
            size < 1024 * 1024 -> "${df.format(size / 1024.0)} KB"
            size < 1024 * 1024 * 1024 -> "${df.format(size / (1024.0 * 1024.0))} MB"
            else -> "${df.format(size / (1024.0 * 1024.0 * 1024.0))} GB"
        }
    }

    override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
        val item = data[rowIndex]
        return when (columnIndex) {
            0 -> item.selected
            1 -> item.packageName
            2 -> item.matchType
            3 -> formatFileSize(item.fileSize)
            4 -> formatDate(item.lastModified)
            else -> ""
        }
    }

    override fun setValueAt(aValue: Any?, rowIndex: Int, columnIndex: Int) {
        if (columnIndex == 0) {
            data[rowIndex].selected = (aValue as? Boolean) ?: false
            fireTableCellUpdated(rowIndex, columnIndex)
        }
    }

    // 添加全选/全不选方法
    fun setAllSelected(selected: Boolean) {
        data = data.map { it.copy(selected = selected) }
        fireTableDataChanged()
    }
}

private class DependencyCleanupPanel(private val project: Project) : JPanel(BorderLayout()) {
    private val previewTable: JBTable
    private val previewModel: PreviewTableModel

    private var currentPreview: CleanupSummary? = null
    private val headerCheckBox = JCheckBox()
    private lateinit var configService: IConfigService
    private lateinit var currentDependencyType: DependencyType
    private lateinit var snapshotCheckBox: JCheckBox
    private lateinit var groupArtifactField: JTextField
    private lateinit var progressBar: JProgressBar
    private lateinit var statusLabel: JLabel

    init {
        previewModel = PreviewTableModel()
        previewTable = JBTable(previewModel).apply {
            setShowGrid(true)
            gridColor = JBColor.border()
            setStriped(true)
            autoResizeMode = JTable.AUTO_RESIZE_SUBSEQUENT_COLUMNS

            // 设置列宽
            columnModel.apply {
                getColumn(0).preferredWidth = 30 // Checkbox
                getColumn(0).maxWidth = 30
                getColumn(1).preferredWidth = 300 // Package Name
                getColumn(2).preferredWidth = 100 // Match Type
                getColumn(2).maxWidth = 100
                getColumn(3).preferredWidth = 100 // Size
                getColumn(3).maxWidth = 100
                getColumn(4).preferredWidth = 150 // Last Modified
                getColumn(4).maxWidth = 150
            }
            // 添加单元格编辑器
            columnModel.getColumn(0).cellEditor = object : DefaultCellEditor(JCheckBox()) {
                override fun getTableCellEditorComponent(
                    table: JTable?,
                    value: Any?,
                    isSelected: Boolean,
                    row: Int,
                    column: Int
                ): Component {
                    return (super.getTableCellEditorComponent(
                        table,
                        value,
                        isSelected,
                        row,
                        column
                    ) as JCheckBox).apply {
                        horizontalAlignment = SwingConstants.CENTER
                    }
                }
            }
            // 设置渲染器
            columnModel.getColumn(0).cellRenderer = createBooleanRenderer()
            columnModel.getColumn(2).cellRenderer = DefaultTableCellRenderer().apply {
                horizontalAlignment = SwingConstants.RIGHT
            }

            // 修改表头复选框引用
            val header = getTableHeader()
            headerCheckBox.apply {  // 使用成员变量
                addActionListener {
                    previewModel.setAllSelected(isSelected)
                }
            }

            columnModel.getColumn(0).headerRenderer = object : DefaultTableCellRenderer() {
                override fun getTableCellRendererComponent(
                    table: JTable?,
                    value: Any?,
                    isSelected: Boolean,
                    hasFocus: Boolean,
                    row: Int,
                    column: Int
                ): Component {
                    return headerCheckBox.apply {
                        horizontalAlignment = SwingConstants.CENTER
                    }
                }
            }

            // 修改点击事件处理
            header.addMouseListener(object : java.awt.event.MouseAdapter() {
                override fun mouseClicked(e: java.awt.event.MouseEvent) {
                    if (header.columnAtPoint(e.point) == 0) {
                        headerCheckBox.isSelected = !headerCheckBox.isSelected
                        previewModel.setAllSelected(headerCheckBox.isSelected)
                    }
                }
            })
            // 添加双击事件处理
            addMouseListener(object : java.awt.event.MouseAdapter() {
                override fun mouseClicked(e: java.awt.event.MouseEvent) {
                    if (e.clickCount == 2) { // 检查是否双击
                        val row = rowAtPoint(e.point)
                        val column = columnAtPoint(e.point)
                        if (column == 1) { // 检查是否在Package Name列
                            openExplorerForPackage(previewModel.getItemAt(row)) // 打开资源管理器
                        }
                    }
                }
            })

            // 添加鼠标悬浮提示
            addMouseMotionListener(object : java.awt.event.MouseMotionAdapter() {
                override fun mouseMoved(e: java.awt.event.MouseEvent) {
                    val row = rowAtPoint(e.point)
                    val column = columnAtPoint(e.point)
                    if (column == 1 && row >= 0) { // 检查是否在Package Name列
                        toolTipText = "Double click to open Explorer"
                    } else {
                        toolTipText = null // 清除提示
                    }
                }
            })
        }
        setupUI()
    }

    private fun createBooleanRenderer(): TableCellRenderer {
        return object : DefaultTableCellRenderer() {
            private val checkBox = JCheckBox()

            init {
                horizontalAlignment = SwingConstants.CENTER
            }

            override fun getTableCellRendererComponent(
                table: JTable,
                value: Any?,
                isSelected: Boolean,
                hasFocus: Boolean,
                row: Int,
                column: Int
            ): Component {
                if (value is Boolean) {
                    checkBox.isSelected = value
                    checkBox.horizontalAlignment = SwingConstants.CENTER
                    checkBox.background = if (isSelected) table.selectionBackground else table.background
                    return checkBox
                }
                return super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
            }
        }
    }

    private fun validForDependencyType(func: () -> Unit, dependencyTypeList: Array<DependencyType>) {
        for (dependencyType in dependencyTypeList) {
            if (dependencyType == DependencyType.UNKNOWN || !dependencyTypeList.contains(currentDependencyType)) {
                continue
            }
            func()
         }
        return
    }

    private fun setupUI() {
        // Top panel with controls
        val controlPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(5)
        }
        add(controlPanel, BorderLayout.NORTH)

        val detector = DependencyManagerDetector()
        val dependencyType = detector.detectDependencyType(project)

        updateUIComponents(controlPanel, dependencyType)

        // Table panel
        val tablePanel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(5)
            add(JBScrollPane(previewTable).apply {
                preferredSize = Dimension(preferredSize.width, 300) // 固定初始高度
            }, BorderLayout.CENTER)
        }
        add(tablePanel, BorderLayout.CENTER)
    }

    private fun updateUIComponents(controlPanel: JPanel, chosenDependencyType : DependencyType) {
        currentDependencyType = chosenDependencyType
        // 清空当前组件
        controlPanel.removeAll()
        // 清空预览数据
        previewModel.setData(emptyList())

        // 创建一个水平布局的面板
        val topRowPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            alignmentX = Component.LEFT_ALIGNMENT  // 确保面板左对齐
            add(JLabel("Dependency manager: $chosenDependencyType").apply {
                alignmentX = Component.LEFT_ALIGNMENT
            })
            add(Box.createHorizontalStrut(10)) // 添加间隔
            // 添加下拉菜单供用户选择依赖管理器
            add(JLabel("Switch to: ").apply {
                alignmentX = Component.LEFT_ALIGNMENT
            })
            val dependencyTypeList = DependencyType.values().filter { it != DependencyType.UNKNOWN && it != chosenDependencyType }.toTypedArray()
            val dependencyTypeComboBox = JComboBox(dependencyTypeList).apply {
                alignmentX = Component.LEFT_ALIGNMENT  // 确保下拉框左对齐
                addActionListener {
                    val dependencyType = selectedItem as DependencyType
                    updateUIComponents(controlPanel, dependencyType)
                }
            }
            add(dependencyTypeComboBox)
            add(Box.createHorizontalGlue())  // 添加水平弹性空间，确保组件靠左对齐
        }

        controlPanel.add(topRowPanel) // 将水平面板添加到控制面板

        // 初始化配置服务
        try {
            updateConfigService(chosenDependencyType)
        } catch (e: Exception) {
            LOG.info(String.format("Failed to initialize config service: {}", e.message))
            return
        }

        // 添加仓库路径显示
        var currentRepoPath = configService.getLocalRepository(false)

        val pathStatusLabel = JLabel().apply {
            foreground = JBColor.RED
            font = font.deriveFont(font.size2D - 1f)
            alignmentX = Component.LEFT_ALIGNMENT  // 确保标签左对齐
        }

        lateinit var refreshButton: JButton

        val previewButton = JButton("Preview Cleanup").apply {
            addActionListener { loadPreview(configService) }
        }

        val cleanButton = JButton("Clean Selected").apply {
            isEnabled = false
            addActionListener { performCleanup(configService) }
        }

        val pathField = JTextField(currentRepoPath, 40).apply {
            validForDependencyType({
                toolTipText = "Maven local repository path (writable directory)"
            } , arrayOf(DependencyType.MAVEN))
            validForDependencyType({
                toolTipText = "Gradle local repository path (writable directory)"
            } , arrayOf(DependencyType.GRADLE))
            border = BorderFactory.createCompoundBorder(
                JBUI.Borders.customLine(JBColor.border(), 1),
                JBUI.Borders.empty(2)
            )
            document.addDocumentListener(object : javax.swing.event.DocumentListener {
                override fun insertUpdate(e: javax.swing.event.DocumentEvent) = updateButtonState()
                override fun removeUpdate(e: javax.swing.event.DocumentEvent) = updateButtonState()
                override fun changedUpdate(e: javax.swing.event.DocumentEvent) = updateButtonState()
                private fun updateButtonState() {
                    val changed = text != currentRepoPath
                    refreshButton.isEnabled = changed // 使用外部作用域的refreshButton
                    previewButton.isEnabled = !changed
                    cleanButton.isEnabled = !changed && previewModel.getSelectedItems().isNotEmpty()
                }
            })
        }

        refreshButton = JButton("Refresh").apply { // 初始化已声明的按钮
            isEnabled = false
            addActionListener {
                try {
                    val newPath = if (pathField.text.isBlank()) {
                        // 当输入框为空时，获取系统默认路径
                        val defaultPath = configService.getLocalRepository(true)
                        configService.updateLocalRepository(defaultPath)
                        defaultPath
                    } else {
                        pathField.text
                    }
                    // 更新当前路径和输入框显示
                    currentRepoPath = newPath
                    pathField.text = newPath
                    configService.updateLocalRepository(newPath)
                    pathStatusLabel.text = ""
                    isEnabled = false
                    previewButton.isEnabled = true
                } catch (e: Exception) {
                    pathStatusLabel.text = "Invalid path: ${e.message}"
                    previewButton.isEnabled = false
                    cleanButton.isEnabled = false
                }
            }
        }
        val repoPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            alignmentX = Component.LEFT_ALIGNMENT
            
            // 创建一个固定宽度的标签面板，确保标签对齐
            val labelPanel = JPanel(BorderLayout()).apply {
                preferredSize = Dimension(120, 25)  // 设置固定宽度
                validForDependencyType({add(JLabel("Maven repository: "), BorderLayout.WEST)}, arrayOf(DependencyType.MAVEN))
                validForDependencyType({add(JLabel("Gradle repository: "), BorderLayout.WEST)}, arrayOf(DependencyType.GRADLE))
            }
            add(labelPanel)
            
            add(Box.createHorizontalStrut(5))
            add(pathField.apply {
                maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height) // 允许横向扩展
            })
            add(Box.createHorizontalStrut(5))
            add(refreshButton)
        }
        
        val filterPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            alignmentX = Component.LEFT_ALIGNMENT
            add(JLabel("Choose:"))
            add(Box.createHorizontalStrut(5)) // 添加间隔
            add(JCheckBox("SNAPSHOT").apply {
                snapshotCheckBox = this  // 初始化复选框引用
                toolTipText = "Include SNAPSHOT packages"
            })
            add(Box.createHorizontalStrut(20)) // 加大间隔
            add(JLabel("Choose [Group:Artifact]:"))
            add(Box.createHorizontalStrut(5))
            add(JTextField(15).apply {
                groupArtifactField = this  // 初始化输入框引用
                toolTipText = "Format: groupId:artifactId or groupId"
                maximumSize = Dimension(200, preferredSize.height)  // 限制最大宽度
            })
            add(Box.createHorizontalGlue())  // 添加水平弹性空间
        }
        
        val buttonPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            alignmentX = Component.LEFT_ALIGNMENT
            add(previewButton)
            add(Box.createHorizontalStrut(10))
            add(cleanButton)
            add(Box.createHorizontalGlue())
        }

        controlPanel.apply {
            add(repoPanel)
            add(Box.createVerticalStrut(5))
            
            // 为错误消息添加一个面板，保持与其他组件的对齐
            val errorPanel = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.X_AXIS)
                alignmentX = Component.LEFT_ALIGNMENT
                add(Box.createRigidArea(Dimension(120, 0)))
                add(pathStatusLabel)
            }
            add(errorPanel)
            
            add(Box.createVerticalStrut(10))
            add(filterPanel)
            add(Box.createVerticalStrut(10))
            add(buttonPanel)
            add(Box.createVerticalStrut(5))

            progressBar = JProgressBar()
            // 为进度条添加一个面板，保持与其他组件的对齐
            val progressPanel = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.X_AXIS)
                alignmentX = Component.LEFT_ALIGNMENT
                add(progressBar.apply {
                    alignmentX = Component.LEFT_ALIGNMENT
                    isVisible = false
                    maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)  // 允许横向扩展
                })
            }
            add(progressPanel)
            
            add(Box.createVerticalStrut(5))

            statusLabel = JLabel()
            // 为状态标签添加一个面板，保持与其他组件的对齐
            val statusPanel = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.X_AXIS)
                alignmentX = Component.LEFT_ALIGNMENT
                add(statusLabel.apply {
                    alignmentX = Component.LEFT_ALIGNMENT
                })
            }
            add(statusPanel)
        }
        // 在表格模型添加监听器
        previewModel.addTableModelListener {
            val hasSelection = previewModel.getSelectedItems().isNotEmpty()
            cleanButton.isEnabled = hasSelection
        }
        // 刷新面板
        controlPanel.revalidate()
        controlPanel.repaint()
    }

    private fun updateConfigService(dependencyType: DependencyType) {
        configService = when (dependencyType) {
            DependencyType.MAVEN -> MavenConfigService(project)
            DependencyType.GRADLE -> GradleConfigService(project)
            else -> throw IllegalArgumentException("Unsupported dependency type: $dependencyType")
        }
    }

    private fun loadPreview(configService: IConfigService) {
        progressBar.isVisible = true
        progressBar.isIndeterminate = true
        statusLabel.text = "Loading preview..."

        SwingUtilities.invokeLater {
            currentPreview = configService.previewCleanup(
                // 添加参数传递
                includeSnapshot = snapshotCheckBox.isSelected,
                groupArtifact = groupArtifactField.text.takeIf { it.isNotEmpty() }
            )
            previewModel.setData(currentPreview?.previewItems ?: emptyList())
            headerCheckBox.isSelected = false
            updateStatus()

            progressBar.isVisible = false
            previewTable.parent.parent.isVisible = true
        }
    }

    private fun performCleanup(configService: IConfigService) {
        val selectedItems = previewModel.getSelectedItems()
        if (selectedItems.isEmpty()) return

        progressBar.isVisible = true
        progressBar.isIndeterminate = false
        progressBar.value = 0

        configService.cleanupRepository(
            project = project,
            selectedItems = selectedItems,
            onProgress = { current, total ->
                SwingUtilities.invokeLater {  // 添加Swing线程调度
                    progressBar.value = (current.toFloat() / total * 100).toInt()
                    statusLabel.text = "Deleting files: $current/$total completed"
                }
            },
            onComplete = { results ->
                SwingUtilities.invokeLater {
                    progressBar.isVisible = false
                    val success = results.count { it.success }
                    statusLabel.text = "Cleanup complete: $success/${results.size} files cleaned"
                    loadPreview(configService) // Refresh the preview
                }
            }
        )
    }

    private fun updateStatus() {
        currentPreview?.let { preview ->
            val totalSize = formatFileSize(preview.totalSize)
            statusLabel.text = "Found ${preview.totalFiles} files (Total size: $totalSize)"  // 添加总大小标识
        }
    }

    private fun formatFileSize(size: Long): String {
        val df = DecimalFormat("#.##")
        return when {
            size < 1024 -> "$size B"
            size < 1024 * 1024 -> "${df.format(size / 1024.0)} KB"
            size < 1024 * 1024 * 1024 -> "${df.format(size / (1024.0 * 1024.0))} MB"
            else -> "${df.format(size / (1024.0 * 1024.0 * 1024.0))} GB"
        }
    }

    private fun openExplorerForPackage(item: CleanupPreview) {
        // 根据包名获取路径并打开资源管理器
        val path = item.path // 需要实现此方法以获取路径
        val file = java.io.File(path)
        if (file.exists() && file.isDirectory) {
            // 使用系统命令打开资源管理器
            val command = when {
                System.getProperty("os.name").lowercase(Locale.getDefault()).contains("win") -> "explorer"
                System.getProperty("os.name").lowercase(Locale.getDefault()).contains("mac") -> "open"
                else -> "xdg-open" // Linux
            }
            Runtime.getRuntime().exec(arrayOf(command, file.path))
        }
    }
}