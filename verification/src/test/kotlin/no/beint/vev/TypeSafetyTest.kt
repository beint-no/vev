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
        Files.writeString(
            source,
            """
            import no.beint.vev.*
            import no.beint.vev.fixture.*
            fun example(read: ReadScope, write: WriteScope, tenantRead: TenantReadScope, tenantWrite: TenantWriteScope) {
                $body
            }
            """.trimIndent() + "\n"
        )
        val classpath = listOf(Queries::class.java, Db::class.java, Unit::class.java)
            .map { Path.of(it.protectionDomain.codeSource.location.toURI()).toString() }.joinToString(java.io.File.pathSeparator)
        val generatedClasses = Path.of(Queries::class.java.protectionDomain.codeSource.location.toURI())
        val output = ByteArrayOutputStream()
        val result = K2JVMCompiler().exec(
            PrintStream(output), "-no-stdlib", "-no-reflect", "-jvm-target", "27",
            "-classpath", classpath, "-Xfriend-paths=$generatedClasses",
            "-d", directory.resolve("classes").toString(), source.toString()
        )
        return result to output.toString()
    }

    @ParameterizedTest @ValueSource(strings = [
        "Queries.customer(CustomerId(1))",
        "Queries.customer(tenantRead, 1)",
        "Queries.customer(tenantRead, InvoiceId(1))",
        "Queries.customer(read, CustomerId(1))",
        "Queries.customer(write, CustomerId(1))",
        "Queries.customer(tenantRead, null)",
        "Queries.createCustomer(tenantRead, \"Alice\")",
        "Queries.createCustomer(read, \"Alice\")",
        "Queries.createCustomer(write, \"Alice\")",
        "Queries.createCustomer(tenantWrite, null)",
        "Queries.post(read, 1, java.math.BigDecimal.ONE)",
        "Queries.post(tenantRead, 1, java.math.BigDecimal.ONE)",
        "Queries.lockTenant(tenantRead, \"k\")",
        "Queries.claimNextDocument(read)",
        "Queries.claimNextDocument(tenantRead)",
        "read.beforeCommit {}",
        "tenantRead.beforeCommit {}",
        "read.tenantId()",
        "write.tenantId()",
        "val id: Int = Queries.invoiceSummary(tenantRead).first().invoiceId",
        "val name: String = Queries.customer(tenantRead, CustomerId(1)).name",
        "Queries.customerIds(tenantRead, listOf(\"wrong\"))",
        "Queries.missingQuery(tenantRead)",
        "Queries.customer(tenantRead, CustomerId(1))!!.missingColumn",
        "Queries.customer(tenantRead, tenantId = 7, id = CustomerId(1))"
    ]) fun `invalid application programs fail Kotlin compilation`(body: String) {
        val (result, diagnostics) = compile(body)
        assertEquals(ExitCode.COMPILATION_ERROR, result, diagnostics)
        assertTrue(diagnostics.contains("Consumer.kt"), diagnostics)
        assertFalse(diagnostics.contains("Cannot access"), diagnostics)
    }

    @Test fun `valid generated contracts and capability subtyping compile with module access`() {
        val (result, diagnostics) = compile("""
            val name: String? = Queries.customer(tenantRead, CustomerId(1))?.name
            Queries.customers(tenantRead, null)
            Queries.customers(tenantWrite, null)
            Queries.createCustomer(tenantWrite, "Alice")
            Queries.quoted(read)
            Queries.quoted(write)
            Queries.quoted(tenantRead)
            Queries.quoted(tenantWrite)
            Queries.post(write, 1, java.math.BigDecimal.ONE)
            Queries.post(tenantWrite, 1, java.math.BigDecimal.ONE)
            Queries.claimNextDocument(write)
            Queries.claimNextDocument(tenantWrite)
            write.beforeCommit {}
            tenantWrite.beforeCommit {}
        """.trimIndent())
        assertEquals(ExitCode.OK, result, diagnostics)
    }

    @Test fun `generated static APIs are usable by an independent Java consumer`() {
        val source = directory.resolve("JavaConsumer.java")
        Files.writeString(source, """
            import no.beint.vev.ReadScope;
            import no.beint.vev.TenantReadScope;
            import no.beint.vev.fixture.*;
            class JavaConsumer {
                String name(TenantReadScope tx) { return Queries.customer(tx, 1).getName(); }
                Object values(ReadScope tx) { return Queries.typeCase2(tx, 42); }
            }
        """.trimIndent())
        val classpath = listOf(Queries::class.java, Db::class.java, Unit::class.java)
            .map { Path.of(it.protectionDomain.codeSource.location.toURI()).toString() }.joinToString(java.io.File.pathSeparator)
        val diagnostics = ByteArrayOutputStream()
        val result = javax.tools.ToolProvider.getSystemJavaCompiler().run(null, diagnostics, diagnostics,
            "-classpath", classpath, "-d", directory.resolve("java-classes").toString(), source.toString())
        assertEquals(0, result, diagnostics.toString())
    }
}
