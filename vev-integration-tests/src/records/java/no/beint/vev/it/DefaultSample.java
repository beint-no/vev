package no.beint.vev.it;

import jakarta.persistence.CheckConstraint;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Enumerated;
import jakarta.persistence.EnumType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import no.beint.vev.TenantKey;
import no.beint.vev.VevBinary;
import no.beint.vev.VevIndex;
import no.beint.vev.VevRows;
import no.beint.vev.VevText;

@Entity
@VevRows(8)
@Table(name = "default_sample", schema = "vev_it", check =
        @CheckConstraint(name = "default_sample_counter_check", constraint = "(counter >= 0)"))
public record DefaultSample(
        @Id @GeneratedValue(strategy = GenerationType.IDENTITY) @Column(name = "id", nullable = false) Integer id,
        @TenantKey @Column(name = "tenant_id", nullable = false) Integer tenantId,
        @Version @Column(name = "version", nullable = false) Integer version,
        @VevIndex(name = "default_sample_enabled_idx") @Column(name = "enabled", nullable = false, options = "DEFAULT true") Boolean enabled,
        @Column(name = "small_value", nullable = false, options = "DEFAULT 0") Short smallValue,
        @Column(name = "counter", nullable = false, options = "DEFAULT (2 + 3)") Integer counter,
        @Column(name = "long_value", nullable = false, options = "DEFAULT 0") Long longValue,
        @Column(name = "amount", nullable = false, precision = 12, scale = 2, options = "DEFAULT 0") java.math.BigDecimal amount,
        @Column(name = "label", nullable = true, length = 64, options = "DEFAULT 'fallback'::character varying") String label,
        @VevText(check = "default_sample_body_length") @Column(name = "body", nullable = true, length = 128, options = "DEFAULT 'defaults; are metadata'::text") String body,
        @Column(name = "day", nullable = true, options = "DEFAULT '2024-01-01'::date") java.time.LocalDate day,
        @Column(name = "clock", nullable = true, options = "DEFAULT '12:00:00'::time without time zone") java.time.LocalTime clock,
        @Column(name = "stamp", nullable = true, options = "DEFAULT '2024-01-01 12:00:00.123456'::timestamp without time zone") java.time.LocalDateTime stamp,
        @Column(name = "moment", nullable = true, options = "DEFAULT '2024-01-01 00:00:00+00'::timestamp with time zone") java.time.Instant moment,
        @Column(name = "token", nullable = true, options = "DEFAULT '00000000-0000-0000-0000-000000000000'::uuid") java.util.UUID token,
        @VevBinary(maximumBytes = 64, check = "default_sample_payload_length") @Column(name = "payload", nullable = true, options = "DEFAULT '\\x73616d706c65'::bytea") no.beint.vev.Binary payload,
        @Enumerated(EnumType.STRING) @Column(name = "state", nullable = false, length = 8, options = "DEFAULT 'OPEN'::character varying") WorkState state) {
}
