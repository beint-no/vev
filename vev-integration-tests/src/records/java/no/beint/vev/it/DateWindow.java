package no.beint.vev.it;

import jakarta.persistence.CheckConstraint;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import no.beint.vev.AppendOnly;
import no.beint.vev.TenantKey;
import java.time.LocalDate;

@Entity
@AppendOnly
@Table(name = "date_window", schema = "vev_it", check = {
        @CheckConstraint(name = "date_window_plus", constraint = "(closed >= (opened + lead_days))"),
        @CheckConstraint(name = "date_window_minus", constraint = "(opened <= (closed - lag_days))"),
        @CheckConstraint(name = "date_window_span", constraint = "((closed - opened) = span_days)")})
public record DateWindow(
        @Id @Column(name = "id", nullable = false) Integer id,
        @TenantKey @Column(name = "tenant_id", nullable = false) Integer tenantId,
        @Column(name = "opened", nullable = false) LocalDate opened,
        @Column(name = "closed", nullable = true) LocalDate closed,
        @Column(name = "lead_days", nullable = false) Integer leadDays,
        @Column(name = "lag_days", nullable = false) Integer lagDays,
        @Column(name = "span_days", nullable = false) Integer spanDays) {
}
