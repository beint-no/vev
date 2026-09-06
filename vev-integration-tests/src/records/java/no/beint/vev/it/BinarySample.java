package no.beint.vev.it;

import jakarta.persistence.*;
import no.beint.vev.Binary;
import no.beint.vev.TenantKey;
import no.beint.vev.VevBinary;
import no.beint.vev.VevIndex;
import no.beint.vev.VevRows;

@Entity
@Table(name = "binary_sample", schema = "vev_it",
        uniqueConstraints = @UniqueConstraint(name = "binary_sample_digest_key", columnNames = {"tenant_id", "digest"}))
@VevRows(32)
public record BinarySample(
        @Id @Column(name = "id", nullable = false) Integer id,
        @TenantKey @Column(name = "tenant_id", nullable = false) Integer tenantId,
        @Version @Column(name = "version", nullable = false) Integer version,
        @VevBinary(maximumBytes = 65536, check = "binary_sample_user_max")
        @Column(name = "user", nullable = true) Binary data,
        @VevIndex(name = "binary_sample_digest_idx")
        @VevBinary(maximumBytes = 32, check = "binary_sample_digest_max")
        @Column(name = "digest", nullable = true) Binary digest) {
}
