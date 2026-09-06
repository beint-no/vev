package no.beint.vev.it;

public enum WorkState {
    OPEN {
        @Override
        public String toString() {
            return "open for work";
        }
    },
    CLOSED
}
