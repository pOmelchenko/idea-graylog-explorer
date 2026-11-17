package dev.graylog.jetbrains;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.ui.components.JBPasswordField;
import com.intellij.ui.components.JBTextField;

import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;

final class ConnectionDialog extends DialogWrapper {
    private final Connection original;
    private final boolean isNew;
    private final JBTextField name = new JBTextField();
    private final JBTextField api = new JBTextField();
    private final JBTextField web = new JBTextField();
    private final JBPasswordField token = new JBPasswordField();

    ConnectionDialog(Project project, Connection connection) {
        super(project);
        isNew = connection == null;
        original = isNew ? new Connection() : connection.copy();
        name.setText(original.name); api.setText(original.apiUrl); web.setText(original.webUrl);
        api.getEmptyText().setText("https://graylog.example.org/api");
        web.getEmptyText().setText("Optional; defaults to the API URL without /api");
        token.getEmptyText().setText(isNew ? "Graylog access token" : "Leave blank to keep the stored token");
        setTitle(isNew ? "Add Graylog Connection" : "Edit Graylog Connection");
        setOKButtonText("Save");
        init();
    }

    @Override protected JComponent createCenterPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        String[] labels = {"Name", "API URL", "Web URL", "Access token"};
        JComponent[] inputs = {name, api, web, token};
        for (int i = 0; i < labels.length; i++) {
            GridBagConstraints c = new GridBagConstraints();
            c.gridy = i; c.gridx = 0; c.anchor = GridBagConstraints.WEST; c.insets = new Insets(6, 0, 6, 12);
            panel.add(new JLabel(labels[i]), c);
            c.gridx = 1; c.weightx = 1; c.fill = GridBagConstraints.HORIZONTAL; c.insets = new Insets(6, 0, 6, 0);
            panel.add(inputs[i], c);
        }
        GridBagConstraints c = new GridBagConstraints();
        c.gridy = labels.length; c.gridx = 0; c.gridwidth = 2; c.anchor = GridBagConstraints.WEST;
        c.insets = new Insets(12, 0, 0, 0);
        panel.add(new JLabel("Shared by all projects. The token is stored only in IDE Password Safe."), c);
        panel.setPreferredSize(new java.awt.Dimension(640, 220));
        return panel;
    }

    @Override public JComponent getPreferredFocusedComponent() { return name; }

    @Override protected ValidationInfo doValidate() {
        if (name.getText().isBlank()) return new ValidationInfo("Enter a connection name.", name);
        try { Connection.normalizeApiUrl(api.getText()); }
        catch (IllegalArgumentException e) { return new ValidationInfo(e.getMessage(), api); }
        if (!web.getText().isBlank()) {
            try { Connection.normalizeApiUrl(web.getText()); }
            catch (IllegalArgumentException e) { return new ValidationInfo(e.getMessage(), web); }
        }
        if (isNew && token.getPassword().length == 0) return new ValidationInfo("Enter an API access token.", token);
        return null;
    }

    Connection connection() {
        Connection result = original.copy();
        result.name = name.getText().trim(); result.apiUrl = Connection.normalizeApiUrl(api.getText());
        result.webUrl = web.getText().isBlank() ? result.apiUrl.replaceFirst("/api$", "") : web.getText().trim().replaceAll("/+$", "");
        return result;
    }

    char[] token() { char[] value = token.getPassword(); token.setText(""); return value; }
}
