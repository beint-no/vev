package no.beint.vev.it;

import jakarta.persistence.*;
import no.beint.vev.TenantKey;
import no.beint.vev.AppendOnly;
import no.beint.vev.VevIndex;
import no.beint.vev.VevPrimaryKey;
import no.beint.vev.VevReference;

@Entity
@VevPrimaryKey(VevPrimaryKey.Shape.ID)
@AppendOnly
@Table(name = "identity_event", schema = "vev_it")
public record IdentityEvent(
        @VevIndex(name = "identity_event_tenant_id_idx")
        @Id @GeneratedValue(strategy = GenerationType.IDENTITY) @Column(name = "id", nullable = false) short id,
        @TenantKey @Column(name = "tenant_id", nullable = false) int tenantId,
        @Column(name = "message", nullable = true, length = 64) String message,
        @VevReference(name = "identity_event_entry_fk", target = IdentityEntry.class, tenantFirst = false)
        @Column(name = "entry_id", nullable = true) Long entryId) {
}
