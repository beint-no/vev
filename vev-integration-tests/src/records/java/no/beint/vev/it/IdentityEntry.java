package no.beint.vev.it;

import jakarta.persistence.*;
import no.beint.vev.TenantKey;
import no.beint.vev.VevReference;
import no.beint.vev.VevIndex;
import no.beint.vev.VevPrimaryKey;
import java.util.UUID;

@Entity
@VevPrimaryKey(VevPrimaryKey.Shape.ID)
@Table(name = "identity_entry", schema = "vev_it", uniqueConstraints = {
        @UniqueConstraint(name = "identity_entry_code_key", columnNames = {"tenant_id", "code"}),
        @UniqueConstraint(name = "identity_entry_id_tenant_key", columnNames = {"id", "tenant_id"})})
public record IdentityEntry(
        @VevIndex(name = "identity_entry_tenant_id_idx")
        @Id @GeneratedValue(strategy = GenerationType.IDENTITY) @Column(name = "id", nullable = false) Long id,
        @TenantKey @Column(name = "tenant_id", nullable = false) Integer tenantId,
        @Version @Column(name = "version", nullable = false) Short version,
        @Column(name = "label", nullable = false, length = 64) String label,
        @Column(name = "code", nullable = true, length = 64) String code,
        @VevReference(name = "identity_entry_account_fk", target = Account.class, tenantFirst = false)
        @Column(name = "account_id", nullable = true) UUID accountId) {
}
