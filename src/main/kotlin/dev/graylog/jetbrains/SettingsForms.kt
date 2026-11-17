package dev.graylog.jetbrains

import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.ColorPanel
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import javax.swing.*
import javax.swing.event.DocumentEvent

/** Small reusable controls; Settings pages own their native DSL bindings. */
object SettingsForms {
        @JvmStatic fun help(text: String): JLabel = JBLabel("<html>$text</html>").apply {
            font = JBFont.medium(); foreground = UIUtil.getContextHelpForeground()
        }
        @JvmStatic fun tab(content: JComponent): JPanel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.emptyTop(12); add(content)
        }
        @JvmStatic fun <T> combo(): ComboBox<T> = combo(null)
        @JvmStatic fun <T> combo(values: Array<T>?): ComboBox<T> = object : ComboBox<T>() {
            override fun getPreferredSize(): Dimension {
                val size = super.getPreferredSize()
                val width = if (font == null) 0 else (0 until itemCount).maxOfOrNull {
                    getFontMetrics(font).stringWidth(getItemAt(it).toString())
                } ?: 0
                return Dimension(maxOf(size.width, minOf(JBUI.scale(360), width + JBUI.scale(64))), size.height)
            }
        }.apply { values?.forEach { addItem(it) } }
        @JvmStatic fun color(field: JBTextField): JPanel {
            val picker = ColorPanel().apply { setSupportTransparency(false) }
            var syncing = false
            field.document.addDocumentListener(object : DocumentAdapter() {
                override fun textChanged(event: DocumentEvent) {
                    if (syncing) return
                    try {
                        syncing = true
                        if (field.text.matches(Regex("#[0-9A-Fa-f]{6}"))) picker.selectedColor = Color.decode(field.text)
                    } finally { syncing = false }
                }
            })
            picker.addActionListener { if (!syncing) picker.selectedColor?.let { field.text = SourceColors.hex(it) } }
            return JPanel(BorderLayout()).apply { add(picker, BorderLayout.WEST) }
        }
    }
