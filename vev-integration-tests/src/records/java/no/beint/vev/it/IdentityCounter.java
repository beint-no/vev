package no.beint.vev.it;

import jakarta.persistence.*;
import no.beint.vev.TenantKey;
import no.beint.vev.VevIndex;
import no.beint.vev.VevPrimaryKey;

@Entity
@VevPrimaryKey(VevPrimaryKey.Shape.ID_TENANT)
@Table(name = "identity_counter", schema = "vev_it")
public record IdentityCounter(
        @VevIndex(name = "identity_counter_tenant_id_idx")
        @Id @GeneratedValue(strategy = GenerationType.IDENTITY) @Column(name = "id", nullable = false) int id,
        @TenantKey @Column(name = "tenant_id", nullable = false) int tenantId,
        @Version @Column(name = "version", nullable = false) int version) {
}
