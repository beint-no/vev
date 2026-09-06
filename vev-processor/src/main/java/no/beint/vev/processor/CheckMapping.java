package no.beint.vev.processor;

record CheckMapping(String name, String expression, String binaryColumn, int maximumBytes) {
    CheckMapping(String name, String expression) {
        this(name, expression, "", 0);
    }
}
