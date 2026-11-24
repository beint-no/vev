package no.beint.vev;

/** Base runtime exception for Vev SQL execution, decoding, and transaction scope failures. */
public class VevException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public VevException(String message) {
        super(message);
    }

    public VevException(String message, Throwable cause) {
        super(message, cause);
    }
}
