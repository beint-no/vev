package no.beint.vev.reai

import no.beint.vev.Vev
import no.beint.vev.compiler.Postgres
import no.beint.vev.reai.fixture.Queries
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.Assertions.*
import org.postgresql.ds.PGSimpleDataSource
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.sql.SQLException
import java.time.LocalDate
import java.time.OffsetDateTime

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReaiExecutionTest {
    private lateinit var postgres: Postgres
    private lateinit var vev: Vev
    @BeforeAll fun start() {
        postgres=Postgres("")
        postgres.migrate(Path.of("../test-fixtures/reai/migration"))
        postgres.connect().use { c ->
            c.autoCommit=false
            c.createStatement().use { it.execute(Files.readString(Path.of("../test-fixtures/reai/seed.sql"))) }
            c.commit()
        }
        vev=Vev(PGSimpleDataSource().apply { setURL(postgres.url()); user=postgres.user(); password=postgres.password() })
    }
    @AfterAll fun stop() { if (::postgres.isInitialized) postgres.close() }

    @Test fun `real schema joins aggregates arrays windows and lateral queries execute`() {
        for (tenant in 1..3) vev.read(tenant) {
            assertEquals(20,Queries.departments().size)
            val ledger=Queries.ledger()
            assertEquals(600,ledger.size)
            assertEquals(BigDecimal("0.00"),ledger.sumOf { it.amount })
            assertEquals(300,Queries.vouchers((1L..300L).toList()).size)
            assertTrue(Queries.vouchers((1L..300L).toList()).all { it.total.compareTo(BigDecimal.ZERO)==0 })
            assertEquals(600,Queries.runningBalance().size)
            assertEquals(20,Queries.departmentTotals().size)
            assertTrue(Queries.departmentTotals().all { it.total.compareTo(BigDecimal.ZERO)==0 })
        }
    }

    @Test fun `same object IDs are isolated by tenant on read and update`() {
        vev.write(1) { assertEquals(1L,Queries.renameDepartment("Tenant One",1)) }
        assertEquals("Tenant One",vev.read(1) { Queries.department(1)!!.name })
        assertEquals("Synthetic Department 1",vev.read(2) { Queries.department(1)!!.name })
        vev.write(1) { Queries.renameDepartment("Synthetic Department 1",1) }
    }

    @Test fun `database rejects cross tenant composite foreign key`() {
        val created=OffsetDateTime.parse("2026-01-01T00:00:00Z")
        val day=LocalDate.of(2026,1,1)
        vev.write(1) { Queries.createDepartment(900,"Tenant One Only") }
        val error=assertThrows(SQLException::class.java) {
            vev.write(2) {
                Queries.createVoucher(999L,created,day,999.toShort(),"Synthetic invalid write")
                Queries.createPosting(999L,created,day,1.toShort(),"3000",BigDecimal.ONE,900)
            }
        }
        assertEquals("23503",error.sqlState)
        assertTrue(vev.read(2) { Queries.vouchers(listOf(999L)).isEmpty() })
        vev.write(1) { Queries.deleteDepartment(900) }
    }

    @Test fun `real deferred trigger rejects voucher without postings and preserves original SQLState`() {
        val error=assertThrows(SQLException::class.java) {
            vev.write(1) { Queries.createVoucher(998L,OffsetDateTime.parse("2026-01-01T00:00:00Z"),LocalDate.of(2026,1,1),998.toShort(),"No postings") }
        }
        assertNotNull(error.sqlState)
        assertTrue(vev.read(1) { Queries.vouchers(listOf(998L)).isEmpty() })
    }

    @Test fun `tenant cascade runs with populated real foreign keys and deferred triggers`() {
        vev.write(3) { Queries.deleteTenant() }
        assertTrue(vev.read(3) { Queries.ledger().isEmpty() })
        assertEquals(600,vev.read(2) { Queries.ledger().size })
        // Restore this tenant so tests are independent of method order.
        postgres.connect().use { c ->
            c.autoCommit=false
            val seed=Files.readString(Path.of("../test-fixtures/reai/seed.sql"))
                .replace("generate_series(1,3)","generate_series(3,3)")
            c.createStatement().use { it.execute(seed) };c.commit()
        }
    }
    @Test fun `PostgreSQL MERGE RETURNING executes both branches and keeps tenants isolated`() {
        assertEquals("inserted",vev.write(1) { Queries.mergeDepartment(901,"inserted").name })
        assertEquals("updated",vev.write(1) { Queries.mergeDepartment(901,"updated").name })
        assertNull(vev.read(2) { Queries.department(901) })
        vev.write(1) { Queries.deleteDepartment(901) }
    }
    @Test fun `paged real ledger reads retain tenant isolation at different result sizes`() {
        for (limit in listOf(0,1,100,600,1000)) {
            val first=vev.read(1) { Queries.pagedLedger(limit) }
            val second=vev.read(2) { Queries.pagedLedger(limit) }
            assertEquals(minOf(limit,600),first.size)
            assertEquals(minOf(limit,600),second.size)
            assertTrue(first.map { it.id }.toSet().intersect(second.map { it.id }.toSet()).isEmpty())
        }
    }

    @Test fun `set based array writes retain affected counts tenant scope and atomic failures`() {
        val ids=(950..981).toList()
        assertEquals(32L,vev.write(1) { Queries.bulkDepartments(ids) })
        assertEquals(52,vev.read(1) { Queries.departments().size })
        assertEquals(20,vev.read(2) { Queries.departments().size })
        vev.write(1) { for (id in ids) Queries.deleteDepartment(id) }
        val error=assertThrows(SQLException::class.java) { vev.write(1) { Queries.bulkDepartments(listOf(950,null)) } }
        assertEquals("23502",error.sqlState)
        assertNull(vev.read(1) { Queries.department(950) })
    }
}
