package no.beint.vev;

final class Names {
    private Names() {
    }

    static String requireStable(String value, String label, int maximumLength) {
        if (value == null) {
            throw new NullPointerException(label);
        }
        if (value.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        if (!value.equals(value.strip())) {
            throw new IllegalArgumentException(label + " must not have surrounding whitespace");
        }
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (Character.isHighSurrogate(current)) {
                if (index + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(index + 1))) {
                    throw new IllegalArgumentException(label + " must contain well-formed Unicode");
                }
                index++;
            } else if (Character.isLowSurrogate(current)) {
                throw new IllegalArgumentException(label + " must contain well-formed Unicode");
            }
        }
        if (value.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(label + " must not contain control characters");
        }
        if (value.codePointCount(0, value.length()) > maximumLength) {
            throw new IllegalArgumentException(label + " must not exceed " + maximumLength + " characters");
        }
        return value;
    }
}
