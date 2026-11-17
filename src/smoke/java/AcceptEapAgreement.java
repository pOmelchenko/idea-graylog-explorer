import java.util.prefs.Preferences;

/** Standalone helper, invoked only after the runner validates an explicitly authorized EAP version. */
public final class AcceptEapAgreement {
    public static void main(String[] args) throws Exception {
        if (args.length != 1 || !args[0].matches("[0-9]+\\.[0-9]+")
                || System.getProperty("java.util.prefs.userRoot") == null) {
            throw new IllegalArgumentException("An exact agreement version and isolated preferences root are required");
        }
        Preferences preferences = Preferences.userRoot().node("jetbrains/privacy_policy");
        preferences.put("ij_euaeap_accepted_version", args[0]);
        preferences.flush();
    }
}
