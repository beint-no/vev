package no.beint.vev.it;

import jakarta.persistence.*;
import no.beint.vev.Binary;
import no.beint.vev.TenantKey;
import no.beint.vev.VevBinary;
import no.beint.vev.VevRows;

@Entity
@Table(name = "binary_asset", schema = "vev_it")
@VevRows(1)
public record BinaryAsset(
        @Id @GeneratedValue(strategy = GenerationType.IDENTITY) @Column(name = "id", nullable = false) Long id,
        @TenantKey @Column(name = "tenant_id", nullable = false) Integer tenantId,
        @Version @Column(name = "version", nullable = false) Integer version,
        @VevBinary(maximumBytes = 20971520, check = "binary_asset_content_max")
        @Column(name = "content", nullable = false) Binary content) {
}
