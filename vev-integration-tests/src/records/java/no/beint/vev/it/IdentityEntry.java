package no.beint.vev.it;

import jakarta.persistence.*;
import no.beint.vev.TenantKey;
import no.beint.vev.VevReference;
import java.util.UUID;

@Entity
@Table(name = "identity_entry", schema = "vev_it", uniqueConstraints =
        @UniqueConstraint(name = "identity_entry_code_key", columnNames = {"tenant_id", "code"}))
public record IdentityEntry(
        @Id @GeneratedValue(strategy = GenerationType.IDENTITY) @Column(name = "id", nullable = false) Long id,
        @TenantKey @Column(name = "tenant_id", nullable = false) Integer tenantId,
        @Version @Column(name = "version", nullable = false) Short version,
        @Column(name = "label", nullable = false, length = 64) String label,
        @Column(name = "code", nullable = true, length = 64) String code,
        @VevReference(name = "identity_entry_account_fk", target = Account.class)
        @Column(name = "account_id", nullable = true) UUID accountId) {
}
