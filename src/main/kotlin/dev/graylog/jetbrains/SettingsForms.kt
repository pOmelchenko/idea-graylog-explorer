package dev.graylog.jetbrains

import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.*

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
}
