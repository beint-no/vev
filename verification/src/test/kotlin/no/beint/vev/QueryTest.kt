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
import java.sql.Connection
import java.sql.SQLException
import java.util.concurrent.CompletableFuture

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class QueryTest {
    private lateinit var postgres: Postgres
    private lateinit var vev: Vev
    private lateinit var source: PGSimpleDataSource

    @BeforeAll fun start() {
        postgres = Postgres("")
        postgres.migrate(Path.of("src/main/schema"))
        source = PGSimpleDataSource().apply { setURL(postgres.url()); user = postgres.user(); password = postgres.password() }
        vev = Vev(source)
    }
    @AfterAll fun stop() { if (::postgres.isInitialized) postgres.close() }
    @BeforeEach fun reset() = sql("TRUNCATE customer,invoice,posting,scalar_values,document_record RESTART IDENTITY CASCADE")
    private fun sql(text: String) { postgres.connect().use { c -> c.createStatement().use { it.execute(text) } } }

    @Test fun `wide write binder loads and executes`() {
        val method=Queries::class.java.methods.single { it.name == "wideWrite" }
        val args=Array<Any?>(252) { 1L }
        assertNotNull(vev.write(7) { method.invoke(null,*args) })
        val primitive=Queries::class.java.methods.single { it.name == "widePrimitiveWrite" }
        assertNotNull(vev.write(7) { primitive.invoke(null,*Array<Any?>(126) { 1L }) })
        val global=Queries::class.java.methods.single { it.name == "wideGlobalWrite" }
        assertNotNull(vev.writeGlobal { global.invoke(null,*Array<Any?>(253) { 1L }) })
    }

    @Test fun `internal wildcards preserve the explicit generated row and tenant predicate`() {
        vev.write(7) { Queries.createCustomer("Synthetic local") }
        vev.write(8) { Queries.createCustomer("Synthetic other") }
        assertEquals(listOf("Synthetic local"),vev.read(7) { Queries.nestedCustomers().map { it.name } })
        assertEquals(listOf("Synthetic other"),vev.read(8) { Queries.nestedCustomers().map { it.name } })
    }

    @Test fun `data classes at JVM parameter slot boundaries compile and execute`() {
        val nullable=vev.readGlobal { Queries.wideNullable() }
        assertNull(nullable.c1)
        assertEquals(1L,nullable.c245)
        val required=vev.readGlobal { Queries.wideRequired() }
        assertEquals(1L,required.c1)
        assertEquals(1L,required.c124)
    }

    @Test fun `tenant binding needs no query plumbing and isolates rows`() {
        val id = vev.write(7) { Queries.createCustomer("Alice").id!! }
        vev.write(8) { Queries.createCustomer("Other tenant") }
        assertEquals("Alice", vev.read(7) { Queries.customer(id)!!.name })
        assertNull(vev.read(8) { Queries.customer(id) })
        assertEquals(1, vev.read(7) { Queries.customers(null).size })
        assertNull(vev.read(7) { Queries.invoiceSummary().single().invoiceId })
    }

    @Test fun `query outside transaction and tenant query in global scope fail closed`() {
        assertThrows(IllegalStateException::class.java) { Queries.customers(null) }
        assertThrows(IllegalStateException::class.java) { vev.readGlobal { Queries.customers(null) } }
        assertThrows(IllegalArgumentException::class.java) { vev.read(0) { fail<Any>("executed") } }
        assertThrows(IllegalArgumentException::class.java) { vev.write(-1) { fail<Any>("executed") } }
        assertThrows(IllegalStateException::class.java) { Session.current() }
    }

    @Test fun `explicit global queries can run inside tenant and global transactions`() {
        assertTrue(vev.readGlobal { Queries.hasKey("{\"yes\":1}", "yes").present }!!)
        assertEquals(":not_a_parameter; \$dollars", vev.read(7) { Queries.quoted().textValue })
    }

    @Test fun `nested transactions reject tenant switching without changing the outer context`() {
        vev.write(7) {
            assertThrows(IllegalStateException::class.java) { vev.write(8) { Queries.createCustomer("wrong") } }
            assertEquals(7, Session.tenant().tenantId())
            Queries.createCustomer("correct")
        }
        assertTrue(vev.read(8) { Queries.customers(null).isEmpty() })
        assertEquals("correct", vev.read(7) { Queries.customers(null).single().name })
    }

    @Test fun `scope does not leak after exceptions or across worker threads`() {
        assertThrows(IllegalArgumentException::class.java) { vev.read(7) { throw IllegalArgumentException("abort") } }
        assertThrows(IllegalStateException::class.java) { Session.current() }
        vev.read(7) {
            val error = CompletableFuture.supplyAsync { runCatching { Queries.customers(null) }.exceptionOrNull() }.get()
            assertInstanceOf(IllegalStateException::class.java, error)
        }
        assertThrows(IllegalStateException::class.java) { Session.current() }
    }

    @Test fun `virtual thread transactions isolate scoped values`() {
        java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor().use { executor ->
            val futures = (1..32).map { tenant -> executor.submit<Int> { vev.read(tenant) { Session.tenant().tenantId() } } }
            assertEquals((1..32).toList(), futures.map { it.get() })
        }
    }

    @Test fun `sessions cannot escape their scope or cross threads`() {
        var escaped: Session? = null
        vev.read(7) { session ->
            escaped = session
            val error = CompletableFuture.supplyAsync { runCatching { session.tenantId() }.exceptionOrNull() }.get()
            assertInstanceOf(IllegalStateException::class.java, error)
        }
        assertThrows(IllegalStateException::class.java) { escaped!!.tenantId() }
    }

    @Test fun `joins CTEs and optimistic writes retain results and conflict detection`() {
        vev.write(7) { Queries.createCustomer("Alice") }
        sql("INSERT INTO invoice(tenant_id,customer_id,amount) VALUES(7,1,10.00)")
        assertEquals(BigDecimal("10.00"), vev.read(7) { Queries.totals().single().total })
        assertEquals(1, vev.write(7) { Queries.updateInvoice(BigDecimal("12.50"), null, InvoiceId(1), 0) }!!.version)
        assertNull(vev.write(7) { Queries.updateInvoice(BigDecimal("14"), "stale", InvoiceId(1), 0) })
        assertEquals(BigDecimal("12.50"), vev.read(7) { Queries.invoiceSummary().single().amount })
    }

    @Test fun `deferred business constraints are checked at commit`() {
        vev.writeGlobal { Queries.post(1, BigDecimal.TEN); Queries.post(1, BigDecimal.TEN.negate()) }
        assertThrows(SQLException::class.java) { vev.writeGlobal { Queries.post(2, BigDecimal.ONE) } }
    }

    @Test fun `read only transactions reject writes`() {
        assertThrows(SQLException::class.java) { vev.read(7) { Queries.createCustomer("rejected") } }
        assertTrue(vev.read(7) { Queries.customers(null).isEmpty() })
    }

    @Test fun `cardinality and materialization bounds fail rather than truncate`() {
        assertThrows(IllegalStateException::class.java) { vev.read(7) { Queries.exactlyOne() } }
        vev.write(7) { repeat(6) { Queries.createCustomer("Person $it") } }
        assertThrows(IllegalStateException::class.java) { vev.read(7) { Queries.exactlyOne() } }
        assertThrows(IllegalStateException::class.java) { vev.read(7) { Queries.customers(null) } }
    }

    @Test fun `caught query failures poison and roll back the transaction`() {
        assertThrows(IllegalStateException::class.java) {
            vev.write(7) {
                Queries.createCustomer("rollback")
                Queries.createCustomer("second")
                try { Queries.exactlyOne() } catch (_: IllegalStateException) { }
            }
        }
        assertTrue(vev.read(7) { Queries.customers(null).isEmpty() })
    }

    @Test fun `single row returning violations roll back every affected row even when caught`() {
        for (optional in listOf(false, true)) {
            assertThrows(IllegalStateException::class.java) {
                vev.write(7) { session ->
                    val insert = "INSERT INTO customer(tenant_id,name) SELECT 7,'Synthetic '||n FROM generate_series(1,3) n RETURNING id"
                    try {
                        if (optional) session.optional(insert, {}, { it.getInt(1) })
                        else session.one(insert, {}, { it.getInt(1) })
                    } catch (_: IllegalStateException) { }
                }
            }
            assertTrue(vev.read(7) { Queries.customers(null).isEmpty() })
        }
    }

    @Test fun `competing optimistic writes have one winner across independent transactions`() {
        vev.write(7) { Queries.createCustomer("Synthetic") }
        sql("INSERT INTO invoice(tenant_id,customer_id,amount) VALUES(7,1,10.00)")
        val barrier = java.util.concurrent.CyclicBarrier(2)
        java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor().use { executor ->
            val updates = (1..2).map { number -> executor.submit<Boolean> {
                barrier.await(10, java.util.concurrent.TimeUnit.SECONDS)
                vev.write(7) { Queries.updateInvoice(BigDecimal(number), null, InvoiceId(1), 0) != null }
            } }
            assertEquals(1, updates.count { it.get(20, java.util.concurrent.TimeUnit.SECONDS) })
        }
        assertEquals(1, vev.read(7) { session -> session.one("SELECT version FROM invoice WHERE tenant_id=7 AND id=1", {}, { it.getInt(1) }) })
    }

    @Test fun `borrowed transaction leaves ownership with caller`() {
        postgres.connect().use { c ->
            c.autoCommit = false
            Session.borrow(c, 7) { Queries.createCustomer("not committed") }
            assertFalse(c.isClosed)
            c.rollback()
        }
        assertTrue(vev.read(7) { Queries.customers(null).isEmpty() })
    }

    @Test fun `tenant predicates add no database statements`() {
        vev.write(7) { Queries.createCustomer("Alice") }
        var prepared = 0
        postgres.connect().use { actual ->
            actual.autoCommit = false
            val observed = java.lang.reflect.Proxy.newProxyInstance(javaClass.classLoader, arrayOf(Connection::class.java)) { _, method, args ->
                if (method.name == "prepareStatement") prepared++
                try { method.invoke(actual, *(args ?: emptyArray())) }
                catch (error: java.lang.reflect.InvocationTargetException) { throw error.targetException }
            } as Connection
            Session.borrow(observed, 7) { Queries.customers(null); Queries.scopedCustomer(CustomerId(1)) }
            actual.rollback()
        }
        assertEquals(2, prepared)
    }

    @Test fun `values and array elements are bound safely`() {
        vev.write(7) { Queries.createCustomer("'; DROP TABLE customer; --") }
        assertEquals(listOf(1), vev.read(7) { Queries.customerIds(listOf(1, null)).map { it.id } })
        assertTrue(vev.read(7) { Queries.customerIds(emptyList()).isEmpty() })
        assertEquals("bound value", vev.readGlobal { Queries.localNames("bound value", 42).bindings })
    }

    @Test fun `database RLS uses one transaction-local setting and resets after pooled reuse`() {
        vev.write(7) { Queries.createCustomer("Alice") }
        sql("CREATE ROLE tenant_reader; GRANT SELECT ON customer TO tenant_reader; ALTER TABLE customer ENABLE ROW LEVEL SECURITY; CREATE POLICY tenant_read ON customer TO tenant_reader USING (tenant_id = nullif(current_setting('vev.tenant_id', true),'')::integer)")
        try {
            // Real nonowner connection: table owners and superusers bypass ordinary RLS.
            val restricted = PGSimpleDataSource().apply {
                setURL(postgres.url()); user = postgres.user(); password = postgres.password()
                options = "-c role=tenant_reader"
            }
            val rls = Vev(restricted, Connection.TRANSACTION_READ_COMMITTED, true)
            assertEquals("Alice", rls.read(7) { Queries.customer(CustomerId(1))!!.name })
            assertNull(rls.read(8) { Queries.customer(CustomerId(1)) })
            assertTrue(rls.readGlobal { session -> session.list("SELECT id FROM customer", 100, {}, { it.getInt(1) }).isEmpty() })
        } finally {
            sql("DROP POLICY tenant_read ON customer; ALTER TABLE customer DISABLE ROW LEVEL SECURITY; REVOKE SELECT ON customer FROM tenant_reader; DROP ROLE tenant_reader")
        }
    }

    @Test fun `Kotlin enums text adapters and asserted nonnull results round trip`() {
        val id = vev.write(7) { Queries.createDocument(DocumentStatus.SENT, ReviewState.PENDING, "Post@ReAI.no", "<n>1</n>", "10.0.0.1") }.id
        val row = vev.read(7) { Queries.document(id) }!!
        assertEquals(DocumentStatus.SENT, row.status)
        assertEquals(ReviewState.PENDING, row.review)
        assertEquals("10.0.0.1/32", row.address)
        assertEquals("<n>1</n>", row.body)
        assertEquals(1, vev.read(7) { Queries.documentsByEmail("POST@reai.NO").size })
        assertEquals(listOf(1L), vev.read(7) { Queries.documentStates().map { it.total } })
        sql("INSERT INTO document_record(id,tenant_id,status,email) VALUES (900,7,'UNKNOWN','test@example.invalid')")
        assertThrows(IllegalArgumentException::class.java) { vev.read(7) { Queries.document(900) } }
    }

    @Test fun `void queries hold advisory locks until transaction completion`() {
        fun locks(): Int = postgres.connect().use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT count(*) FROM pg_locks WHERE locktype='advisory'").use { result ->
                    result.next(); result.getInt(1)
                }
            }
        }
        vev.write(7) {
            Queries.lockTenant("registration")
            assertEquals(1,locks(),"the void function must execute and retain its transaction lock")
        }
        assertEquals(0,locks(),"commit must release the transaction lock")
    }
    @Test fun `RLS global scopes clear inherited session settings on a reused physical connection`() {
        postgres.connect().use { actual ->
            actual.createStatement().use { it.execute("SELECT set_config('vev.tenant_id','999',false)") }
            val pooled = java.lang.reflect.Proxy.newProxyInstance(javaClass.classLoader, arrayOf(javax.sql.DataSource::class.java)) { _, method, _ ->
                check(method.name == "getConnection")
                java.lang.reflect.Proxy.newProxyInstance(javaClass.classLoader, arrayOf(Connection::class.java)) { _, call, args ->
                    if (call.name == "close") { actual.autoCommit=true; actual.isReadOnly=false; null }
                    else try { call.invoke(actual, *(args ?: emptyArray())) }
                    catch (error: java.lang.reflect.InvocationTargetException) { throw error.targetException }
                }
            } as javax.sql.DataSource
            val scoped=Vev(pooled,Connection.TRANSACTION_READ_COMMITTED,true)
            fun setting() = Session.current().one("SELECT current_setting('vev.tenant_id',true)", {}, { it.getString(1) })
            assertEquals("7",scoped.read(7) { setting() })
            assertEquals("8",scoped.read(8) { setting() })
            assertEquals("",scoped.readGlobal { setting() })
            assertThrows(IllegalArgumentException::class.java) { scoped.write(7) { throw IllegalArgumentException("abort") } }
            assertEquals("",scoped.readGlobal { setting() })
        }
    }
    @Test fun `default isolation inherits configured connections and explicit overrides remain available`() {
        val configured=PGSimpleDataSource().apply {
            setURL(postgres.url());user=postgres.user();password=postgres.password()
            options="-c default_transaction_isolation=serializable"
        }
        fun level() = Session.current().one("SHOW transaction_isolation", {}, { it.getString(1) })
        assertEquals("serializable",Vev(configured).read(7) { level() })
        assertEquals("read committed",Vev(configured,Connection.TRANSACTION_READ_COMMITTED).read(7) { level() })
        assertEquals("serializable",Vev(configured).read(7) { level() })
    }
}
