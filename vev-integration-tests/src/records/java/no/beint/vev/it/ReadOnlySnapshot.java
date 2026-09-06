package no.beint.vev.it;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import no.beint.vev.TenantKey;
import no.beint.vev.VevIndex;
import no.beint.vev.VevReadOnly;

@Entity
@VevReadOnly(externalIncomingReferences = true)
@Table(name = "readonly_snapshot", schema = "vev_it")
public record ReadOnlySnapshot(
        @Id @Column(name = "id", nullable = false) java.util.UUID id,
        @TenantKey @Column(name = "tenant_id", nullable = false) Integer tenantId,
        @VevIndex(name = "readonly_snapshot_label_idx") @Column(name = "label", nullable = false, length = 64) String label) {
}
