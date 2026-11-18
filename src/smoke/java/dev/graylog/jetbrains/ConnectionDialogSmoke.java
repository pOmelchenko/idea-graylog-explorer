package dev.graylog.jetbrains;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ValidationInfo;
import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.Robot;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;

/** Checks the Kotlin form's user-visible validation and cancel behavior in the real IDE. */
final class ConnectionDialogSmoke {
    static void run(Project project, Path report) throws Exception {
        ConnectionDialog fresh = new ConnectionDialog(project, null);
        try {
            JTextField name = (JTextField) SmokeUi.component(fresh.getContentPane(), "connection.name");
            JTextField api = (JTextField) SmokeUi.component(fresh.getContentPane(), "connection.api");
            JPasswordField token = (JPasswordField) SmokeUi.component(fresh.getContentPane(), "connection.token");
            check(validate(fresh) != null, "Missing name rejected");
            name.setText("Fixture"); api.setText("invalid-url");
            check(validate(fresh) != null, "Invalid API URL rejected");
            api.setText("https://graylog.example.org/api/");
            check(validate(fresh) != null, "New connection requires a token");
            token.setText("dialog-fixture-token");
            check(validate(fresh) == null, "Complete connection accepted");
            check("https://graylog.example.org/api".equals(fresh.connection().apiUrl), "API URL normalized");
            char[] entered = fresh.token();
            check(entered.length > 0 && token.getPassword().length == 0, "Reading a token clears the field"); Arrays.fill(entered, '\0');
            token.setText("dialog-fixture-token");
            AtomicReference<Exception> failure = new AtomicReference<>();
            Timer capture = new Timer(650, event -> {
                try { ImageIO.write(new Robot().createScreenCapture(fresh.getWindow().getBounds()), "png", report.resolve("connection-dialog.png").toFile()); }
                catch (Exception error) { failure.set(error); }
                finally { fresh.doCancelAction(); }
            });
            capture.setRepeats(false); capture.start(); fresh.show();
            if (failure.get() != null) throw failure.get();
            check(token.getPassword().length == 0, "Cancel clears the password field");
        } finally { fresh.close(com.intellij.openapi.ui.DialogWrapper.CANCEL_EXIT_CODE); }
        Connection existing = new Connection(); existing.name = "Existing fixture"; existing.apiUrl = "https://graylog.example.org/api";
        ConnectionDialog edit = new ConnectionDialog(project, existing);
        try {
            check(validate(edit) == null, "Existing connection can keep its stored token");
            ((JTextField) SmokeUi.component(edit.getContentPane(), "connection.name")).setText("Edited fixture");
            check("Edited fixture".equals(edit.connection().name) && "Existing fixture".equals(existing.name), "Editing does not mutate the original connection");
        } finally { edit.close(com.intellij.openapi.ui.DialogWrapper.CANCEL_EXIT_CODE); }
        java.nio.file.Files.writeString(report.resolve("connection-dialog-result.txt"), "PASS: Kotlin dialog validation, URL normalization, copy editing and token clearing on read/cancel.\n");
    }
    private static ValidationInfo validate(ConnectionDialog dialog) throws Exception {
        return dialog.doValidate();
    }
    private static void check(boolean condition, String label) { if (!condition) throw new AssertionError(label); }
}
