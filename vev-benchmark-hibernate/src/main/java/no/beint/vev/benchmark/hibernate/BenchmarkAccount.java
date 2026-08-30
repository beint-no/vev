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

@Entity(name = "BenchmarkAccount")
@Table(name = BenchmarkDataset.TABLE_NAME, schema = BenchmarkDataset.SCHEMA_NAME)
@IdClass(BenchmarkAccountId.class)
@Access(AccessType.FIELD)
public class BenchmarkAccount {
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Id
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private Integer tenantId;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @Column(name = "email", nullable = false)
    private String email;

    @Column(name = "balance", nullable = false, precision = 19, scale = 4)
    private BigDecimal balance;

    @Column(name = "active", nullable = false)
    private Boolean active;

    protected BenchmarkAccount() {
    }

    BenchmarkAccount(
            Long id,
            Integer tenantId,
            Long version,
            String email,
            BigDecimal balance,
            Boolean active) {
        this.id = id;
        this.tenantId = tenantId;
        this.version = version;
        this.email = email;
        this.balance = balance;
        this.active = active;
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

    public String email() {
        return email;
    }

    public BigDecimal balance() {
        return balance;
    }

    public Boolean active() {
        return active;
    }

    public void changeBalance(BigDecimal newBalance) {
        balance = newBalance;
    }

    public void deactivate() {
        active = false;
    }

    long stableChecksum() {
        long checksum = id;
        checksum = mix(checksum, tenantId);
        checksum = mix(checksum, version);
        checksum = mix(checksum, email.hashCode());
        checksum = mix(checksum, balance.unscaledValue().longValueExact());
        checksum = mix(checksum, balance.scale());
        return mix(checksum, active ? 1 : 0);
    }

    private static long mix(long checksum, long value) {
        return checksum * 31 + value;
    }
}
