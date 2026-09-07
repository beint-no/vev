package no.beint.vev.processor;

record CheckMapping(String name, String expression, Kind kind, String boundColumn, int maximumLength) {
    CheckMapping(String name, String expression) {
        this(name, expression, Kind.EXACT, "", 0);
    }

    enum Kind { EXACT, BINARY_MAXIMUM, TEXT_MAXIMUM }
}
