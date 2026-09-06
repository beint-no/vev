package no.beint.vev.it;

import jakarta.persistence.*;
import no.beint.vev.TenantKey;
import no.beint.vev.AppendOnly;

@Entity
@AppendOnly
@Table(name = "identity_event", schema = "vev_it")
public record IdentityEvent(
        @Id @GeneratedValue(strategy = GenerationType.IDENTITY) @Column(name = "id", nullable = false) short id,
        @TenantKey @Column(name = "tenant_id", nullable = false) int tenantId,
        @Column(name = "message", nullable = true, length = 64) String message) {
}
