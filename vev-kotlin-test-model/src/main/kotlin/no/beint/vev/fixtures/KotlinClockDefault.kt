package no.beint.vev.fixtures

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import no.beint.vev.TenantKey
import no.beint.vev.VevRows
import java.time.Instant
import java.time.LocalDate

@Entity
@JvmRecord
@VevRows(8)
@Table(name = "kotlin_clock_default", schema = "vev_it")
data class KotlinClockDefault(
    @field:Id @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    @field:Column(name = "id", nullable = false) val id: Long,
    @field:TenantKey @field:Column(name = "tenant_id", nullable = false) val tenantId: Int,
    @field:Version @field:Column(name = "version", nullable = false) val version: Long,
    @field:Column(name = "day", nullable = true, options = "DEFAULT CURRENT_DATE") val day: LocalDate?,
    @field:Column(name = "moment", nullable = true, options = "DEFAULT CURRENT_TIMESTAMP") val moment: Instant?
)
