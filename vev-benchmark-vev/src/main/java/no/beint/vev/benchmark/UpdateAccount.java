package no.beint.vev.benchmark;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import no.beint.vev.TenantKey;

import java.math.BigDecimal;

@Entity
@Table(name = "update_account", schema = "vev_bench")
public record UpdateAccount(
        @Id @Column(name = "id", nullable = false) Long id,
        @TenantKey @Column(name = "tenant_id", nullable = false) Integer tenantId,
        @Version @Column(name = "version", nullable = false) Long version,
        @Column(name = "balance", nullable = false, precision = 19, scale = 4) BigDecimal balance) {
}
