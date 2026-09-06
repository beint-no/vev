package no.beint.vev.it;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import no.beint.vev.TenantKey;

@Entity
@Table(name = "snapshot_probe", schema = "vev_it")
public record SnapshotProbe(
        @Id @Column(name = "id", nullable = false) long id,
        @TenantKey @Column(name = "tenant_id", nullable = false) int tenantId,
        @Version @Column(name = "version", nullable = false) long version,
        @Column(name = "value", nullable = false, length = 64) String value) {
    @Override
    public boolean equals(Object other) {
        throw new AssertionError("Persistence must not invoke entity equality");
    }

    @Override
    public int hashCode() {
        throw new AssertionError("Persistence must not hash an entity");
    }

    @Override
    public String toString() {
        throw new AssertionError("Persistence must not render an entity");
    }
}
