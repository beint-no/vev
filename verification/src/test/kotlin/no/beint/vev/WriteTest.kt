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
    private lateinit var vev: Vev
    @BeforeAll fun start() {
        postgres=Postgres("")
        postgres.migrate(Path.of("src/main/schema"))
        source=PGSimpleDataSource().apply { setURL(postgres.url());user=postgres.user();password=postgres.password() }
        vev=Vev(source)
    }
    @AfterAll fun stop() { if (::postgres.isInitialized) postgres.close() }
    @BeforeEach fun reset() { postgres.connect().use { c -> c.createStatement().use { it.execute("TRUNCATE write_settings,customer,document_record RESTART IDENTITY CASCADE") } } }

    @Test fun `upsert returns trigger normalized generated and old-new values`() {
        val first=vev.write(7) { Queries.saveWriteSettings(BigDecimal("12.30"),"  first  ") }
        assertNull(first.oldAmount)
        assertEquals(BigDecimal("12.30"),first.amount)
        assertEquals("first",first.label)
        assertEquals("FIRST",first.normalized)
        assertEquals(0,first.revision)
        val second=vev.write(7) { Queries.saveWriteSettings(BigDecimal("42.50")," next ") }
        assertEquals(first.amount,second.oldAmount)
        assertEquals(BigDecimal("42.50"),second.amount)
        assertEquals("NEXT",second.normalized)
        assertEquals(1,second.revision)
        assertEquals(second.updatedAt,vev.read(7) { Queries.writeSettings()!!.updatedAt })
    }
    @Test fun `nullable values can be cleared without deleting a settings row`() {
        vev.write(7) { Queries.saveWriteSettings(BigDecimal.ONE,"one") }
        val cleared=vev.write(7) { Queries.saveWriteSettings(null,"cleared") }
        assertEquals(BigDecimal("1.00"),cleared.oldAmount)
        assertNull(cleared.amount)
        assertNotNull(vev.read(7) { Queries.writeSettings() })
    }
    @Test fun `tenant-keyed upserts updates and deletes cannot alter another tenant`() {
        vev.write(7) { Queries.saveWriteSettings(BigDecimal.ONE,"seven") }
        vev.write(8) { Queries.saveWriteSettings(BigDecimal.TEN,"eight") }
        vev.write(7) { Queries.renameWriteSettings("renamed",0);Queries.deleteWriteSettings() }
        assertNull(vev.read(7) { Queries.writeSettings() })
        assertEquals("eight",vev.read(8) { Queries.writeSettings()!!.label })
        assertNull(vev.write(9) { Queries.renameWriteSettings("absent",0) })
        assertNull(vev.write(9) { Queries.deleteWriteSettings() })
    }
    @Test fun `optimistic update returns previous state and rejects stale revisions`() {
        vev.write(7) { Queries.saveWriteSettings(BigDecimal.ONE,"original") }
        val changed=vev.write(7) { Queries.renameWriteSettings("changed",0) }!!
        assertEquals("original",changed.oldLabel)
        assertEquals("changed",changed.label)
        assertEquals(1,changed.revision)
        vev.write(7) {
            assertNull(Queries.renameWriteSettings("stale",0))
            assertEquals("changed",Queries.writeSettings()!!.label)
        }
    }
    @Test fun `conditional upsert distinguishes zero returned rows from a nullable value`() {
        vev.write(7) {
            assertNotNull(Queries.conditionalWriteSettings(null,0))
            assertNull(Queries.conditionalWriteSettings(BigDecimal.TEN,9))
            assertNull(Queries.writeSettings()!!.amount)
            assertNotNull(Queries.conditionalWriteSettings(BigDecimal.ONE,0))
        }
    }
    @Test fun `delete returning exposes old state and null new state`() {
        vev.write(7) { Queries.saveWriteSettings(BigDecimal.ONE,"delete") }
        val deleted=vev.write(7) { Queries.deleteWriteSettings() }!!
        assertEquals(BigDecimal("1.00"),deleted.amount)
        assertEquals("delete",deleted.label)
        assertNull(deleted.newAmount)
        assertNull(vev.read(7) { Queries.writeSettings() })
    }
    @Test fun `application failure rolls back an upsert and subsequent update together`() {
        vev.write(7) { Queries.saveWriteSettings(BigDecimal.ONE,"original") }
        assertThrows(IllegalArgumentException::class.java) {
            vev.write(7) { Queries.saveWriteSettings(BigDecimal.TEN,"new");Queries.renameWriteSettings("last",1);throw IllegalArgumentException("abort") }
        }
        val row=vev.read(7) { Queries.writeSettings() }!!
        assertEquals("original",row.label)
        assertEquals(0,row.revision)
    }
    @Test fun `caught returning decode failures poison and roll back successful mutations`() {
        assertThrows(IllegalStateException::class.java) {
            vev.write(7) { try { Queries.writeDecodeFailure() } catch (_: IllegalArgumentException) { } }
        }
        assertNull(vev.read(7) { Queries.writeSettings() })
    }
    @Test fun `constraint failures preserve SQLSTATE and roll back previous writes`() {
        val failure=assertThrows(SQLException::class.java) {
            vev.write(7) { Queries.saveWriteSettings(BigDecimal.ONE,"first");Queries.saveWriteSettings(BigDecimal.ZERO,"invalid") }
        }
        assertEquals("23514",failure.sqlState)
        assertNull(vev.read(7) { Queries.writeSettings() })
    }
    @Test fun `read only scopes reject upsert update and delete even for missing rows`() {
        for (action in listOf<()->Any?>({ Queries.saveWriteSettings(null,"rejected") },{ Queries.renameWriteSettings("rejected",0) },{ Queries.deleteWriteSettings() })) {
            assertEquals("25006",assertThrows(SQLException::class.java) { vev.read(7) { action() } }.sqlState)
        }
    }
    @Test fun `affected row counts preserve empty arrays exact matches and tenant predicates`() {
        val ids=vev.write(7) { (1..3).map { Queries.createCustomer("customer $it").id!!.value } }
        val other=vev.write(8) { Queries.createCustomer("other").id!!.value }
        vev.write(7) {
            assertEquals(0L,Queries.renameCustomers("empty",emptyList()))
            assertEquals(0L,Queries.renameCustomers("other",listOf(other)))
            assertEquals(3L,Queries.renameCustomers("renamed",ids+other))
        }
        assertEquals("other",vev.read(8) { Queries.customer(CustomerId(other))!!.name })
    }
    @Test fun `simultaneous first upserts never lose an update or create duplicate tenant rows`() {
        val barrier=CyclicBarrier(12)
        Executors.newVirtualThreadPerTaskExecutor().use { executor ->
            val futures=(0 until 12).map { n -> executor.submit {
                barrier.await(10,TimeUnit.SECONDS)
                vev.write(7) { Queries.saveWriteSettings(BigDecimal(n+1),"writer $n") }
            } }
            futures.forEach { it.get(20,TimeUnit.SECONDS) }
        }
        assertEquals(11,vev.read(7) { Queries.writeSettings()!!.revision })
    }
    @Test fun `simultaneous optimistic updates allow exactly one winner`() {
        vev.write(7) { Queries.saveWriteSettings(null,"original") }
        val barrier=CyclicBarrier(4)
        Executors.newVirtualThreadPerTaskExecutor().use { executor ->
            val futures=(1..4).map { n -> executor.submit<Boolean> {
                barrier.await(10,TimeUnit.SECONDS)
                vev.write(7) { Queries.renameWriteSettings("writer $n",0)!=null }
            } }
            assertEquals(1,futures.count { it.get(20,TimeUnit.SECONDS) })
        }
        assertEquals(1,vev.read(7) { Queries.writeSettings()!!.revision })
    }
    @Test fun `serialization failures are propagated once and leave no partial changes`() {
        vev.write(7) { Queries.saveWriteSettings(null,"original") }
        val serial=Vev(source,Connection.TRANSACTION_SERIALIZABLE)
        val barrier=CyclicBarrier(2)
        Executors.newVirtualThreadPerTaskExecutor().use { executor ->
            val futures=(1..2).map { n -> executor.submit<String> {
                try {
                    serial.write(7) { Queries.writeSettings();barrier.await(10,TimeUnit.SECONDS);Queries.renameWriteSettings("writer $n",0) }
                    "committed"
                } catch (failure: SQLException) { failure.sqlState }
            } }
            assertEquals(listOf("40001","committed"),futures.map { it.get(20,TimeUnit.SECONDS) }.sorted())
        }
        assertEquals(1,vev.read(7) { Queries.writeSettings()!!.revision })
    }
    @Test fun `lock timeout rolls back the waiting writer without disturbing the lock owner`() {
        val id=vev.write(7) { Queries.createCustomer("original").id!!.value }
        vev.write(7) {
            Queries.renameCustomers("owner",listOf(id))
            Executors.newVirtualThreadPerTaskExecutor().use { executor ->
                val state=executor.submit<String> {
                    try { vev.write(7) { s -> s.execute("SET LOCAL lock_timeout='50ms'",{});Queries.renameCustomers("waiter",listOf(id)) };"unexpected" }
                    catch (failure: SQLException) { failure.sqlState }
                }.get(10,TimeUnit.SECONDS)
                assertEquals("55P03",state)
            }
        }
        assertEquals("owner",vev.read(7) { Queries.customer(CustomerId(id))!!.name })
    }
    @Test fun `deadlock victim rolls back its first write and is not automatically retried`() {
        val ids=vev.write(7) { listOf(Queries.createCustomer("one").id!!.value,Queries.createCustomer("two").id!!.value) }
        val barrier=CyclicBarrier(2)
        Executors.newVirtualThreadPerTaskExecutor().use { executor ->
            val futures=(0..1).map { n -> executor.submit<Pair<Int,String>> {
                try {
                    vev.write(7) { s ->
                        s.execute("SET LOCAL deadlock_timeout='50ms'",{})
                        Queries.renameCustomers("$n first",listOf(ids[n]))
                        barrier.await(10,TimeUnit.SECONDS)
                        Queries.renameCustomers("$n second",listOf(ids[1-n]))
                    };n to "committed"
                } catch (failure: SQLException) { n to failure.sqlState }
            } }
            val outcomes=futures.map { it.get(20,TimeUnit.SECONDS) }
            assertEquals(listOf("40P01","committed"),outcomes.map { it.second }.sorted())
            val winner=outcomes.single { it.second=="committed" }.first
            assertTrue(vev.read(7) { Queries.customers(null).all { it.name!!.startsWith("$winner ") } })
        }
    }
    @Test fun `borrowed caller can recover at a savepoint only by starting a new scope`() {
        postgres.connect().use { connection ->
            connection.autoCommit=false
            Session.borrow(connection,7) { Queries.saveWriteSettings(BigDecimal.ONE,"before") }
            val point=connection.setSavepoint()
            assertThrows(IllegalStateException::class.java) {
                Session.borrow(connection,7) {
                    try { Queries.saveWriteSettings(BigDecimal.ZERO,"invalid") } catch (_: SQLException) { connection.rollback(point) }
                    Queries.writeSettings()
                }
            }
            Session.borrow(connection,7) { Queries.renameWriteSettings("recovered",0) }
            connection.commit()
        }
        assertEquals("recovered",vev.read(7) { Queries.writeSettings()!!.label })
    }
    @Test fun `global skip-locked claims hand distinct rows to concurrent workers and transition enum status`() {
        val first=vev.write(7) { Queries.createDocument(DocumentStatus.DRAFT,null,"a@example.test",null,null).id }
        val second=vev.write(8) { Queries.createDocument(DocumentStatus.DRAFT,null,"b@example.test",null,null).id }
        val barrier=CyclicBarrier(2)
        val targets=listOf(DocumentStatus.SENT,DocumentStatus.PAID)
        Executors.newVirtualThreadPerTaskExecutor().use { executor ->
            val futures=(0..1).map { worker -> executor.submit<Pair<Int,DocumentStatus>> {
                vev.writeGlobal {
                    val claimed=Queries.claimNextDocument()!!
                    assertEquals(DocumentStatus.DRAFT,claimed.status)
                    barrier.await(10,TimeUnit.SECONDS)
                    assertEquals(1L,Queries.transitionDocumentStatus(targets[worker],claimed.id))
                    claimed.id to targets[worker]
                }
            } }
            val results=futures.map { it.get(20,TimeUnit.SECONDS) }.toMap()
            assertEquals(setOf(first,second),results.keys)
            assertNull(vev.writeGlobal { Queries.claimNextDocument() })
            val firstRow=vev.read(7) { Queries.document(first)!! }
            val secondRow=vev.read(8) { Queries.document(second)!! }
            assertEquals(results[first],firstRow.status)
            assertEquals(if (results[first]==DocumentStatus.PAID) ReviewState.APPROVED else null,firstRow.review)
            assertEquals(results[second],secondRow.status)
            assertEquals(if (results[second]==DocumentStatus.PAID) ReviewState.APPROVED else null,secondRow.review)
        }
    }
}
