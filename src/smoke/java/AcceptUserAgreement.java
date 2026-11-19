import com.intellij.ide.gdpr.EndUserAgreement;
import com.intellij.ide.gdpr.Version;
import java.util.prefs.Preferences;

/** Test-only SDK API; the runner verifies the explicitly authorized bundled document/version first. */
public final class AcceptUserAgreement {
    public static void main(String[] args) throws Exception {
        if (args.length != 2 || !java.util.List.of("eua", "euaEap").contains(args[0])
                || !args[1].matches("[0-9]+\\.[0-9]+") || System.getProperty("java.util.prefs.userRoot") == null)
            throw new IllegalArgumentException("An authorized document/version and isolated preferences root are required");
        EndUserAgreement.setAcceptedVersion(args[0], Version.fromString(args[1]));
        Preferences.userRoot().flush();
    }
}
