package no.beint.vev.it;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import no.beint.vev.AppendOnly;
import no.beint.vev.TenantKey;
import no.beint.vev.VevReference;

@Entity
@AppendOnly
@Table(name = "catalog_selection", schema = "vev_it")
public record CatalogSelection(
        @Id @Column(name = "id", nullable = false) java.util.UUID id,
        @TenantKey @Column(name = "tenant_id", nullable = false) Integer tenantId,
        @VevReference(name = "catalog_selection_catalog_fk", target = SharedCatalog.class)
        @Column(name = "catalog_id", nullable = true) Integer catalogId) {
}
