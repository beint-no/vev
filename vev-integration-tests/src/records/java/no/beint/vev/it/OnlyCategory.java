package no.beint.vev.it;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import no.beint.vev.VevIndex;
import no.beint.vev.VevReadOnly;
import no.beint.vev.VevReference;
import no.beint.vev.VevRows;
import no.beint.vev.VevShared;

@Entity
@VevShared
@VevReadOnly
@VevRows(8)
@Table(name = "only_category", schema = "vev_it", uniqueConstraints =
        @UniqueConstraint(name = "only_category_code_key", columnNames = "code"))
public record OnlyCategory(
        @Id @GeneratedValue(strategy = GenerationType.IDENTITY) @Column(name = "id", nullable = false) Integer id,
        @VevIndex(name = "only_category_code_idx") @Column(name = "code", nullable = false, length = 32) String code,
        @VevIndex(name = "only_category_label_idx", orderBy = "position") @Column(name = "label", nullable = true, length = 64) String label,
        @Column(name = "position", nullable = false) Integer position,
        @VevReference(name = "only_category_parent_fk", target = OnlyCategory.class) @Column(name = "parent_id", nullable = true) Integer parentId) {
}
