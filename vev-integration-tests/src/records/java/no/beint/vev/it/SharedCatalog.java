package no.beint.vev.it;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import no.beint.vev.VevIndex;
import no.beint.vev.VevReadOnly;
import no.beint.vev.VevReference;
import no.beint.vev.VevRows;
import no.beint.vev.VevShared;

@Entity
@VevShared
@VevReadOnly
@VevRows(8)
@Table(name = "shared_catalog", schema = "vev_it", uniqueConstraints = @UniqueConstraint(name = "shared_catalog_code_key", columnNames = "code"))
public record SharedCatalog(
        @Id @GeneratedValue(strategy = GenerationType.IDENTITY) @Column(name = "id", nullable = false) Integer id,
        @Version @Column(name = "version", nullable = false) Long version,
        @Column(name = "code", nullable = false, length = 32) String code,
        @VevIndex(name = "shared_catalog_label_idx") @Column(name = "label", nullable = true, length = 64) String label,
        @VevReference(name = "shared_catalog_parent_fk", target = SharedCatalog.class) @Column(name = "parent_id", nullable = true) Integer parentId) {
}
