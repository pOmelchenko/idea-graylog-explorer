package dev.graylog.jetbrains

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import javax.swing.JComponent

/** Native form layout; the dialog edits a copy and never writes settings or credentials. */
class ConnectionDialog(project: Project?, connection: Connection?) : DialogWrapper(project) {
    private val isNew = connection == null
    private val original = connection?.copy() ?: Connection()
    private val name = JBTextField(original.name)
    private val api = JBTextField(original.apiUrl)
    private val web = JBTextField(original.webUrl)
    private val token = JBPasswordField()

    init {
        name.name = "connection.name"; api.name = "connection.api"; web.name = "connection.web"; token.name = "connection.token"
        api.columns = 40
        api.emptyText.text = "https://graylog.example.org/api"
        web.emptyText.text = "Optional; defaults to the API URL without /api"
        token.emptyText.text = if (isNew) "Graylog access token" else "Leave blank to keep the stored token"
        title = if (isNew) "Add Graylog Connection" else "Edit Graylog Connection"
        setOKButtonText("Save")
        init()
    }
    override fun createCenterPanel(): JComponent = panel {
        row("Name:") { cell(name).align(AlignX.FILL).resizableColumn() }
        row("API URL:") { cell(api).align(AlignX.FILL).resizableColumn() }
        row("Web URL:") { cell(web).align(AlignX.FILL).resizableColumn() }
        row("Access token:") { cell(token).align(AlignX.FILL).resizableColumn() }
        row { comment("Shared by all projects. The token is stored only in IDE Password Safe.") }
    }
    override fun getPreferredFocusedComponent(): JComponent = name
    public override fun doValidate(): ValidationInfo? {
        if (name.text.isBlank()) return ValidationInfo("Enter a connection name.", name)
        try { Connection.normalizeApiUrl(api.text) }
        catch (error: IllegalArgumentException) { return ValidationInfo(error.message ?: "Invalid API URL.", api) }
        if (web.text.isNotBlank()) {
            try { Connection.normalizeApiUrl(web.text) }
            catch (error: IllegalArgumentException) { return ValidationInfo(error.message ?: "Invalid web URL.", web) }
        }
        val entered = token.password
        try { if (isNew && entered.isEmpty()) return ValidationInfo("Enter an API access token.", token) }
        finally { entered.fill('\u0000') }
        return null
    }
    fun connection(): Connection = original.copy().apply {
        name = this@ConnectionDialog.name.text.trim()
        apiUrl = Connection.normalizeApiUrl(api.text)
        webUrl = web.text.trim().trimEnd('/').ifBlank { apiUrl.removeSuffix("/api") }
    }
    fun token(): CharArray = token.password.also { token.text = "" }
    override fun doCancelAction() { token.text = ""; super.doCancelAction() }
}
