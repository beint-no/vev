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
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

@Entity
@VevRows(8)
@Table(name = "clock_default", schema = "vev_it")
public record ClockDefaultEntry(
        @Id @GeneratedValue(strategy = GenerationType.IDENTITY) @Column(name = "id", nullable = false) Integer id,
        @TenantKey @Column(name = "tenant_id", nullable = false) Integer tenantId,
        @Version @Column(name = "version", nullable = false) Integer version,
        @Column(name = "day", nullable = true, options = "DEFAULT CURRENT_DATE") LocalDate day,
        @Column(name = "moment", nullable = true, options = "DEFAULT CURRENT_TIMESTAMP") Instant moment,
        @Column(name = "rounded_moment", nullable = true, options = "DEFAULT CURRENT_TIMESTAMP(3)") Instant roundedMoment,
        @Column(name = "clock", nullable = true, options = "DEFAULT LOCALTIME") LocalTime clock,
        @Column(name = "rounded_clock", nullable = true, options = "DEFAULT LOCALTIME(6)") LocalTime roundedClock,
        @Column(name = "stamp", nullable = true, options = "DEFAULT LOCALTIMESTAMP") LocalDateTime stamp,
        @Column(name = "rounded_stamp", nullable = true, options = "DEFAULT LOCALTIMESTAMP(0)") LocalDateTime roundedStamp,
        @Column(name = "now_value", nullable = true, options = "DEFAULT now()") Instant nowValue,
        @Column(name = "transaction_value", nullable = true, options = "DEFAULT transaction_timestamp()") Instant transactionValue) {
}
