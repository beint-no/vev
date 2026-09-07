package no.beint.vev.it;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import no.beint.vev.TenantKey;
import no.beint.vev.VevRows;
import no.beint.vev.VevTenantReference;

@Entity
@VevRows(8)
@Table(name = "registry_entry", schema = "vev_it")
public record RegistryEntry(
        @Id @GeneratedValue(strategy = GenerationType.IDENTITY) @Column(name = "id", nullable = false) Long id,
        @TenantKey @VevTenantReference(name = "registry_entry_tenant_fk", schema = "vev_it", table = "tenant_registry", column = "id", onDelete = VevTenantReference.OnDelete.CASCADE)
        @Column(name = "tenant_id", nullable = false) Integer tenantId,
        @Version @Column(name = "version", nullable = false) Long version,
        @Column(name = "label", nullable = true, length = 64) String label) {
}
