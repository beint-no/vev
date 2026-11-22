package no.beint.vev.benchmark;

import jakarta.persistence.*;
import no.beint.vev.Session;
import no.beint.vev.reai.fixture.Queries;
import org.hibernate.SessionFactory;
import org.hibernate.annotations.TenantId;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.openjdk.jmh.annotations.*;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/** The former ReAI find-then-save entity lifecycle versus atomic SQL on the same real table. */
@State(Scope.Thread) @BenchmarkMode(Mode.AverageTime) @OutputTimeUnit(TimeUnit.MICROSECONDS)
public class SettingsWriteBenchmark {
    @Param({"insert","update","unchanged"}) public String operation;
    @Param({"null","12.50"}) public String value;
    @Entity(name="WriteSettingsEntity") @Table(name="project_settings")
    public static class Settings {
        @Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Integer id;
        @TenantId @Column(name="tenant_id",nullable=false,updatable=false) public Integer tenantId;
        @Column(name="default_hourly_rate",precision=10,scale=2) public BigDecimal rate;
        public Settings() { }
    }
    private ReaiFixture fixture;
    private StandardServiceRegistry registry;
    private SessionFactory factory;
    private BigDecimal amount;
    private int tenant;
    private String sql;
    private boolean verifying;
    @Setup(Level.Trial) public void setup() throws Exception {
        fixture=new ReaiFixture();
        tenant=operation.equals("insert")?2:1;
        amount=value.equals("null")?null:new BigDecimal(value);
        sql=ReaiFixture.sql("saveDefaultHourlyRate");
        try(var statement=fixture.connection.prepareStatement("INSERT INTO project_settings(tenant_id,default_hourly_rate) VALUES(1,?)")) {
            statement.setBigDecimal(1,operation.equals("unchanged")?amount:new BigDecimal("10.00"));statement.executeUpdate();
        }
        fixture.connection.commit();
        registry=new StandardServiceRegistryBuilder()
                .applySetting("hibernate.dialect",new org.hibernate.dialect.PostgreSQLDialect(org.hibernate.dialect.DatabaseVersion.make(19,0)))
                .applySetting("hibernate.boot.allow_jdbc_metadata_access",false)
                .applySetting("hibernate.connection.provider_disables_autocommit",true)
                .applySetting("hibernate.tenant_identifier_resolver",new org.hibernate.context.spi.CurrentTenantIdentifierResolver<Integer>() {
                    public Integer resolveCurrentTenantIdentifier(){return tenant;}
                    public boolean validateExistingCurrentSessions(){return false;}
                }).build();
        factory=new MetadataSources(registry).addAnnotatedClass(Settings.class).buildMetadata().buildSessionFactory();
        verifying=true;
        try {
            if(!Objects.equals(amount,generatedSettingsSave()) || !Objects.equals(amount,jdbcSettingsSave())
                    || !Objects.equals(amount,hibernateSettingsSave()))throw new IllegalStateException("Write result parity failed");
        }finally {verifying=false;}
    }
    private void checkStored() throws java.sql.SQLException {
        if(!verifying)return;
        try(var statement=fixture.connection.prepareStatement("SELECT default_hourly_rate FROM project_settings WHERE tenant_id=?")) {
            statement.setInt(1,tenant);
            try(var rows=statement.executeQuery()) {
                if(!rows.next() || !Objects.equals(amount,rows.getBigDecimal(1)) || rows.next())throw new IllegalStateException("Stored write state parity failed");
            }
        }
    }
    @Benchmark public BigDecimal generatedSettingsSave() throws Exception {
        try {
            var result=Session.borrowTenant(fixture.connection, tenant,s->Queries.saveDefaultHourlyRate(amount)).getDefaultHourlyRate();
            checkStored();return result;
        }finally {fixture.connection.rollback();}
    }
    @Benchmark public BigDecimal jdbcSettingsSave() throws Exception {
        try(var statement=fixture.connection.prepareStatement(sql)) {
            statement.setInt(1,tenant);statement.setObject(2,amount,java.sql.Types.NUMERIC);
            try(var rows=statement.executeQuery()) {
                if(!rows.next())throw new IllegalStateException("Expected one settings row");
                var result=rows.getBigDecimal(1);
                if(rows.next())throw new IllegalStateException("Multiple settings rows");
                checkStored();return result;
            }
        }finally {fixture.connection.rollback();}
    }
    @Benchmark public BigDecimal hibernateSettingsSave() throws Exception {
        try(var session=factory.withOptions().connection(fixture.connection).tenantIdentifier(tenant).openSession()) {
            var transaction=session.beginTransaction();
            try {
                var settings=session.createSelectionQuery("from WriteSettingsEntity",Settings.class).getSingleResultOrNull();
                if(settings==null) {settings=new Settings();settings.rate=amount;session.persist(settings);}
                else {settings.rate=amount;settings=session.merge(settings);}
                session.flush();checkStored();return settings.rate;
            }finally {if(transaction.isActive())transaction.rollback();}
        }
    }
    @TearDown(Level.Trial) public void close() throws Exception {
        try {if(factory!=null)factory.close();}
        finally {try {if(registry!=null)StandardServiceRegistryBuilder.destroy(registry);}finally {if(fixture!=null)fixture.close();}}
    }
}
