package no.beint.vev.benchmark.hibernate;

import jakarta.persistence.Access;
import jakarta.persistence.AccessType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;

@Entity(name = "BenchmarkUpdateAccount")
@Table(name = BenchmarkDataset.UPDATE_TABLE_NAME, schema = BenchmarkDataset.SCHEMA_NAME)
@IdClass(BenchmarkAccountId.class)
@Access(AccessType.FIELD)
public class BenchmarkUpdateAccount {
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Id
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private Integer tenantId;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @Column(name = "balance", nullable = false, precision = 19, scale = 4)
    private BigDecimal balance;

    protected BenchmarkUpdateAccount() {
    }

    BenchmarkUpdateAccount(Long id, Integer tenantId, Long version, BigDecimal balance) {
        this.id = id;
        this.tenantId = tenantId;
        this.version = version;
        this.balance = balance;
    }

    public Long id() {
        return id;
    }

    public Integer tenantId() {
        return tenantId;
    }

    public Long version() {
        return version;
    }

    public BigDecimal balance() {
        return balance;
    }
}
