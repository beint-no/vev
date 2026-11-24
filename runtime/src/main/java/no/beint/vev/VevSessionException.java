package no.beint.vev;

/** Raised when a transaction scope is misused, escalated from read to write, poisoned, or escaped. */
public final class VevSessionException extends VevException {
    private static final long serialVersionUID = 1L;

    public VevSessionException(String message) {
        super(message);
    }
}
