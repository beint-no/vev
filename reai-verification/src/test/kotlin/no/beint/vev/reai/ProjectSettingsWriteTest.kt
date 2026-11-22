package no.beint.vev.reai

import no.beint.vev.Session
import no.beint.vev.Vev
import no.beint.vev.compiler.Postgres
import no.beint.vev.reai.fixture.Queries
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.Assertions.*
import org.postgresql.ds.PGSimpleDataSource
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.sql.SQLException
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProjectSettingsWriteTest {
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
        vev=Vev(PGSimpleDataSource().apply { setURL(postgres.url());user=postgres.user();password=postgres.password() })
    }
    @AfterAll fun stop() { if (::postgres.isInitialized) postgres.close() }

    @TestFactory fun `actual settings contracts preserve nullable values identity and tenant state`() =
        (1..3).flatMap { tenant -> listOf("absent","null","populated").flatMap { initial ->
            listOf(null,"0.01","12.50","12.5000","10000000").mapIndexed { index,value ->
                DynamicTest.dynamicTest("tenant $tenant initial $initial input $index") {
                    postgres.connect().use { c ->
                        c.autoCommit=false
                        try {
                            c.createStatement().use { it.execute("DELETE FROM project_settings") }
                            c.prepareStatement("INSERT INTO project_settings(tenant_id,default_hourly_rate) VALUES(?,77.00)").use { s -> s.setInt(1,if(tenant==3)1 else tenant+1);s.executeUpdate() }
                            if(initial!="absent")c.prepareStatement("INSERT INTO project_settings(tenant_id,default_hourly_rate) VALUES(?,?)").use { s ->
                                s.setInt(1,tenant);s.setBigDecimal(2,if(initial=="null")null else BigDecimal("100.00"));s.executeUpdate()
                            }
                            val before=c.prepareStatement("SELECT id FROM project_settings WHERE tenant_id=?").use { s -> s.setInt(1,tenant);s.executeQuery().use { r -> if(r.next())r.getInt(1) else null } }
                            Session.borrowTenant(c, tenant) {
                                val previous=Queries.defaultHourlyRate()
                                if(initial=="absent")assertNull(previous) else assertNotNull(previous)
                                val expected=value?.let { BigDecimal(it).setScale(2) }
                                assertEquals(expected,Queries.saveDefaultHourlyRate(value?.let(::BigDecimal)).defaultHourlyRate)
                                assertEquals(expected,Queries.defaultHourlyRate()!!.defaultHourlyRate)
                                assertEquals(1,c.prepareStatement("SELECT count(*) FROM project_settings WHERE tenant_id=?").use { s -> s.setInt(1,tenant);s.executeQuery().use { r -> r.next();r.getInt(1) } })
                                if(before!=null)assertEquals(before,c.prepareStatement("SELECT id FROM project_settings WHERE tenant_id=?").use { s -> s.setInt(1,tenant);s.executeQuery().use { r -> r.next();r.getInt(1) } })
                                c.prepareStatement("SELECT default_hourly_rate FROM project_settings WHERE tenant_id<>?").use { s -> s.setInt(1,tenant);s.executeQuery().use { r -> assertTrue(r.next());assertEquals(BigDecimal("77.00"),r.getBigDecimal(1));assertFalse(r.next()) } }
                            }
                        }finally {c.rollback()}
                    }
                }
            }
        } }

    @TestFactory fun `real schema constraint SQLSTATE survives generated write APIs`() =
        listOf("0" to "23514","-1" to "23514","10000000.01" to "23514","100000000" to "22003").map { (value,state) ->
            DynamicTest.dynamicTest("invalid numeric $value") {
                assertEquals(state,assertThrows(SQLException::class.java) { vev.write(1) { Queries.saveDefaultHourlyRate(BigDecimal(value)) } }.sqlState)
            }
        }
    @Test fun `actual tenant foreign key rejects nonexisting tenant`() {
        assertEquals("23503",assertThrows(SQLException::class.java) { vev.write(Int.MAX_VALUE) { Queries.saveDefaultHourlyRate(BigDecimal.ONE) } }.sqlState)
    }
    @Test fun `real settings write respects read-only transactions`() {
        assertEquals("25006",assertThrows(SQLException::class.java) { vev.read(1) { Queries.saveDefaultHourlyRate(BigDecimal.ONE) } }.sqlState)
    }
    @Test fun `numeric rounding remains PostgreSQL behavior rather than application validation`() {
        postgres.connect().use { c ->
            c.autoCommit=false
            try { Session.borrowTenant(c, 1) { assertEquals(BigDecimal("1.01"),Queries.saveDefaultHourlyRate(BigDecimal("1.005")).defaultHourlyRate) } }
            finally {c.rollback()}
        }
    }
    @Test fun `concurrent first saves succeed for overlapping tenant settings`() {
        postgres.connect().use { c -> c.createStatement().use { it.execute("DELETE FROM project_settings") } }
        try {
            val barrier=CyclicBarrier(12)
            Executors.newVirtualThreadPerTaskExecutor().use { executor ->
                val futures=(0 until 12).map { n -> executor.submit {
                    barrier.await(10,TimeUnit.SECONDS)
                    vev.write(1+n%3) { Queries.saveDefaultHourlyRate(BigDecimal(n+1)) }
                } }
                futures.forEach { it.get(20,TimeUnit.SECONDS) }
            }
            for(tenant in 1..3)assertNotNull(vev.read(tenant) { Queries.defaultHourlyRate() })
            postgres.connect().use { c -> c.createStatement().use { s -> s.executeQuery("SELECT count(*) FROM project_settings").use { r -> r.next();assertEquals(3,r.getInt(1)) } } }
        }finally {postgres.connect().use { c -> c.createStatement().use { it.execute("DELETE FROM project_settings") } }}
    }
}
