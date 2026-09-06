package no.beint.vev.it;

import jakarta.persistence.*;
import no.beint.vev.TenantKey;
import no.beint.vev.VevIndex;
import no.beint.vev.VevRows;
import no.beint.vev.VevText;

@Entity
@Table(name = "text_document", schema = "vev_it",
        uniqueConstraints = @UniqueConstraint(name = "text_document_label_key", columnNames = {"tenant_id", "label"}))
@VevRows(4)
public record TextDocument(
        @Id @Column(name = "id", nullable = false) Integer id,
        @TenantKey @Column(name = "tenant_id", nullable = false) Integer tenantId,
        @Version @Column(name = "version", nullable = false) Integer version,
        @VevText(check = "text_document_user_max")
        @Column(name = "user", nullable = true, length = 1048576) String body,
        @VevText(check = "text_document_label_max")
        @VevIndex(name = "text_document_label_idx")
        @Column(name = "label", nullable = true, length = 128) String label) {
}
