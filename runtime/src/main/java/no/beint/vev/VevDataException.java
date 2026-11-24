package no.beint.vev;

/** Raised when a query's returned rows or column nullability violate its generated contract. */
public final class VevDataException extends VevException {
    private static final long serialVersionUID = 1L;

    public VevDataException(String message) {
        super(message);
    }
}
