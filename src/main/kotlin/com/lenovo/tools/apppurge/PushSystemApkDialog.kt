package com.lenovo.tools.apppurge

import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.ComboboxWithBrowseButton
import com.intellij.util.ui.JBUI
import java.awt.Dimension
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.GridLayout
import java.awt.Insets
import java.io.File
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextField
import javax.swing.UIManager

private const val PUSH_DIALOG_MIN_FIELD_WIDTH = 430
private const val PUSH_DIALOG_MAX_FIELD_WIDTH = 460
private const val PUSH_DIALOG_CONTENT_WIDTH = 560
private const val PUSH_DIALOG_FIELD_HEIGHT = 32

internal class PushSystemApkDialog(
    private val project: Project,
    private val projectBasePath: String?,
    private val adbPath: String,
    private val serial: String,
    private val info: AppInstallInfo,
    private val target: SystemApkTarget,
) : DialogWrapper(project, true) {
    private val apkChoices = info.apkFiles
    private var selectedApk: File? = apkChoices.firstOrNull()
    private lateinit var apkCombo: ComboBox<String>
    private val fieldWidth = preferredPushFieldWidth()
    private val targetField = JTextField(target.targetPath).apply {
        preferredSize = Dimension(fieldWidth, PUSH_DIALOG_FIELD_HEIGHT)
        caretPosition = 0
    }
    private val removeOverlayAllowed = info.status == InstallStatus.UPDATED_SYSTEM_APP && target.hasDataOverlay
    private val clearDataAllowed = info.isInstalled
    private val removeOverlayCheck = JCheckBox("Remove /data/app overlay", removeOverlayAllowed).apply {
        isEnabled = removeOverlayAllowed
        toolTipText = if (removeOverlayAllowed) {
            "Remove the user-installed update so the pushed system APK can take effect after reboot"
        } else {
            "No updated-system-app overlay detected for this package"
        }
    }
    private val clearDataCheck = JCheckBox("Clear app data", clearDataAllowed).apply {
        isEnabled = clearDataAllowed
        toolTipText = if (clearDataAllowed) {
            "Clear current installed app data after pushing"
        } else {
            "Package is not installed on the current device user"
        }
    }

    init {
        title = "Push System APK"
        setOKButtonText("Push")
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(GridBagLayout()).apply {
            border = JBUI.Borders.empty(8, 4, 4, 4)
        }
        val gbc = GridBagConstraints().apply {
            gridx = 0
            gridy = 0
            anchor = GridBagConstraints.WEST
            insets = Insets(7, 6, 7, 6)
        }

        fun addLabel(text: String) {
            gbc.gridx = 0
            gbc.weightx = 0.0
            gbc.fill = GridBagConstraints.NONE
            panel.add(JLabel(text), gbc)
        }

        fun addField(component: JComponent) {
            gbc.gridx = 1
            gbc.weightx = 1.0
            gbc.fill = GridBagConstraints.HORIZONTAL
            gbc.anchor = GridBagConstraints.WEST
            panel.add(component, gbc)
            gbc.gridy++
        }

        addLabel("Package:")
        addField(JTextField(info.packageName).apply {
            isEditable = false
            preferredSize = Dimension(fieldWidth, PUSH_DIALOG_FIELD_HEIGHT)
        })

        addLabel("Local APK:")
        addField(localApkComponent())

        addLabel("Device Target:")
        addField(deviceTargetComponent())
        gbc.gridx = 1
        gbc.weightx = 1.0
        gbc.fill = GridBagConstraints.HORIZONTAL
        gbc.anchor = GridBagConstraints.WEST
        panel.add(deviceTargetSourceLabel(), gbc)
        gbc.gridy++

        gbc.gridx = 1
        gbc.weightx = 0.0
        gbc.fill = GridBagConstraints.NONE
        gbc.anchor = GridBagConstraints.WEST
        panel.add(JPanel(GridLayout(0, 1, 0, 2)).apply {
            border = JBUI.Borders.emptyTop(4)
            add(removeOverlayCheck)
            add(clearDataCheck)
        }, gbc)

        return panel.apply {
            preferredSize = Dimension(PUSH_DIALOG_CONTENT_WIDTH, 220)
        }
    }

    private fun localApkComponent(): JComponent {
        apkCombo = ComboBox(apkChoices.map { relativeApkPath(it) }.toTypedArray()).apply {
            isEditable = true
            if (apkChoices.isNotEmpty()) selectedIndex = 0
            toolTipText = selectedApk?.absolutePath
            addActionListener {
                val idx = selectedIndex
                if (idx >= 0 && idx < apkChoices.size) {
                    selectedApk = apkChoices[idx]
                    toolTipText = selectedApk?.absolutePath
                }
            }
        }
        return ComboboxWithBrowseButton(apkCombo).apply {
            preferredSize = Dimension(fieldWidth, PUSH_DIALOG_FIELD_HEIGHT)
            toolTipText = "Choose APK"
            addActionListener { chooseLocalApk() }
        }
    }

    private fun preferredPushFieldWidth(): Int {
        val fontMetrics = JLabel().getFontMetrics(UIManager.getFont("TextField.font") ?: UIManager.getFont("Label.font"))
        val longest = listOf(
            info.packageName,
            target.targetPath,
        ) + apkChoices.map { relativeApkPath(it) }
        val contentWidth = (longest.maxOfOrNull { fontMetrics.stringWidth(it) } ?: PUSH_DIALOG_MIN_FIELD_WIDTH) + 80
        return contentWidth.coerceIn(PUSH_DIALOG_MIN_FIELD_WIDTH, PUSH_DIALOG_MAX_FIELD_WIDTH)
    }

    private fun chooseLocalApk() {
        val descriptor = FileChooserDescriptorFactory.createSingleFileDescriptor("apk")
        val chosen = FileChooser.chooseFile(descriptor, project, null) ?: return
        selectedApk = File(chosen.path)
        apkCombo.selectedItem = relativeApkPath(selectedApk!!)
        apkCombo.toolTipText = selectedApk?.absolutePath
    }

    private fun resolveSelectedApk(): File? {
        val current = apkCombo.editor?.item?.toString()?.trim().orEmpty()
        if (current.isNotBlank()) {
            val typed = File(current)
            selectedApk = if (typed.isAbsolute) typed else projectBasePath?.let { File(it, current) } ?: typed
        }
        return selectedApk
    }

    private fun deviceTargetComponent(): JComponent {
        targetField.toolTipText = target.detectedPath?.let { "Detected: $it" } ?: "Default path"
        return targetField
    }

    private fun deviceTargetSourceLabel(): JComponent {
        val text = if (target.detectedPath != null) {
            "Target Path: detected original system path"
        } else {
            "Target Path: generated default path"
        }
        return JLabel(text).apply {
            foreground = UIManager.getColor("Label.foreground")
            font = font.deriveFont(font.size - 1f)
            border = JBUI.Borders.empty(0, 2, 4, 0)
            toolTipText = target.detectedPath?.let { "Detected: $it" } ?: "Generated from module name, not detected"
        }
    }

    override fun doOKAction() {
        val apk = resolveSelectedApk()
        if (apk == null || !apk.isFile) {
            Messages.showErrorDialog("Choose a valid local APK file.", "AppPurge")
            return
        }
        if (!targetField.text.trim().endsWith(".apk", ignoreCase = true)) {
            Messages.showErrorDialog("Device Target must be a full .apk path.", "AppPurge")
            return
        }
        super.doOKAction()
    }

    fun request(): SystemPushRequest =
        SystemPushRequest(
            serial = serial,
            packageName = info.packageName,
            localApk = resolveSelectedApk()!!,
            targetPath = targetField.text.trim(),
            removeDataOverlay = removeOverlayCheck.isEnabled && removeOverlayCheck.isSelected,
            clearData = clearDataCheck.isEnabled && clearDataCheck.isSelected,
            adb = adbPath,
        )

    private fun relativeApkPath(apk: File): String {
        val basePath = projectBasePath?.takeIf(String::isNotBlank) ?: return apk.absolutePath
        return runCatching {
            File(basePath).canonicalFile.toPath()
                .relativize(apk.canonicalFile.toPath())
                .toString()
                .replace(File.separatorChar, '/')
        }.getOrDefault(apk.absolutePath)
    }
}
