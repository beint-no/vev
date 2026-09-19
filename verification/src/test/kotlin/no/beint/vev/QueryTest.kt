package no.beint.vev

import no.beint.vev.compiler.Postgres
import no.beint.vev.fixture.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.Assertions.*
import org.postgresql.ds.PGSimpleDataSource
import java.math.BigDecimal
import java.nio.file.Path
import java.time.*
import java.util.UUID

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class QueryTest {
    private lateinit var postgres: Postgres
    private lateinit var vev: Vev

    @BeforeAll fun start() {
        postgres = Postgres("")
        postgres.migrate(Path.of("src/main/schema"))
        val source = PGSimpleDataSource().apply {
            setURL(postgres.url()); user = postgres.user(); password = postgres.password()
        }
        vev = Vev(source)
    }
    @AfterAll fun stop() { if (::postgres.isInitialized) postgres.close() }
    @BeforeEach fun reset() {
        postgres.connect().use { c -> c.createStatement().use { it.execute("TRUNCATE customer,invoice,posting,scalar_values RESTART IDENTITY CASCADE") } }
    }

    @Test fun `typed queries retain nulls and tenant predicates`() {
        val first = vev.write { Queries.createCustomer(it, 7, "Alice").id!! }
        vev.write { Queries.createCustomer(it, 8, "Other tenant") }
        assertEquals("Alice", vev.read { Queries.customer(it, 7, first)!!.name })
        assertNull(vev.read { Queries.customer(it, 8, first) })
        val rows = vev.read { Queries.invoiceSummary(it, 7) }
        assertEquals(1, rows.size)
        assertNull(rows.single().invoiceId)
        assertNull(rows.single().amount)
        assertEquals(1, vev.read { Queries.customers(it, 7, null).size })
        assertEquals(0, vev.read { Queries.customers(it, 7, "Nobody").size })
    }

    @Test fun `generated queries execute joins CTEs and optimistic updates`() {
        vev.write { Queries.createCustomer(it, 7, "Alice") }
        postgres.connect().use { c -> c.createStatement().use { it.execute("INSERT INTO invoice(tenant_id,customer_id,amount) VALUES(7,1,10.00)") } }
        assertEquals(BigDecimal("10.00"), vev.read { Queries.totals(it, 7).single().total })
        val updated = vev.write { Queries.updateInvoice(it, BigDecimal("12.50"), null, 7, InvoiceId(1), 0) }
        assertEquals(1, updated!!.version)
        assertNull(vev.write { Queries.updateInvoice(it, BigDecimal("14"), "stale", 7, InvoiceId(1), 0) })
        assertEquals(BigDecimal("12.50"), vev.read { Queries.invoiceSummary(it, 7).single().amount })
    }

    @Test fun `database deferred business constraints survive`() {
        vev.write { Queries.post(it, 1, BigDecimal.TEN); Queries.post(it, 1, BigDecimal.TEN.negate()) }
        assertThrows(java.sql.SQLException::class.java) { vev.write { Queries.post(it, 2, BigDecimal.ONE) } }
        postgres.connect().use { c -> c.createStatement().use { s -> s.executeQuery("SELECT count(*) FROM posting").use { r -> r.next(); assertEquals(2, r.getInt(1)) } } }
    }

    @Test fun `read only transactions reject writes`() {
        assertThrows(java.sql.SQLException::class.java) { vev.read { Queries.createCustomer(it, 7, "rejected") } }
        assertTrue(vev.read { Queries.customers(it, 7, null).isEmpty() })
    }

    @Test fun `cardinality and row bounds fail rather than truncate`() {
        assertThrows(IllegalStateException::class.java) { vev.read { Queries.exactlyOne(it, 7) } }
        vev.write { tx -> repeat(6) { Queries.createCustomer(tx, 7, "Person $it") } }
        assertThrows(IllegalStateException::class.java) { vev.read { Queries.exactlyOne(it, 7) } }
        assertThrows(IllegalStateException::class.java) { vev.read { Queries.customers(it, 7, null) } }
    }

    @Test fun `caught execution failures still roll back the complete transaction`() {
        assertThrows(IllegalStateException::class.java) {
            vev.write { tx ->
                Queries.createCustomer(tx, 7, "rollback")
                try { Queries.exactlyOne(tx, 8) } catch (_: IllegalStateException) { }
            }
        }
        assertTrue(vev.read { Queries.customers(it, 7, null).isEmpty() })
    }

    @Test fun `sessions cannot escape their scope or thread`() {
        var escaped: Session? = null
        vev.read { tx ->
            escaped = tx
            val error = java.util.concurrent.CompletableFuture.supplyAsync {
                runCatching { Queries.customers(tx, 7, null) }.exceptionOrNull()
            }.get()
            assertInstanceOf(IllegalStateException::class.java, error)
        }
        assertThrows(IllegalStateException::class.java) { Queries.customers(escaped!!, 7, null) }
    }

    @Test fun `borrowed transactions retain caller ownership`() {
        postgres.connect().use { c ->
            c.autoCommit = false
            Session.borrow(c) { Queries.createCustomer(it, 7, "not committed") }
            assertFalse(c.isClosed)
            c.rollback()
        }
        assertTrue(vev.read { Queries.customers(it, 7, null).isEmpty() })
        postgres.connect().use { c -> assertThrows(IllegalStateException::class.java) { Session.borrow(c) { 1 } } }
    }

    @Test fun `each generated query executes one statement including tenant predicates`() {
        vev.write { Queries.createCustomer(it, 7, "Alice") }
        var prepared = 0
        var executed = 0
        postgres.connect().use { actual ->
            actual.autoCommit = false
            val observed = java.lang.reflect.Proxy.newProxyInstance(javaClass.classLoader, arrayOf(java.sql.Connection::class.java)) { _, method, args ->
                try {
                    val result = method.invoke(actual, *(args ?: emptyArray()))
                    if (method.name == "prepareStatement") {
                        prepared++
                        java.lang.reflect.Proxy.newProxyInstance(javaClass.classLoader, arrayOf(java.sql.PreparedStatement::class.java)) { _, call, values ->
                            if (call.name.startsWith("execute")) executed++
                            try { call.invoke(result, *(values ?: emptyArray())) }
                            catch (failure: java.lang.reflect.InvocationTargetException) { throw failure.targetException }
                        }
                    } else result
                } catch (failure: java.lang.reflect.InvocationTargetException) { throw failure.targetException }
            } as java.sql.Connection
            assertEquals(1, Session.borrow(observed) { Queries.customers(it, 7, null).size })
            assertNotNull(TenantSession.borrow(observed, 7) { Queries.scopedCustomer(it, CustomerId(1)) })
            actual.rollback()
        }
        assertEquals(2, prepared)
        assertEquals(2, executed)
    }

    @Test fun `parameter names cannot shadow generated lambda locals`() {
        val row = vev.read { Queries.localNames(it, "bound value", 42) }
        assertEquals("bound value", row.bindings)
        assertEquals(42, row.result)
    }

    @Test fun `arrays JSON and SQL literals are bound safely`() {
        vev.write { Queries.createCustomer(it, 7, "'; DROP TABLE customer; --") }
        assertEquals(listOf(1), vev.read { Queries.customerIds(it, 7, listOf(1, null)).map { row -> row.id } })
        assertTrue(vev.read { Queries.customerIds(it, 7, emptyList()).isEmpty() })
        assertEquals(true, vev.read { Queries.hasKey(it, "{\"hello\":true}", "hello").present })
        assertEquals(":not_a_parameter; \$dollars", vev.read { Queries.quoted(it).textValue })
    }

    @Test fun `tenant capabilities bind predicates and restore transaction context`() {
        val id = vev.write { Queries.createCustomer(it, 7, "Alice").id!! }
        assertEquals("Alice", vev.read(7) { Queries.scopedCustomer(it, id)!!.name })
        assertNull(vev.read(8) { Queries.scopedCustomer(it, id) })
        var escaped: TenantSession? = null
        postgres.connect().use { connection ->
            connection.autoCommit = false
            TenantSession.borrow(connection, 7) { outer -> outer.withRls { scope -> escaped = scope; Queries.scopedCustomer(scope, id) } }
            connection.createStatement().use { s -> s.executeQuery("SELECT current_setting('vev.tenant_id')").use { r -> r.next(); assertEquals("", r.getString(1)) } }
            connection.rollback()
        }
        assertThrows(IllegalStateException::class.java) { Queries.scopedCustomer(escaped!!, id) }
        assertThrows(IllegalArgumentException::class.java) { vev.read(-1) { Queries.scopedCustomer(it, id) } }
    }

    @Test fun `RLS is enforced by PostgreSQL under a nonowner role`() {
        val id = vev.write { Queries.createCustomer(it, 7, "Alice").id!! }
        postgres.connect().use { connection ->
            connection.createStatement().use { s ->
                s.execute("CREATE ROLE tenant_reader; GRANT SELECT ON customer TO tenant_reader; ALTER TABLE customer ENABLE ROW LEVEL SECURITY; CREATE POLICY tenant_read ON customer TO tenant_reader USING (tenant_id = nullif(current_setting('vev.tenant_id', true),'')::integer)")
            }
            try {
                connection.autoCommit = false
                connection.createStatement().use { it.execute("SET LOCAL ROLE tenant_reader") }
                assertEquals("Alice", TenantSession.borrow(connection, 7) { outer -> outer.withRls { Queries.scopedCustomer(it, id)!!.name } })
                assertNull(TenantSession.borrow(connection, 8) { outer -> outer.withRls { Queries.scopedCustomer(it, id) } })
                assertTrue(Session.borrow(connection) { Queries.customers(it, 7, null).isEmpty() })
                connection.rollback()
            } finally {
                connection.autoCommit = true
                connection.createStatement().use { it.execute("DROP POLICY tenant_read ON customer; ALTER TABLE customer DISABLE ROW LEVEL SECURITY; REVOKE SELECT ON customer FROM tenant_reader; DROP ROLE tenant_reader") }
            }
        }
    }

    @Test fun `all supported scalar bindings round trip without reflective mapping`() {
        val uid = UUID.randomUUID()
        val day = LocalDate.of(2026, 9, 19)
        val clock = LocalTime.of(12, 30, 45, 123456000)
        val local = LocalDateTime.of(day, clock)
        val stamp = local.atOffset(ZoneOffset.UTC)
        vev.write { Queries.insertScalars(it, 1, true, 12, 5_000_000_000L, BigDecimal("1.25"), 1.5f, 2.5, "æøå", uid, day, clock, local, stamp, byteArrayOf(1,2), "{\"n\":1}", listOf(1,null,3), listOf("a",null)) }
        val row = vev.read { Queries.scalars(it, 1)!! }
        assertEquals(uid, row.uid); assertEquals(day, row.day); assertEquals(clock, row.clock)
        assertEquals(local, row.localStamp); assertEquals(stamp, row.stamp)
        assertArrayEquals(byteArrayOf(1,2), row.payload)
        assertEquals(listOf(1,null,3), row.numbers); assertEquals(listOf("a",null), row.tags)
        assertEquals(BigDecimal("1.25"), row.precise)
        assertEquals(5_000_000_000L, row.large)
        assertEquals(true, row.active); assertEquals("æøå", row.label)
    }
}
