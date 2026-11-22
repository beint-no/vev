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
import java.time.Instant

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
                Session.borrowTenant(connection, 1) {
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
                Session.borrowTenant(connection, 2) {
                    assertTrue(Queries.reportTotals(start, end, listOf("4000"), true).isEmpty())
                    assertTrue(Queries.reportPostings(start, end, listOf("4000"), true, 901).isEmpty())
                }
            } finally { connection.rollback() }
        }
    }

    @Test fun `posting group reports and voucher search execute with real tenant constraints`() {
        postgres.connect().use { connection ->
            connection.autoCommit = false
            try {
                connection.createStatement().use { it.execute("""
                    INSERT INTO persons(id,tenant_id,name) VALUES (950,1,'Synthetic party'),(950,2,'Other tenant');
                    INSERT INTO suppliers(id,tenant_id,person_id) VALUES (950,1,950),(950,2,950);
                    INSERT INTO customers(id,tenant_id,person_id) VALUES (950,1,950),(950,2,950);
                    INSERT INTO employees(id,tenant_id,person_id) VALUES (950,1,950);
                    INSERT INTO posting_groups(id,tenant_id,origin) VALUES (950,1,'LEDGER_CLOSE'),(950,2,'LEDGER_CLOSE'),(951,1,'LEDGER_CLOSE');
                    UPDATE postings SET account_number='2400',supplier_id=950 WHERE tenant_id IN (1,2) AND voucher_id IN (1,2) AND account_number='1920';
                    INSERT INTO posting_group_postings(posting_id,posting_group_id,tenant_id)
                        SELECT id,950,tenant_id FROM postings WHERE tenant_id IN (1,2) AND voucher_id IN (1,2) AND account_number='2400';
                    UPDATE postings SET account_number='2910',employee_id=950 WHERE tenant_id=1 AND voucher_id=3 AND account_number='3000';
                    UPDATE postings SET account_number='1500',customer_id=950 WHERE tenant_id=1 AND voucher_id=4 AND account_number='3000';
                    INSERT INTO posting_group_postings(posting_id,posting_group_id,tenant_id)
                        SELECT id,951,tenant_id FROM postings WHERE tenant_id=1 AND voucher_id IN (3,4) AND account_number IN ('2910','1500');
                    UPDATE vouchers SET voucher_type='INCOME' WHERE tenant_id=1 AND id=4;
                """) }
                Session.borrowTenant(connection, 1) {
                    val suppliers = Queries.supplierPostingGroupCounts(listOf(950, 951), "2400")
                    assertEquals(1, suppliers.size)
                    assertEquals(2L, suppliers.single().postingCount)
                    assertEquals(950, suppliers.single().postingGroupId)
                    assertEquals(1L, Queries.employeePostingGroupCounts(listOf(950,951)).single().postingCount)
                    assertEquals(1L, Queries.customerPostingGroupCounts(listOf(950,951),"1500").single().postingCount)
                    val dates = Queries.postingSettlementDates(listOf("2400"),LocalDate.of(2026,1,2))
                    assertEquals(1, dates.size)
                    assertEquals(LocalDate.of(2026,1,3),dates.single().settlementDate)
                    assertTrue(Queries.postingSettlementDates(emptyList(),LocalDate.of(2026,1,2)).isEmpty())
                    assertTrue(Queries.supplierPostingGroupCounts(emptyList(),"2400").isEmpty())
                    val sources = Queries.voucherSearchSources(listOf(1,2,3,4))
                    assertEquals(1,sources.size)
                    assertEquals("INCOME",sources.single().kind)
                    assertEquals(4L,sources.single().voucherId)
                    assertEquals(4L,sources.single().sourceId)
                    assertNull(sources.single().number)
                    assertTrue(Queries.voucherSearchSources(emptyList()).isEmpty())
                }
                Session.borrowTenant(connection, 2) {
                    assertEquals(2L,Queries.supplierPostingGroupCounts(listOf(950),"2400").single().postingCount)
                    assertTrue(Queries.employeePostingGroupCounts(listOf(951)).isEmpty())
                    assertTrue(Queries.customerPostingGroupCounts(listOf(951),"1500").isEmpty())
                    assertTrue(Queries.voucherSearchSources(listOf(4)).isEmpty())
                }
            } finally { connection.rollback() }
        }
    }

    @Test fun `VAT and voucher projections preserve signed amounts dates nulls and attachment isolation`() {
        postgres.connect().use { connection ->
            connection.autoCommit = false
            try {
                connection.createStatement().use { it.execute("""
                    UPDATE postings SET vat_code='87' WHERE tenant_id=1 AND account_number='3000';
                    INSERT INTO persons(id,tenant_id,name) VALUES(960,1,'Synthetic supplier');
                    INSERT INTO suppliers(id,tenant_id,person_id) VALUES(960,1,960);
                    INSERT INTO supplier_invoice(id,tenant_id,date,supplier_id,number,currency)
                        VALUES(960,1,'2026-01-06',960,'SYNTHETIC-960','NOK');
                    UPDATE vouchers SET voucher_type='SUPPLIER_INVOICE' WHERE id=5 AND tenant_id=1;
                    UPDATE postings SET supplier_invoice_id=960 WHERE voucher_id=5 AND tenant_id=1 AND account_number='3000';
                    INSERT INTO attachments(id,tenant_id,file_data,filename,mimetype) VALUES
                        (960,1,'\x01','synthetic.pdf','application/pdf'),
                        (961,1,'\x02','synthetic.txt','text/plain'),
                        (962,1,'\x03','synthetic.png','image/png'),
                        (960,2,'\x04','other.pdf','application/pdf');
                    INSERT INTO voucher_attachments(id,tenant_id,voucher_id,attachment_id) VALUES
                        (960,1,1,960),(961,1,1,962),(962,1,1,961),(963,2,1,960);
                    INSERT INTO supplier_invoice_attachments(id,tenant_id,supplier_invoice_id,attachment_id) VALUES(960,1,960,960);
                """) }
                val start = LocalDate.of(2026,1,1)
                val end = LocalDate.of(2026,1,31)
                Session.borrowTenant(connection, 1) {
                    val basis = Queries.vatBasis(start,end).associate { it.vatCode to it.basisAmount }
                    assertEquals(BigDecimal("45150.00"),basis["87"])
                    assertEquals(BigDecimal("-45150.00"),basis["0"])
                    assertEquals(BigDecimal.ZERO.setScale(2),basis.values.sumOf { it })
                    val monthly = Queries.vatBasisByMonth(start,end)
                    assertEquals(basis,monthly.associate { it.vatCode to it.basisAmount })
                    assertTrue(monthly.all { it.monthNumber==1 })
                    assertEquals(listOf(LocalDate.of(2026,1,28)),Queries.reverseChargeQuarters(start,end.plusDays(1)).map { it.lastPostingDate })
                    assertTrue(Queries.reverseChargeQuarters(start,start).isEmpty())
                    val overview = Queries.voucherOverview(listOf(1,5))
                    assertEquals(4,overview.size)
                    assertEquals(listOf(5L,5L,1L,1L),overview.map { it.voucherId })
                    assertEquals(BigDecimal.ZERO.setScale(2),overview.sumOf { it.amount!! })
                    assertEquals("SYNTHETIC-960",overview.single { it.postingSupplierInvoiceId!=null }.supplierInvoiceNumber)
                    assertTrue(Queries.voucherOverview(emptyList()).isEmpty())
                    val documents = Queries.voucherDocuments(listOf(1,5)).associateBy { it.voucherId }
                    assertEquals(961,documents.getValue(1).voucherAttachmentId)
                    assertEquals(960,documents.getValue(5).supplierInvoiceId)
                    assertEquals(960,documents.getValue(5).supplierInvoiceAttachmentId)
                    assertNull(documents.getValue(1).supplierInvoiceId)
                    assertTrue(Queries.voucherDocuments(emptyList()).isEmpty())
                    assertTrue(Queries.creditNoteAppliedInvoices(emptyList()).isEmpty())
                    assertTrue(Queries.creditNoteAppliedInvoices(listOf(960)).isEmpty())
                }
                Session.borrowTenant(connection, 2) {
                    assertEquals(963,Queries.voucherDocuments(listOf(1)).single().voucherAttachmentId)
                    assertTrue(Queries.voucherDocuments(listOf(5)).all { it.supplierInvoiceId==null })
                    assertTrue(Queries.vatBasis(start,end).none { it.vatCode=="87" })
                }
            } finally { connection.rollback() }
        }
    }

    @Test fun `credit note references retain invoice order and tenant ownership`() {
        postgres.connect().use { connection ->
            connection.autoCommit=false
            try {
                connection.createStatement().use { it.execute("""
                    INSERT INTO invoices_ar(id,tenant_id,number,reference,issue_date,voucher_id) VALUES
                        (970,1,'SYNTHETIC-A','REF-A','2026-01-02',10),
                        (971,1,'SYNTHETIC-B','REF-B','2026-01-01',11),
                        (972,1,'SYNTHETIC-CREDIT','REF-CREDIT','2026-01-03',12);
                    INSERT INTO invoice_ar_credit_note_allocations(tenant_id,invoice_id,credit_note_invoice_id,allocation_type,allocated_amount)
                        VALUES(1,970,972,'MANUAL_PARTIAL_COVER',1),(1,971,972,'MANUAL_PARTIAL_COVER',1);
                """) }
                Session.borrowTenant(connection, 1) {
                    val rows=Queries.creditNoteAppliedInvoices(listOf(972))
                    assertEquals(listOf(971,970),rows.map { it.invoiceId })
                    assertEquals(listOf("REF-B","REF-A"),rows.map { it.invoiceNumber })
                    assertTrue(rows.all { it.creditNoteInvoiceId==972 })
                    assertTrue(Queries.creditNoteAppliedInvoices(listOf(970)).isEmpty())
                }
                Session.borrowTenant(connection, 2) { assertTrue(Queries.creditNoteAppliedInvoices(listOf(972)).isEmpty()) }
            } finally { connection.rollback() }
        }
    }

    @Test fun `voucher overview uses registry company names when local overrides are absent`() {
        postgres.connect().use { connection ->
            connection.autoCommit=false
            try {
                connection.createStatement().use { it.execute("""
                    INSERT INTO brreg_legal_forms(id,code) VALUES(960,'AS');
                    INSERT INTO brreg_companies_compact(org_number,legal_form_id,legal_form_description_missing,name)
                        VALUES(123456789,960,false,'Synthetic registry name');
                    INSERT INTO companies(id,tenant_id,created_at,updated_at,country_code,organization_number,registry_org_number)
                        VALUES(961,1,'2026-01-01Z','2026-01-01Z','NO','123456789',123456789);
                    INSERT INTO suppliers(id,tenant_id,company_id) VALUES(961,1,961);
                    INSERT INTO customers(id,tenant_id,company_id) VALUES(961,1,961);
                    UPDATE postings SET account_number='1500',customer_id=961 WHERE tenant_id=1 AND voucher_id=1 AND account_number='3000';
                    UPDATE postings SET account_number='2400',supplier_id=961 WHERE tenant_id=1 AND voucher_id=2 AND account_number='1920';
                """) }
                Session.borrowTenant(connection, 1) {
                    val rows=Queries.voucherOverview(listOf(1,2))
                    assertEquals("Synthetic Registry Name",rows.single { it.accountNumber=="1500" }.subsidiaryLedgerName)
                    val supplier=rows.single { it.accountNumber=="2400" }
                    assertEquals("Synthetic Registry Name",supplier.subsidiaryLedgerName)
                    assertEquals("Synthetic Registry Name",supplier.supplierName)
                }
            } finally { connection.rollback() }
        }
    }

    @Test fun `company detail views preserve tenant isolation when company IDs overlap`() {
        postgres.connect().use { connection ->
            connection.autoCommit=false
            try {
                connection.createStatement().use { it.execute("""
                    INSERT INTO companies(id,tenant_id,created_at,updated_at,name,country_code,company_type_code)
                    VALUES (990,1,'2026-01-01Z','2026-01-01Z','Synthetic Alpha','SE','AB'),
                           (990,2,'2026-01-01Z','2026-01-01Z','Synthetic Other Tenant','SE','AB');
                    INSERT INTO suppliers(id,tenant_id,company_id) VALUES(990,1,990);
                    INSERT INTO customers(id,tenant_id,company_id) VALUES(990,1,990);
                    UPDATE postings SET account_number='1500',customer_id=990 WHERE tenant_id=1 AND voucher_id=1 AND account_number='1920';
                    UPDATE postings SET account_number='2400',supplier_id=990 WHERE tenant_id=1 AND voucher_id=2 AND account_number='1920';
                    UPDATE vouchers SET voucher_type='CUSTOMER_INVOICE' WHERE tenant_id=1 AND id=1;
                    UPDATE vouchers SET voucher_type='SUPPLIER_INVOICE' WHERE tenant_id=1 AND id=2;
                """) }
                Session.borrowTenant(connection, 1) {
                    val start=LocalDate.of(2026,1,1)
                    val end=LocalDate.of(2026,1,31)
                    val totals=Queries.reportTotals(start,end,listOf("3000"),false).filter { it.partyId==990 }
                    assertEquals(1,totals.size)
                    assertEquals("Synthetic Alpha",totals.single().name)
                    assertEquals(BigDecimal("-1.00"),totals.single().amount)
                    val postings=Queries.reportPostings(start,end,listOf("3000"),false,990)
                    assertEquals(1,postings.size)
                    assertEquals("Synthetic Alpha",postings.single().name)
                    val costs=Queries.reportSupplierCosts(start,end,listOf("3000"),true,990)
                    assertEquals(1,costs.size)
                    assertEquals("Synthetic Alpha",costs.single().supplierName)
                    assertEquals(BigDecimal("2.00"),costs.single().amount)
                    val overview=Queries.voucherOverview(listOf(1,2))
                    assertEquals(4,overview.size)
                    assertTrue(overview.filter { it.subsidiaryLedgerName!=null }.all { it.subsidiaryLedgerName=="Synthetic Alpha" })
                    assertTrue(overview.filter { it.supplierName!=null }.all { it.supplierName=="Synthetic Alpha" })
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
        val created=Instant.parse("2026-01-01T00:00:00Z")
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
            vev.write(1) { Queries.createVoucher(998L,Instant.parse("2026-01-01T00:00:00Z"),LocalDate.of(2026,1,1),998.toShort(),"No postings") }
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
