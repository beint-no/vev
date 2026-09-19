package no.beint.vev

import no.beint.vev.fixture.Queries
import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path

class TypeSafetyTest {
    @TempDir lateinit var directory: Path

    private fun compile(body: String): Pair<ExitCode, String> {
        val source = directory.resolve("Consumer.kt")
        Files.writeString(source, "import no.beint.vev.*\nimport no.beint.vev.fixture.*\nfun example(session: Session) { $body }\n")
        val classpath = listOf(Queries::class.java, Session::class.java, Unit::class.java)
            .map { Path.of(it.protectionDomain.codeSource.location.toURI()).toString() }.joinToString(java.io.File.pathSeparator)
        val output = ByteArrayOutputStream()
        val result = K2JVMCompiler().exec(PrintStream(output), "-no-stdlib", "-no-reflect", "-jvm-target", "26", "-classpath", classpath, "-d", directory.resolve("classes").toString(), source.toString())
        return result to output.toString()
    }

    @ParameterizedTest @ValueSource(strings = [
        "Queries.customer(session, 7, 1)",
        "Queries.customer(session, 7, InvoiceId(1))",
        "Queries.customer(session, \"tenant\", CustomerId(1))",
        "Queries.customer(session, 7, null)",
        "Queries.createCustomer(session, 7, null)",
        "val id: Int = Queries.invoiceSummary(session, 7).first().invoiceId",
        "val name: String = Queries.customer(session, 7, CustomerId(1)).name",
        "Queries.customerIds(session, 7, listOf(\"wrong\"))",
        "Queries.missingQuery(session)",
        "Queries.customer(session, 7, CustomerId(1))!!.missingColumn",
        "Queries.scopedCustomer(session, CustomerId(1))"
    ]) fun `invalid application programs fail Kotlin compilation`(body: String) {
        val (result, diagnostics) = compile(body)
        assertEquals(ExitCode.COMPILATION_ERROR, result, diagnostics)
        assertTrue(diagnostics.contains("Consumer.kt"), diagnostics)
    }

    @Test fun `valid generated contracts compile in an independent consumer`() {
        val (result, diagnostics) = compile("val name: String? = Queries.customer(session, 7, CustomerId(1))?.name; Queries.customers(session, 7, null)")
        assertEquals(ExitCode.OK, result, diagnostics)
    }
}
