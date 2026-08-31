package no.beint.vev.it;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import no.beint.vev.TenantKey;
import no.beint.vev.VevIndex;

import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "account", schema = "vev_it")
public record Account(
        @Id @Column(name = "id", nullable = false) UUID id,
        @TenantKey @Column(name = "tenant_id", nullable = false) Integer tenantId,
        @Version @Column(name = "version", nullable = false) Long version,
        @VevIndex(name = "account_email_vev_idx")
        @Column(name = "email", nullable = true, length = 255) String email,
        @Column(name = "balance", nullable = false, precision = 19, scale = 4) BigDecimal balance) {
}
