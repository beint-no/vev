package no.beint.vev.it;

import jakarta.persistence.*;
import no.beint.vev.TenantKey;

@Entity
@Table(name = "identity_counter", schema = "vev_it")
public record IdentityCounter(
        @Id @GeneratedValue(strategy = GenerationType.IDENTITY) @Column(name = "id", nullable = false) int id,
        @TenantKey @Column(name = "tenant_id", nullable = false) int tenantId,
        @Version @Column(name = "version", nullable = false) int version) {
}
