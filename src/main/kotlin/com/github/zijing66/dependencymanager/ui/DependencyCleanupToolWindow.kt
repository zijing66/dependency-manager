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
    private val columns = arrayOf("", "Package Name", "Size", "Last Modified")

    private var data: List<CleanupPreview> = emptyList()

    fun setData(newData: List<CleanupPreview>) {
        // 默认不选中所有项
        data = newData.map { it.copy(selected = false) }
        fireTableDataChanged()
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
            2 -> formatFileSize(item.fileSize)
            3 -> formatDate(item.lastModified)
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
    private val progressBar: JProgressBar
    private val statusLabel: JLabel
    private var currentPreview: CleanupSummary? = null
    private val headerCheckBox = JCheckBox()

    private lateinit var snapshotCheckBox: JCheckBox
    private lateinit var groupArtifactField: JTextField
    private lateinit var cleanButton: JButton

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
                getColumn(2).preferredWidth = 100 // Size
                getColumn(2).maxWidth = 100
                getColumn(3).preferredWidth = 150 // Last Modified
                getColumn(3).maxWidth = 150
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
        }
        // 在表格模型添加监听器
        previewModel.addTableModelListener {
            val hasSelection = previewModel.getSelectedItems().isNotEmpty()
            cleanButton.isEnabled = hasSelection
        }
        progressBar = JProgressBar()
        statusLabel = JLabel()

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
            if (dependencyType == DependencyType.UNKNOWN) {
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

        val detector = DependencyManagerDetector()
        val dependencyType = detector.detectDependencyType(project)

        controlPanel.add(JLabel("Detected dependency manager: $dependencyType").apply {
            alignmentX = Component.LEFT_ALIGNMENT
        })

        val configService: IConfigService = when (dependencyType) {
            DependencyType.MAVEN -> MavenConfigService(project)
            DependencyType.GRADLE -> GradleConfigService(project)
            else -> throw IllegalArgumentException("Unsupported dependency type: $dependencyType")
        }
        // 添加仓库路径显示
        var currentRepoPath = configService.getLocalRepository(false)

        val pathStatusLabel = JLabel().apply {
            foreground = JBColor.RED
            font = font.deriveFont(font.size2D - 1f)
        }

        lateinit var refreshButton: JButton;

        val previewButton = JButton("Preview Cleanup").apply {
            addActionListener { loadPreview(configService) }
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

        cleanButton = JButton("Clean Selected").apply {
            isEnabled = false
            addActionListener { performCleanup(configService) }
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
            validForDependencyType({add(JLabel("Maven repository: "))}, arrayOf(DependencyType.MAVEN))
            validForDependencyType({add(JLabel("Gradle repository: "))}, arrayOf(DependencyType.GRADLE))
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
            })
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
            add(pathStatusLabel)
            add(Box.createVerticalStrut(10))
            add(filterPanel)
            add(Box.createVerticalStrut(10))
            add(buttonPanel)
            add(Box.createVerticalStrut(5))
            add(progressBar.apply {
                alignmentX = Component.LEFT_ALIGNMENT
                isVisible = false
            })
            add(Box.createVerticalStrut(5))
            add(statusLabel.apply {
                alignmentX = Component.LEFT_ALIGNMENT
            })
        }

        add(controlPanel, BorderLayout.NORTH)

        // Table panel
        val tablePanel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(5)
            add(JBScrollPane(previewTable).apply {
                preferredSize = Dimension(preferredSize.width, 300) // 固定初始高度
            }, BorderLayout.CENTER)
        }
        add(tablePanel, BorderLayout.CENTER)
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
}