package no.beint.vev.reai

import no.beint.vev.Session
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

    @Test fun `counterparty trial reports preserve unassigned revenue inclusive dates and signed totals`() {
        val start = LocalDate.of(2026, 1, 1)
        val end = LocalDate.of(2026, 1, 31)
        for (tenant in 1..3) vev.read(tenant) {
            val totals = Queries.reportTotals(start, end, listOf("3000"), false)
            assertEquals(1, totals.size)
            assertNull(totals.single().partyId)
            assertEquals("", totals.single().name)
            assertEquals(BigDecimal("-45150.00"), totals.single().amount)
            val postings = Queries.reportPostings(start, end, listOf("3000"), false, -1)
            assertEquals(300, postings.size)
            assertEquals(totals.single().amount, postings.sumOf { it.amount })
            val day = LocalDate.of(2026, 1, 2)
            val daily = Queries.reportPostings(day, day, listOf("3000"), false, -1)
            assertTrue(daily.isNotEmpty())
            assertTrue(daily.all { it.postingDate == day })
            assertTrue(Queries.reportTotals(start, end, emptyList(), false).isEmpty())
            assertTrue(Queries.reportTotals(start, end, listOf("3000"), true).isEmpty())
            assertTrue(Queries.reportSupplierCosts(start, end, listOf("3000"), true, null).isEmpty())
            assertTrue(Queries.reportSupplierCosts(start, end, listOf("3000"), true, 1).isEmpty())
            assertTrue(Queries.reportPostings(start, end, listOf("3000"), false, 1).isEmpty())
        }
    }

    @Test fun `supplier trial handles balanced rows invoice fallback credits filters and tenant overlap`() {
        postgres.connect().use { connection ->
            connection.autoCommit = false
            try {
                connection.createStatement().use { it.execute("""
                    INSERT INTO persons(id,tenant_id,name) VALUES
                        (901,1,'Synthetic Supplier A'),(902,1,'Synthetic Supplier B'),(901,2,'Other tenant');
                    INSERT INTO suppliers(id,tenant_id,person_id) VALUES (901,1,901),(902,1,902),(901,2,901);
                    UPDATE postings SET row_number=1 WHERE tenant_id=1 AND voucher_id IN (1,2,7);
                    UPDATE postings SET account_number='2400',supplier_id=901 WHERE tenant_id=1 AND account_number='1920' AND voucher_id IN (1,2,4,7);
                    UPDATE postings SET posting_date=posting_date+1 WHERE tenant_id=1 AND account_number='2400' AND voucher_id=2;
                    UPDATE vouchers SET voucher_type='SUPPLIER_INVOICE' WHERE tenant_id=1 AND id IN (4,8);
                    UPDATE postings SET account_number='2400',supplier_id=902 WHERE tenant_id=1 AND account_number='1920' AND voucher_id IN (5,8);
                    UPDATE postings SET amount=-amount,currency_amount=-currency_amount WHERE tenant_id=1 AND voucher_id=7;
                    UPDATE postings SET account_number='4000' WHERE account_number='3000';
                """) }
                val start = LocalDate.of(2026, 1, 1)
                val end = LocalDate.of(2026, 1, 31)
                Session.borrow(connection, 1) {
                    val totals = Queries.reportTotals(start, end, listOf("4000"), true).associateBy { it.partyId }
                    assertEquals(setOf(901, 902), totals.keys)
                    assertEquals(BigDecimal("-2.00"), totals.getValue(901).amount)
                    assertEquals("Synthetic Supplier A", totals.getValue(901).name)
                    assertEquals(BigDecimal("8.00"), totals.getValue(902).amount)
                    val rows = Queries.reportPostings(start, end, listOf("4000"), true, 901)
                    assertEquals(setOf(1L, 4L, 7L), rows.map { it.voucherId }.toSet())
                    assertEquals(BigDecimal("-2.00"), rows.sumOf { it.amount })
                    val costs = Queries.reportSupplierCosts(start, end, listOf("4000"), true, null)
                    assertEquals(2, costs.size)
                    assertEquals(setOf("4000"), costs.map { it.accountNumber }.toSet())
                    val filtered = Queries.reportSupplierCosts(start, end, listOf("4000"), true, 901)
                    assertEquals(1, filtered.size)
                    assertEquals(BigDecimal("-2.00"), filtered.single().amount)
                    assertTrue(Queries.reportSupplierCosts(start, end, emptyList(), true, 901).isEmpty())
                }
                Session.borrow(connection, 2) {
                    assertTrue(Queries.reportTotals(start, end, listOf("4000"), true).isEmpty())
                    assertTrue(Queries.reportPostings(start, end, listOf("4000"), true, 901).isEmpty())
                }
            } finally { connection.rollback() }
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
