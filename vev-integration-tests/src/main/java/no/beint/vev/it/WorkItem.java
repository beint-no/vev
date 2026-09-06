package no.beint.vev.it;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Enumerated;
import jakarta.persistence.EnumType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import no.beint.vev.TenantKey;
import no.beint.vev.VevIndex;
import no.beint.vev.VevReference;

import java.util.UUID;

@Entity
@Table(name = "work_item", schema = "vev_it", uniqueConstraints =
        @UniqueConstraint(name = "work_item_account_state_key", columnNames = {"tenant_id", "account_id", "state"}))
public record WorkItem(
        @Id @Column(name = "id", nullable = false) UUID id,
        @TenantKey @Column(name = "tenant_id", nullable = false) Integer tenantId,
        @Version @Column(name = "version", nullable = false) Long version,
        @Enumerated(EnumType.STRING)
        @VevIndex(name = "work_item_state_vev_idx")
        @Column(name = "state", nullable = true, length = 16) WorkState state,
        @VevReference(name = "work_item_account_fk", target = Account.class)
        @Column(name = "account_id", nullable = true) UUID accountId) {
}
