package dev.graylog.jetbrains;

import com.intellij.openapi.editor.Document;
import com.intellij.openapi.extensions.ExtensionPointName;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import java.util.List;

/** Optional language adapters run under a smart, committed PSI read action. Never execute user code. */
public interface VariableHintProvider {
    ExtensionPointName<VariableHintProvider> EP = ExtensionPointName.create("dev.omelchenko.graylogexplorer.variableHintProvider");
    record Hint(int offset, String value) { }
    List<Hint> hints(Project project, VirtualFile file, Document document, int line,
                     List<ExceptionSites.Detail> exceptions);
}
