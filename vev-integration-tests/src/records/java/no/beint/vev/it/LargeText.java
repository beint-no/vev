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
@Table(name = "large_text", schema = "vev_it")
@VevRows(8)
public record LargeText(
        @Id @Column(name = "id", nullable = false) Integer id,
        @TenantKey @Column(name = "tenant_id", nullable = false) Integer tenantId,
        @Version @Column(name = "version", nullable = false) Integer version,
        @VevIndex(name = "large_text_category_idx")
        @Column(name = "category", nullable = true, length = 8) String category,
        @Column(name = "body", nullable = false, length = 65535) String body) {
}
