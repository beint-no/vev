package no.beint.vev.it;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import no.beint.vev.AppendOnly;
import no.beint.vev.TenantKey;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@AppendOnly
@Table(name = "audit_event", schema = "vev_it")
public record AuditEvent(
        @Id @Column(name = "id", nullable = false) UUID id,
        @TenantKey @Column(name = "tenant_id", nullable = false) Integer tenantId,
        @Column(name = "occurred_at", nullable = false) Instant occurredAt,
        @Column(name = "local_occurred_at", nullable = false) LocalDateTime localOccurredAt,
        @Column(name = "business_date", nullable = false) LocalDate businessDate,
        @Column(name = "event_type", nullable = false, length = 255) String eventType) {
}
