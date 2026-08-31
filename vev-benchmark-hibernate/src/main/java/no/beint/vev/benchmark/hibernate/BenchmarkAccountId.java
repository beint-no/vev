package no.beint.vev.benchmark.hibernate;

import java.io.Serial;
import java.io.Serializable;
import java.util.Objects;

public final class BenchmarkAccountId implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;
    private Integer tenantId;

    public BenchmarkAccountId() {
    }

    public BenchmarkAccountId(long id, int tenantId) {
        this.id = id;
        this.tenantId = tenantId;
    }

    public Long id() {
        return id;
    }

    public Integer tenantId() {
        return tenantId;
    }

    @Override
    public boolean equals(Object other) {
        return this == other
                || other instanceof BenchmarkAccountId that
                && Objects.equals(id, that.id)
                && Objects.equals(tenantId, that.tenantId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, tenantId);
    }
}
