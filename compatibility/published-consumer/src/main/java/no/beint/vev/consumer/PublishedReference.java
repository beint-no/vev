package no.beint.vev.consumer;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import no.beint.vev.VevReadOnly;
import no.beint.vev.VevShared;

@Entity
@VevReadOnly
@VevShared
@Table(name = "published_reference", schema = "published_consumer")
public record PublishedReference(
        @Id @Column(name = "id", nullable = false) Integer id,
        @Column(name = "label", nullable = false, length = 64) String label) {
}
