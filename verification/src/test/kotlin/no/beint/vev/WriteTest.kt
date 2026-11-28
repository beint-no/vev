package no.beint.vev

import no.beint.vev.compiler.Postgres
import no.beint.vev.fixture.Queries
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.TestInstance
import no.beint.vev.fixture.CustomerId
import no.beint.vev.fixture.DocumentStatus
import no.beint.vev.fixture.ReviewState
import org.junit.jupiter.api.Assertions.*
import org.postgresql.ds.PGSimpleDataSource
import java.math.BigDecimal
import java.nio.file.Path
import java.sql.Connection
import java.sql.SQLException
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WriteTest {
    private lateinit var postgres: Postgres
    private lateinit var source: PGSimpleDataSource
    private lateinit var db: Db
    @BeforeAll fun start() {
        postgres=Postgres("")
        postgres.migrate(Path.of("src/main/schema"))
        source=PGSimpleDataSource().apply { setURL(postgres.url());user=postgres.user();password=postgres.password() }
        db=Db(source)
    }
    @AfterAll fun stop() { if (::postgres.isInitialized) postgres.close() }
    @BeforeEach fun reset() { postgres.connect().use { c -> c.createStatement().use { it.execute("TRUNCATE write_settings,customer,document_record RESTART IDENTITY CASCADE") } } }

    @Test fun `upsert returns trigger normalized generated and old-new values`() {
        val first=db.write(7) { Queries.saveWriteSettings(it, BigDecimal("12.30"),"  first  ") }
        assertNull(first.oldAmount)
        assertEquals(BigDecimal("12.30"),first.amount)
        assertEquals("first",first.label)
        assertEquals("FIRST",first.normalized)
        assertEquals(0,first.revision)
        val second=db.write(7) { Queries.saveWriteSettings(it, BigDecimal("42.50")," next ") }
        assertEquals(first.amount,second.oldAmount)
        assertEquals(BigDecimal("42.50"),second.amount)
        assertEquals("NEXT",second.normalized)
        assertEquals(1,second.revision)
        assertEquals(second.updatedAt,db.read(7) { Queries.writeSettings(it)!!.updatedAt })
    }
    @Test fun `nullable values can be cleared without deleting a settings row`() {
        db.write(7) { Queries.saveWriteSettings(it, BigDecimal.ONE,"one") }
        val cleared=db.write(7) { Queries.saveWriteSettings(it, null,"cleared") }
        assertEquals(BigDecimal("1.00"),cleared.oldAmount)
        assertNull(cleared.amount)
        assertNotNull(db.read(7) { Queries.writeSettings(it) })
    }
    @Test fun `tenant-keyed upserts updates and deletes cannot alter another tenant`() {
        db.write(7) { Queries.saveWriteSettings(it, BigDecimal.ONE,"seven") }
        db.write(8) { Queries.saveWriteSettings(it, BigDecimal.TEN,"eight") }
        db.write(7) { tx -> Queries.renameWriteSettings(tx, "renamed",0);Queries.deleteWriteSettings(tx) }
        assertNull(db.read(7) { Queries.writeSettings(it) })
        assertEquals("eight",db.read(8) { Queries.writeSettings(it)!!.label })
        assertNull(db.write(9) { Queries.renameWriteSettings(it, "absent",0) })
        assertNull(db.write(9) { Queries.deleteWriteSettings(it) })
    }
    @Test fun `optimistic update returns previous state and rejects stale revisions`() {
        db.write(7) { Queries.saveWriteSettings(it, BigDecimal.ONE,"original") }
        val changed=db.write(7) { Queries.renameWriteSettings(it, "changed",0) }!!
        assertEquals("original",changed.oldLabel)
        assertEquals("changed",changed.label)
        assertEquals(1,changed.revision)
        db.write(7) { tx ->
            assertNull(Queries.renameWriteSettings(tx, "stale",0))
            assertEquals("changed",Queries.writeSettings(tx)!!.label)
        }
    }
    @Test fun `conditional upsert distinguishes zero returned rows from a nullable value`() {
        db.write(7) { tx ->
            assertNotNull(Queries.conditionalWriteSettings(tx, null,0))
            assertNull(Queries.conditionalWriteSettings(tx, BigDecimal.TEN,9))
            assertNull(Queries.writeSettings(tx)!!.amount)
            assertNotNull(Queries.conditionalWriteSettings(tx, BigDecimal.ONE,0))
        }
    }
    @Test fun `delete returning exposes old state and null new state`() {
        db.write(7) { Queries.saveWriteSettings(it, BigDecimal.ONE,"delete") }
        val deleted=db.write(7) { Queries.deleteWriteSettings(it) }!!
        assertEquals(BigDecimal("1.00"),deleted.amount)
        assertEquals("delete",deleted.label)
        assertNull(deleted.newAmount)
        assertNull(db.read(7) { Queries.writeSettings(it) })
    }
    @Test fun `application failure rolls back an upsert and subsequent update together`() {
        db.write(7) { Queries.saveWriteSettings(it, BigDecimal.ONE,"original") }
        assertThrows(IllegalArgumentException::class.java) {
            db.write(7) { tx -> Queries.saveWriteSettings(tx, BigDecimal.TEN,"new");Queries.renameWriteSettings(tx, "last",1);throw IllegalArgumentException("abort") }
        }
        val row=db.read(7) { Queries.writeSettings(it) }!!
        assertEquals("original",row.label)
        assertEquals(0,row.revision)
    }
    @Test fun `caught returning decode failures poison and roll back successful mutations`() {
        assertThrows(VevSessionException::class.java) {
            db.write(7) { tx -> try { Queries.writeDecodeFailure(tx) } catch (_: VevDataException) { } }
        }
        assertNull(db.read(7) { Queries.writeSettings(it) })
    }
    @Test fun `constraint failures preserve SQLSTATE and roll back previous writes`() {
        val failure=assertThrows(VevSqlException::class.java) {
            db.write(7) { tx -> Queries.saveWriteSettings(tx, BigDecimal.ONE,"first");Queries.saveWriteSettings(tx, BigDecimal.ZERO,"invalid") }
        }
        assertEquals("23514",failure.sqlState())
        assertTrue(failure.isCheckViolation())
        assertNull(db.read(7) { Queries.writeSettings(it) })
    }
    @Test fun `affected row counts preserve empty arrays exact matches and tenant predicates`() {
        val ids=db.write(7) { tx -> (1..3).map { Queries.createCustomer(tx, "customer $it").id!!.value } }
        val other=db.write(8) { Queries.createCustomer(it, "other").id!!.value }
        db.write(7) { tx ->
            assertEquals(0L,Queries.renameCustomers(tx, "empty",emptyList()))
            assertEquals(0L,Queries.renameCustomers(tx, "other",listOf(other)))
            assertEquals(3L,Queries.renameCustomers(tx, "renamed",ids+other))
        }
        assertEquals("other",db.read(8) { Queries.customer(it, CustomerId(other))!!.name })
    }
    @Test fun `simultaneous first upserts never lose an update or create duplicate tenant rows`() {
        val barrier=CyclicBarrier(12)
        Executors.newVirtualThreadPerTaskExecutor().use { executor ->
            val futures=(0 until 12).map { n -> executor.submit {
                barrier.await(10,TimeUnit.SECONDS)
                db.write(7) { Queries.saveWriteSettings(it, BigDecimal(n+1),"writer $n") }
            } }
            futures.forEach { it.get(20,TimeUnit.SECONDS) }
        }
        assertEquals(11,db.read(7) { Queries.writeSettings(it)!!.revision })
    }
    @Test fun `simultaneous optimistic updates allow exactly one winner`() {
        db.write(7) { Queries.saveWriteSettings(it, null,"original") }
        val barrier=CyclicBarrier(4)
        Executors.newVirtualThreadPerTaskExecutor().use { executor ->
            val futures=(1..4).map { n -> executor.submit<Boolean> {
                barrier.await(10,TimeUnit.SECONDS)
                db.write(7) { Queries.renameWriteSettings(it, "writer $n",0)!=null }
            } }
            assertEquals(1,futures.count { it.get(20,TimeUnit.SECONDS) })
        }
        assertEquals(1,db.read(7) { Queries.writeSettings(it)!!.revision })
    }
    @Test fun `serialization failures are propagated once and leave no partial changes`() {
        db.write(7) { Queries.saveWriteSettings(it, null,"original") }
        val serial=Db(source,Connection.TRANSACTION_SERIALIZABLE)
        val barrier=CyclicBarrier(2)
        Executors.newVirtualThreadPerTaskExecutor().use { executor ->
            val futures=(1..2).map { n -> executor.submit<String> {
                try {
                    serial.write(7) { tx -> Queries.writeSettings(tx);barrier.await(10,TimeUnit.SECONDS);Queries.renameWriteSettings(tx, "writer $n",0) }
                    "committed"
                } catch (failure: VevSqlException) { failure.sqlState()!! }
            } }
            assertEquals(listOf("40001","committed"),futures.map { it.get(20,TimeUnit.SECONDS) }.sorted())
        }
        assertEquals(1,db.read(7) { Queries.writeSettings(it)!!.revision })
    }
    @Test fun `lock timeout rolls back the waiting writer without disturbing the lock owner`() {
        val id=db.write(7) { Queries.createCustomer(it, "original").id!!.value }
        db.write(7) { tx ->
            Queries.renameCustomers(tx, "owner",listOf(id))
            Executors.newVirtualThreadPerTaskExecutor().use { executor ->
                val state=executor.submit<String> {
                    try { db.write(7) { s -> s.prepare("SET LOCAL lock_timeout='50ms'").use { it.execute() };Queries.renameCustomers(s, "waiter",listOf(id)) };"unexpected" }
                    catch (failure: VevSqlException) { failure.sqlState()!! }
                }.get(10,TimeUnit.SECONDS)
                assertEquals("55P03",state)
            }
        }
        assertEquals("owner",db.read(7) { Queries.customer(it, CustomerId(id))!!.name })
    }
    @Test fun `deadlock victim rolls back its first write and is not automatically retried`() {
        val ids=db.write(7) { tx -> listOf(Queries.createCustomer(tx, "one").id!!.value,Queries.createCustomer(tx, "two").id!!.value) }
        val barrier=CyclicBarrier(2)
        Executors.newVirtualThreadPerTaskExecutor().use { executor ->
            val futures=(0..1).map { n -> executor.submit<Pair<Int,String>> {
                try {
                    db.write(7) { s ->
                        s.prepare("SET LOCAL deadlock_timeout='50ms'").use { it.execute() }
                        Queries.renameCustomers(s, "$n first",listOf(ids[n]))
                        barrier.await(10,TimeUnit.SECONDS)
                        Queries.renameCustomers(s, "$n second",listOf(ids[1-n]))
                    };n to "committed"
                } catch (failure: VevSqlException) { n to (failure.sqlState() ?: "") }
            } }
            val outcomes=futures.map { it.get(20,TimeUnit.SECONDS) }
            assertEquals(listOf("40P01","committed"),outcomes.map { it.second }.sorted())
            val winner=outcomes.single { it.second=="committed" }.first
            assertTrue(db.read(7) { Queries.customers(it, null).all { r -> r.name!!.startsWith("$winner ") } })
        }
    }
    @Test fun `borrowed caller can recover at a savepoint only by starting a new scope`() {
        postgres.connect().use { connection ->
            connection.autoCommit=false
            Session.borrowTenantWrite(connection, 7) { Queries.saveWriteSettings(it, BigDecimal.ONE,"before") }
            val point=connection.setSavepoint()
            assertThrows(VevSessionException::class.java) {
                Session.borrowTenantWrite(connection, 7) { tx ->
                    try { Queries.saveWriteSettings(tx, BigDecimal.ZERO,"invalid") } catch (_: SQLException) { connection.rollback(point) }
                    Queries.writeSettings(tx)
                }
            }
            Session.borrowTenantWrite(connection, 7) { Queries.renameWriteSettings(it, "recovered",0) }
            connection.commit()
        }
        assertEquals("recovered",db.read(7) { Queries.writeSettings(it)!!.label })
    }
    @Test fun `global skip-locked claims hand distinct rows to concurrent workers and transition enum status`() {
        val first=db.write(7) { Queries.createDocument(it, DocumentStatus.DRAFT,null,"a@example.test",null,null).id }
        val second=db.write(8) { Queries.createDocument(it, DocumentStatus.DRAFT,null,"b@example.test",null,null).id }
        val barrier=CyclicBarrier(2)
        val targets=listOf(DocumentStatus.SENT,DocumentStatus.PAID)
        Executors.newVirtualThreadPerTaskExecutor().use { executor ->
            val futures=(0..1).map { worker -> executor.submit<Pair<Int,DocumentStatus>> {
                db.write { tx ->
                    val claimed=Queries.claimNextDocument(tx)!!
                    assertEquals(DocumentStatus.DRAFT,claimed.status)
                    barrier.await(10,TimeUnit.SECONDS)
                    assertEquals(1L,Queries.transitionDocumentStatus(tx, targets[worker],claimed.id))
                    claimed.id to targets[worker]
                }
            } }
            val results=futures.map { it.get(20,TimeUnit.SECONDS) }.toMap()
            assertEquals(setOf(first,second),results.keys)
            assertNull(db.write { Queries.claimNextDocument(it) })
            val firstRow=db.read(7) { Queries.document(it, first)!! }
            val secondRow=db.read(8) { Queries.document(it, second)!! }
            assertEquals(results[first],firstRow.status)
            assertEquals(if (results[first]==DocumentStatus.PAID) ReviewState.APPROVED else null,firstRow.review)
            assertEquals(results[second],secondRow.status)
            assertEquals(if (results[second]==DocumentStatus.PAID) ReviewState.APPROVED else null,secondRow.review)
        }
    }

    @Test fun `savepoint recovers from PostgreSQL constraint errors and keeps outer writes atomic`() {
        val events = mutableListOf<String>()
        db.write(7) { outer ->
            Queries.saveWriteSettings(outer, BigDecimal.ONE, "before")
            outer.afterCommit { events.add("outer") }
            val failure = assertThrows(VevSqlException::class.java) {
                db.tenantSavepoint { sp ->
                    sp.afterCommit { events.add("rolled-back") }
                    Queries.saveWriteSettings(sp, BigDecimal.ZERO, "invalid")
                }
            }
            assertTrue(failure.isCheckViolation())
            // Outer transaction recovered cleanly from PostgreSQL 25P02 aborted state.
            val current = Queries.writeSettings(outer)!!
            assertEquals("before", current.label)
            Queries.renameWriteSettings(outer, "after-recovery", 0)
        }
        assertEquals(listOf("outer"), events)
        assertEquals("after-recovery", db.read(7) { Queries.writeSettings(it)!!.label })
    }

    @Test fun `readRepeatable, writeSerializable, readNew, and withTimeout work against PostgreSQL 19`() {
        fun level(tx: ReadScope) = tx.prepare("SHOW transaction_isolation").use { s -> s.executeQuery().use { it.next(); it.getString(1) } }
        fun timeout(tx: ReadScope) = tx.prepare("SHOW statement_timeout").use { s -> s.executeQuery().use { it.next(); it.getString(1) } }

        assertEquals("repeatable read", db.readRepeatable(7) { level(it) })
        assertEquals("serializable", db.writeSerializable(7) { level(it) })

        db.write(7) { outer ->
            Queries.createCustomer(outer, "uncommitted")
            val visibleInReadNew = db.readNew(7) { inner ->
                assertFalse(db.inWriteTransaction())
                Queries.customers(inner, null)
            }
            assertTrue(visibleInReadNew.isEmpty(), "readNew must only see committed data")
        }
        assertEquals(1, db.read(7) { Queries.customers(it, null).size })

        // Raw JDBC statements on tx.connection() inherit PostgreSQL SET LOCAL statement_timeout from withTimeout / Db.queryTimeout.
        val timedDb = Db.builder(source).queryTimeout(java.time.Duration.ofSeconds(30)).build()
        val canceled = assertThrows(VevSqlException::class.java) {
            timedDb.withTimeout(java.time.Duration.ofMillis(250)).read(7) { inner ->
                assertEquals("250ms", timeout(inner))
                inner.connection().createStatement().use { it.execute("SELECT pg_sleep(5)") }
            }
        }
        assertTrue(canceled.isQueryCanceled())
        // Restored when nested withTimeout succeeds cleanly.
        timedDb.read(7) { outer ->
            assertEquals("30s", timeout(outer))
            timedDb.withTimeout(java.time.Duration.ofSeconds(5)).read(7) { inner ->
                assertEquals("5s", timeout(inner))
            }
            assertEquals("30s", timeout(outer))
        }
    }

    @Test fun `withLockTimeout enforces PostgreSQL lock_timeout, composes with withTimeout, and restores across nesting and savepoints`() {
        fun lockTimeout(tx: ReadScope) = tx.prepare("SHOW lock_timeout").use { s -> s.executeQuery().use { it.next(); it.getString(1) } }
        fun stmtTimeout(tx: ReadScope) = tx.prepare("SHOW statement_timeout").use { s -> s.executeQuery().use { it.next(); it.getString(1) } }

        val id = db.write(7) { Queries.createCustomer(it, "original").id!!.value }
        db.write(7) { tx ->
            Queries.renameCustomers(tx, "owner", listOf(id))
            Executors.newVirtualThreadPerTaskExecutor().use { executor ->
                val failure = executor.submit<VevSqlException> {
                    assertThrows(VevSqlException::class.java) {
                        db.withLockTimeout(java.time.Duration.ofMillis(50)).write(7) { s ->
                            Queries.renameCustomers(s, "waiter", listOf(id))
                        }
                    }
                }.get(10, TimeUnit.SECONDS)
                assertTrue(failure.isLockNotAvailable())
            }
        }

        val timedDb = Db.builder(source)
            .queryTimeout(java.time.Duration.ofSeconds(30))
            .lockTimeout(java.time.Duration.ofSeconds(2))
            .build()
        timedDb.write(7) { outer ->
            assertEquals("30s", stmtTimeout(outer))
            assertEquals("2s", lockTimeout(outer))
            timedDb.withTimeout(java.time.Duration.ofSeconds(5)).withLockTimeout(java.time.Duration.ofMillis(500)).write(7) { inner ->
                assertEquals("5s", stmtTimeout(inner))
                assertEquals("500ms", lockTimeout(inner))
            }
            assertEquals("30s", stmtTimeout(outer))
            assertEquals("2s", lockTimeout(outer))

            // Savepoint rollback automatically reverts SET LOCAL, and normal savepoint release restores outer lock_timeout.
            assertThrows(IllegalStateException::class.java) {
                timedDb.withLockTimeout(java.time.Duration.ofMillis(250)).tenantSavepoint { sp ->
                    assertEquals("250ms", lockTimeout(sp))
                    throw IllegalStateException("rollback sp")
                }
            }
            assertEquals("2s", lockTimeout(outer))
            timedDb.withLockTimeout(null).tenantSavepoint { sp ->
                assertEquals("0", lockTimeout(sp))
            }
            assertEquals("2s", lockTimeout(outer))
        }
    }
}
