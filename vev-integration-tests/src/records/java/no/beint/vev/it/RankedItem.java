package no.beint.vev.it;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import no.beint.vev.TenantKey;
import no.beint.vev.VevIndex;
import no.beint.vev.VevRows;

@Entity
@VevRows(8)
@Table(name = "ranked_item", schema = "vev_it")
public record RankedItem(
        @Id @Column(name = "id", nullable = false) Integer id,
        @TenantKey @Column(name = "tenant_id", nullable = false) Integer tenantId,
        @Version @Column(name = "version", nullable = false) Integer version,
        @VevIndex(name = "ranked_item_category_idx", orderBy = "rank_value") @Column(name = "category", nullable = true, length = 64) String category,
        @VevIndex(name = "ranked_item_enabled_idx", orderBy = "order", direction = VevIndex.Direction.DESC) @Column(name = "enabled", nullable = false) Boolean enabled,
        @Column(name = "rank_value", nullable = false) Integer rank,
        @Column(name = "order", nullable = false, length = 64) String label) {
}
