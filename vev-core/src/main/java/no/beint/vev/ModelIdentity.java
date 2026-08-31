package no.beint.vev;

/**
 * Stable identity embedded into generated code and verified against database schema metadata.
 *
 * @param name stable closed-model name
 * @param fingerprint deterministic generated-model fingerprint
 */
public record ModelIdentity(String name, String fingerprint) {
    /**
     * Validates a stable model name and SHA-256 fingerprint.
     *
     * @param name stable closed-model name
     * @param fingerprint deterministic generated-model fingerprint
     */
    public ModelIdentity {
        name = Names.requireStable(name, "name", 128);
        fingerprint = Names.requireStable(fingerprint, "fingerprint", 256);
        if (!fingerprint.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException("fingerprint must be a lowercase SHA-256 value");
        }
    }
}
