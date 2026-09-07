package no.beint.vev.fixtures

import jakarta.persistence.*
import no.beint.vev.TenantKey
import no.beint.vev.VevIndex
import java.time.LocalTime

@Entity
@Table(name = "kotlin_clock", schema = "vev_it", check = [CheckConstraint(
    name = "kotlin_clock_time_check", constraint = "(observed_at <= '24:00:00'::time without time zone)")])
@JvmRecord
data class KotlinClock(
    @field:Id @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    @field:Column(name = "id", nullable = false) val id: Int,
    @field:TenantKey @field:Column(name = "tenant_id", nullable = false) val tenantId: Int,
    @field:Version @field:Column(name = "version", nullable = false) val version: Int,
    @field:VevIndex(name = "kotlin_clock_time_idx")
    @field:Column(name = "observed_at", nullable = true) val observedAt: LocalTime?
)
