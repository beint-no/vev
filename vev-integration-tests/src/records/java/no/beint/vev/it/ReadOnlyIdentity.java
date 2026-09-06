package no.beint.vev.it;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import no.beint.vev.TenantKey;
import no.beint.vev.VevIndex;
import no.beint.vev.VevReadOnly;

@Entity
@VevReadOnly
@Table(name = "readonly_identity", schema = "vev_it")
public record ReadOnlyIdentity(
        @Id @GeneratedValue(strategy = GenerationType.IDENTITY) @Column(name = "id", nullable = false) Short id,
        @TenantKey @Column(name = "tenant_id", nullable = false) Integer tenantId,
        @Version @Column(name = "version", nullable = false) Long version,
        @VevIndex(name = "readonly_identity_label_idx") @Column(name = "label", nullable = true, length = 64) String label) {
}
