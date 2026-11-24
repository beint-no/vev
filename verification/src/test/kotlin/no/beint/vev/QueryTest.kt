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
import java.util.concurrent.CompletableFuture

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class QueryTest {
    private lateinit var postgres: Postgres
    private lateinit var db: Db
    private lateinit var source: PGSimpleDataSource

    @BeforeAll fun start() {
        postgres = Postgres("")
        postgres.migrate(Path.of("src/main/schema"))
        source = PGSimpleDataSource().apply { setURL(postgres.url()); user = postgres.user(); password = postgres.password() }
        db = Db(source)
    }
    @AfterAll fun stop() { if (::postgres.isInitialized) postgres.close() }
    @BeforeEach fun reset() = sql("TRUNCATE customer,invoice,posting,scalar_values,document_record RESTART IDENTITY CASCADE")
    private fun sql(text: String) { postgres.connect().use { c -> c.createStatement().use { it.execute(text) } } }

    @Test fun `wide write binder loads and executes`() {
        val method = Queries::class.java.methods.single { it.name == "wideWrite" }
        assertNotNull(db.write(7) { tx -> method.invoke(null, tx, *Array<Any?>(252) { 1L }) })
        val primitive = Queries::class.java.methods.single { it.name == "widePrimitiveWrite" }
        assertNotNull(db.write(7) { tx -> primitive.invoke(null, tx, *Array<Any?>(126) { 1L }) })
        val global = Queries::class.java.methods.single { it.name == "wideGlobalWrite" }
        assertNotNull(db.write { tx -> global.invoke(null, tx, *Array<Any?>(253) { 1L }) })
    }

    @Test fun `internal wildcards preserve the explicit generated row and tenant predicate`() {
        db.write(7) { Queries.createCustomer(it, "Synthetic local") }
        db.write(8) { Queries.createCustomer(it, "Synthetic other") }
        assertEquals(listOf("Synthetic local"), db.read(7) { Queries.nestedCustomers(it).map { r -> r.name } })
        assertEquals(listOf("Synthetic other"), db.read(8) { Queries.nestedCustomers(it).map { r -> r.name } })
    }

    @Test fun `data classes at JVM parameter slot boundaries compile and execute`() {
        val nullable = db.read { Queries.wideNullable(it) }
        assertNull(nullable.c1)
        assertEquals(1L, nullable.c245)
        val required = db.read { Queries.wideRequired(it) }
        assertEquals(1L, required.c1)
        assertEquals(1L, required.c124)
    }

    @Test fun `tenant binding needs no query plumbing and isolates rows`() {
        val id = db.write(7) { Queries.createCustomer(it, "Alice").id!! }
        db.write(8) { Queries.createCustomer(it, "Other tenant") }
        assertEquals("Alice", db.read(7) { Queries.customer(it, id)!!.name })
        assertNull(db.read(8) { Queries.customer(it, id) })
        assertEquals(1, db.read(7) { Queries.customers(it, null).size })
        assertNull(db.read(7) { Queries.invoiceSummary(it).single().invoiceId })
    }

    @Test fun `invalid tenant identifiers fail closed`() {
        assertThrows(IllegalArgumentException::class.java) { db.read(0) { fail<Any>("executed") } }
        assertThrows(IllegalArgumentException::class.java) { db.write(-1) { fail<Any>("executed") } }
    }

    @Test fun `borrowed tenant scope rejects missing tenants and preserves caller ownership`() {
        postgres.connect().use { connection ->
            connection.autoCommit = false
            for (tenantId in listOf(0, -1)) {
                assertThrows(IllegalArgumentException::class.java) {
                    Session.borrowTenantWrite(connection, tenantId) { fail<Unit>("executed") }
                }
            }
            Session.borrowTenantWrite(connection, 7) { Queries.createCustomer(it, "caller owns rollback") }
            assertFalse(connection.isClosed)
            connection.rollback()
            assertTrue(db.read(7) { Queries.customers(it, null).isEmpty() })
        }
    }

    @Test fun `materialized rows stay ordered and immutable without exposing the backing list`() {
        db.write(7) { tx -> repeat(3) { Queries.createCustomer(tx, "Customer $it") } }
        val rows = db.read(7) { Queries.customers(it, null) }
        assertEquals(listOf("Customer 0", "Customer 1", "Customer 2"), rows.map { it.name })
        assertTrue(rows is java.util.RandomAccess)
        assertThrows(UnsupportedOperationException::class.java) { (rows as MutableList).removeAt(0) }
    }

    @Test fun `explicit global queries can run inside tenant and global transactions`() {
        assertTrue(db.read { Queries.hasKey(it, "{\"yes\":1}", "yes").present }!!)
        assertEquals(":not_a_parameter; \$dollars", db.read(7) { Queries.quoted(it).textValue })
    }

    @Test fun `nested transactions reject tenant switching without changing the outer context`() {
        db.write(7) { outer ->
            assertThrows(VevSessionException::class.java) { db.write(8) { Queries.createCustomer(it, "wrong") } }
            assertEquals(7, outer.tenantId())
            Queries.createCustomer(outer, "correct")
        }
        assertTrue(db.read(8) { Queries.customers(it, null).isEmpty() })
        assertEquals("correct", db.read(7) { Queries.customers(it, null).single().name })
    }

    @Test fun `reentrant read inside write joins the same transaction and sees uncommitted writes`() {
        db.write(7) { tx ->
            Queries.createCustomer(tx, "Visible inside tx")
            val names = db.read(7) { inner -> Queries.customers(inner, null).map { it.name } }
            assertEquals(listOf("Visible inside tx"), names)
        }
    }

    @Test fun `scope does not leak after exceptions or across worker threads`() {
        assertThrows(IllegalArgumentException::class.java) { db.read(7) { throw IllegalArgumentException("abort") } }
        assertFalse(db.inTransaction())
        db.read(7) { tx ->
            val error = CompletableFuture.supplyAsync { runCatching { Queries.customers(tx, null) }.exceptionOrNull() }.get()
            assertInstanceOf(VevSessionException::class.java, error)
        }
        assertFalse(db.inTransaction())
    }

    @Test fun `virtual thread transactions isolate scoped values`() {
        java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor().use { executor ->
            val futures = (1..32).map { tenant -> executor.submit<Int> { db.read(tenant) { it.tenantId() } } }
            assertEquals((1..32).toList(), futures.map { it.get() })
        }
    }

    @Test fun `sessions cannot escape their scope or cross threads`() {
        var escaped: TenantReadScope? = null
        db.read(7) { session ->
            escaped = session
            val error = CompletableFuture.supplyAsync { runCatching { session.tenantId() }.exceptionOrNull() }.get()
            assertInstanceOf(VevSessionException::class.java, error)
        }
        assertThrows(VevSessionException::class.java) { escaped!!.tenantId() }
    }

    @Test fun `joins CTEs and optimistic writes retain results and conflict detection`() {
        db.write(7) { Queries.createCustomer(it, "Alice") }
        sql("INSERT INTO invoice(tenant_id,customer_id,amount) VALUES(7,1,10.00)")
        assertEquals(BigDecimal("10.00"), db.read(7) { Queries.totals(it).single().total })
        assertEquals(1, db.write(7) { Queries.updateInvoice(it, BigDecimal("12.50"), null, InvoiceId(1), 0) }!!.version)
        assertNull(db.write(7) { Queries.updateInvoice(it, BigDecimal("14"), "stale", InvoiceId(1), 0) })
        assertEquals(BigDecimal("12.50"), db.read(7) { Queries.invoiceSummary(it).single().amount })
    }

    @Test fun `deferred business constraints are checked at commit`() {
        db.write { tx -> Queries.post(tx, 1, BigDecimal.TEN); Queries.post(tx, 1, BigDecimal.TEN.negate()) }
        assertThrows(VevSqlException::class.java) { db.write { Queries.post(it, 2, BigDecimal.ONE) } }
    }

    @Test fun `read only transactions reject raw JDBC writes and nested write escalation`() {
        val sqlFailure = assertThrows(VevSqlException::class.java) {
            db.read(7) { tx ->
                tx.prepare("INSERT INTO customer(tenant_id, name) VALUES (7, 'rejected')").use { it.executeUpdate() }
            }
        }
        assertEquals("25006", sqlFailure.sqlState())
        assertThrows(VevSessionException::class.java) {
            db.read(7) { db.write(7) { Queries.createCustomer(it, "rejected") } }
        }
        assertTrue(db.read(7) { Queries.customers(it, null).isEmpty() })
    }

    @Test fun `cardinality failures fail with VevDataException rather than truncate`() {
        assertThrows(VevDataException::class.java) { db.read(7) { Queries.exactlyOne(it) } }
        db.write(7) { tx -> repeat(6) { Queries.createCustomer(tx, "Person $it") } }
        assertThrows(VevDataException::class.java) { db.read(7) { Queries.exactlyOne(it) } }
    }

    @Test fun `lists return every row`() {
        assertEquals(5000, db.read { Queries.boundedSeries(it, 5000) }.size)
    }

    @Test fun `custom plan queries never become server-prepared statements`() {
        postgres.connect().use { connection ->
            connection.autoCommit = false
            fun namedStatements(): Int = connection.createStatement().use { statement ->
                statement.executeQuery("SELECT count(*) FROM pg_prepared_statements WHERE NOT from_sql").use { it.next(); it.getInt(1) }
            }
            Session.borrowRead(connection) { tx ->
                repeat(10) { assertEquals(if (it % 2 == 0) 20 else 11, Queries.customPlanSeries(tx, if (it % 2 == 0) null else 10).size) }
            }
            assertEquals(0, namedStatements(), "A custom plan query must stay an unnamed statement")
            Session.borrowRead(connection) { tx -> repeat(10) { Queries.boundedSeries(tx, 3) } }
            assertTrue(namedStatements() > 0, "Without the directive pgJDBC server-prepares a repeated statement")
            connection.rollback()
        }
    }

    @Test fun `caught query failures poison and roll back the transaction`() {
        assertThrows(VevSessionException::class.java) {
            db.write(7) { tx ->
                Queries.createCustomer(tx, "rollback")
                Queries.createCustomer(tx, "second")
                try { Queries.exactlyOne(tx) } catch (_: VevDataException) { }
            }
        }
        assertTrue(db.read(7) { Queries.customers(it, null).isEmpty() })
    }

    @Test fun `single row returning violations roll back every affected row even when caught`() {
        for (optional in listOf(false, true)) {
            assertThrows(VevSessionException::class.java) {
                db.write(7) { tx ->
                    try {
                        if (optional) Queries.createSyntheticCustomersOptional(tx)
                        else Queries.createSyntheticCustomersOne(tx)
                    } catch (_: VevDataException) { }
                }
            }
            assertTrue(db.read(7) { Queries.customers(it, null).isEmpty() })
        }
    }

    @Test fun `competing optimistic writes have one winner across independent transactions`() {
        db.write(7) { Queries.createCustomer(it, "Synthetic") }
        sql("INSERT INTO invoice(tenant_id,customer_id,amount) VALUES(7,1,10.00)")
        val barrier = java.util.concurrent.CyclicBarrier(2)
        java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor().use { executor ->
            val updates = (1..2).map { number -> executor.submit<Boolean> {
                barrier.await(10, java.util.concurrent.TimeUnit.SECONDS)
                db.write(7) { Queries.updateInvoice(it, BigDecimal(number), null, InvoiceId(1), 0) != null }
            } }
            assertEquals(1, updates.count { it.get(20, java.util.concurrent.TimeUnit.SECONDS) })
        }
        assertEquals(1, db.read(7) { session -> session.prepare("SELECT version FROM invoice WHERE tenant_id=7 AND id=1").use { s -> s.executeQuery().use { it.next(); it.getInt(1) } } })
    }

    @Test fun `borrowed transaction leaves ownership with caller`() {
        postgres.connect().use { c ->
            c.autoCommit = false
            Session.borrowTenantWrite(c, 7) { Queries.createCustomer(it, "not committed") }
            assertFalse(c.isClosed)
            c.rollback()
        }
        assertTrue(db.read(7) { Queries.customers(it, null).isEmpty() })
    }

    @Test fun `tenant predicates add no database statements`() {
        db.write(7) { Queries.createCustomer(it, "Alice") }
        var prepared = 0
        postgres.connect().use { actual ->
            actual.autoCommit = false
            val observed = java.lang.reflect.Proxy.newProxyInstance(javaClass.classLoader, arrayOf(Connection::class.java)) { _, method, args ->
                if (method.name == "prepareStatement") prepared++
                try { method.invoke(actual, *(args ?: emptyArray())) }
                catch (error: java.lang.reflect.InvocationTargetException) { throw error.targetException }
            } as Connection
            Session.borrowTenantRead(observed, 7) { tx -> Queries.customers(tx, null); Queries.scopedCustomer(tx, CustomerId(1)) }
            actual.rollback()
        }
        assertEquals(2, prepared)
    }

    @Test fun `values and array elements are bound safely`() {
        db.write(7) { Queries.createCustomer(it, "'; DROP TABLE customer; --") }
        assertEquals(listOf(1), db.read(7) { Queries.customerIds(it, listOf(1, null)).map { r -> r.id } })
        assertTrue(db.read(7) { Queries.customerIds(it, emptyList()).isEmpty() })
        assertEquals("bound value", db.read { Queries.localNames(it, "bound value", 42).bindings })
    }

    @Test fun `Kotlin enums text adapters and asserted nonnull results round trip`() {
        val id = db.write(7) { Queries.createDocument(it, DocumentStatus.SENT, ReviewState.PENDING, "Post@ReAI.no", "<n>1</n>", "10.0.0.1") }.id
        val row = db.read(7) { Queries.document(it, id) }!!
        assertEquals(DocumentStatus.SENT, row.status)
        assertEquals(ReviewState.PENDING, row.review)
        assertEquals("10.0.0.1/32", row.address)
        assertEquals("<n>1</n>", row.body)
        assertEquals(1, db.read(7) { Queries.documentsByEmail(it, "POST@reai.NO").size })
        assertEquals(listOf(1L), db.read(7) { Queries.documentStates(it).map { r -> r.total } })
        sql("INSERT INTO document_record(id,tenant_id,status,email) VALUES (900,7,'UNKNOWN','test@example.invalid')")
        assertThrows(IllegalArgumentException::class.java) { db.read(7) { Queries.document(it, 900) } }
    }

    @Test fun `void queries hold advisory locks until transaction completion`() {
        fun locks(): Int = postgres.connect().use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT count(*) FROM pg_locks WHERE locktype='advisory'").use { result ->
                    result.next(); result.getInt(1)
                }
            }
        }
        db.write(7) { tx ->
            Queries.lockTenant(tx, "registration")
            assertEquals(1, locks(), "the void function must execute and retain its transaction lock")
        }
        assertEquals(0, locks(), "commit must release the transaction lock")
    }

    @Test fun `default isolation inherits configured connections and explicit overrides remain available`() {
        val configured = PGSimpleDataSource().apply {
            setURL(postgres.url()); user = postgres.user(); password = postgres.password()
            options = "-c default_transaction_isolation=serializable"
        }
        fun level(tx: ReadScope) = tx.prepare("SHOW transaction_isolation").use { s -> s.executeQuery().use { it.next(); it.getString(1) } }
        assertEquals("serializable", Db(configured).read(7) { level(it) })
        assertEquals("read committed", Db(configured, Connection.TRANSACTION_READ_COMMITTED).read(7) { level(it) })
        assertEquals("serializable", Db(configured).read(7) { level(it) })
    }

    @Test fun `stream queries deliver every row and poison on consumer errors`() {
        var count = 0
        var sum = 0L
        db.read { tx ->
            Queries.streamSeries(tx, 2500) { row ->
                count++
                sum += row.n
            }
        }
        assertEquals(2500, count)
        assertEquals(2500L * 2501L / 2L, sum)

        assertThrows(VevSessionException::class.java) {
            db.write(7) { tx ->
                Queries.createCustomer(tx, "rolled back on stream failure")
                try {
                    Queries.streamSeries(tx, 10) { row ->
                        if (row.n == 3) throw IllegalArgumentException("stop streaming")
                    }
                } catch (_: IllegalArgumentException) { }
            }
        }
        assertTrue(db.read(7) { Queries.customers(it, null).isEmpty() })
    }

    @Test fun `query timeout cancels slow statements and rolls back writes`() {
        val timed = Db(source, Db.DEFAULT_ISOLATION, java.time.Duration.ofSeconds(1))
        val failure = assertThrows(VevSqlException::class.java) {
            timed.write(7) { tx ->
                Queries.createCustomer(tx, "rolled back on timeout")
                Queries.sleepSeconds(tx, 5.0)
            }
        }
        assertEquals("57014", failure.sqlState())
        assertTrue(failure.isQueryCanceled())
        assertTrue(failure.isTransient())
        assertTrue(db.read(7) { Queries.customers(it, null).isEmpty() })
    }

    @Test fun `bounded reads keep fetchSize at zero so PostgreSQL parallel workers can run`() {
        val observedFetchSizes = mutableListOf<Int>()
        postgres.connect().use { actual ->
            actual.autoCommit = false
            val observed = java.lang.reflect.Proxy.newProxyInstance(javaClass.classLoader, arrayOf(Connection::class.java)) { _, method, args ->
                val result = try { method.invoke(actual, *(args ?: emptyArray())) }
                catch (error: java.lang.reflect.InvocationTargetException) { throw error.targetException }
                if (method.name == "prepareStatement" && result is java.sql.PreparedStatement) {
                    java.lang.reflect.Proxy.newProxyInstance(javaClass.classLoader, arrayOf(java.sql.PreparedStatement::class.java)) { _, sm, sargs ->
                        if (sm.name == "setFetchSize") observedFetchSizes.add(sargs[0] as Int)
                        try { sm.invoke(result, *(sargs ?: emptyArray())) }
                        catch (error: java.lang.reflect.InvocationTargetException) { throw error.targetException }
                    }
                } else result
            } as Connection
            Session.borrowTenantRead(observed, 7) { tx ->
                Queries.boundedSeries(tx, 10)
                Queries.customers(tx, null)
            }
            assertTrue(observedFetchSizes.isEmpty(), "Bounded reads must leave fetchSize at 0: $observedFetchSizes")
            Session.borrowTenantRead(observed, 7) { tx ->
                Queries.streamSeries(tx, 10) { }
            }
            assertEquals(listOf(Session.DEFAULT_STREAM_FETCH_SIZE), observedFetchSizes)
            actual.rollback()
        }
    }

    @Test fun `tenant stream queries bind array parameters and decode enums across multiple fetch batches`() {
        val tenant7Ids = db.write(7) { tx ->
            (1..300).map { i ->
                val status = if (i % 2 == 0) DocumentStatus.SENT else DocumentStatus.DRAFT
                Queries.createDocument(tx, status, null, "t7-$i@example.test", null, null).id
            }
        }
        val tenant8Ids = db.write(8) { tx ->
            (1..10).map { i ->
                Queries.createDocument(tx, DocumentStatus.PAID, null, "t8-$i@example.test", null, null).id
            }
        }

        val streamed = mutableListOf<Pair<Int, DocumentStatus>>()
        db.read(7) { tx ->
            Queries.streamDocumentsByIds(tx, tenant7Ids + tenant8Ids) { row ->
                streamed.add(row.id to row.status)
            }
        }
        assertEquals(300, streamed.size)
        assertEquals(tenant7Ids, streamed.map { it.first })
        assertEquals(150, streamed.count { it.second == DocumentStatus.SENT })
        assertEquals(150, streamed.count { it.second == DocumentStatus.DRAFT })
    }

    @Test fun `tenant DISTINCT ON and WITH RECURSIVE queries preserve ordering and tenant isolation`() {
        val (aLatest, bLatest) = db.write(91) { tx ->
            Queries.createDocument(tx, DocumentStatus.DRAFT, null, "alpha@example.test", null, null)
            val a2 = Queries.createDocument(tx, DocumentStatus.PAID, null, "alpha@example.test", null, null).id
            val b1 = Queries.createDocument(tx, DocumentStatus.SENT, null, "beta@example.test", null, null).id
            a2 to b1
        }
        db.write(92) { tx ->
            Queries.createDocument(tx, DocumentStatus.SENT, null, "alpha@example.test", null, null)
            Queries.createDocument(tx, DocumentStatus.PAID, null, "gamma@example.test", null, null)
        }

        val latest = db.read(91) { tx ->
            Queries.latestDocumentByEmails(tx, listOf("alpha@example.test", "beta@example.test", "gamma@example.test"))
        }
        assertEquals(
            listOf(Triple("alpha@example.test", aLatest, DocumentStatus.PAID), Triple("beta@example.test", bLatest, DocumentStatus.SENT)),
            latest.map { Triple(it.email, it.id, it.status) },
        )

        val root = db.write(91) { tx ->
            val r = Queries.createMessageChainNode(tx, null, "root").id
            val child = Queries.createMessageChainNode(tx, r, "child").id
            Queries.createMessageChainNode(tx, child, "grandchild")
            r
        }
        val foreignRoot = db.write(92) { tx ->
            Queries.createMessageChainNode(tx, root, "cross-tenant-child")
            Queries.createMessageChainNode(tx, null, "foreign-root").id
        }

        val tree = db.read(91) { Queries.messageChainTree(it, listOf(root, foreignRoot)) }
        assertEquals(listOf("root", "child", "grandchild"), tree.map { it.label })
        assertTrue(tree.all { it.rootId == root })
    }
}
