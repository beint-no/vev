/** Provides the JDK 26 annotation processor that compiles closed Vev models and PostgreSQL plans. */
module no.beint.vev.processor {
    requires java.compiler;
    requires jdk.compiler;

    provides javax.annotation.processing.Processor with no.beint.vev.processor.VevProcessor;
}
