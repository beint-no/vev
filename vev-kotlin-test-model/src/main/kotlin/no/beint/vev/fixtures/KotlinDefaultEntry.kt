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

@Entity
@JvmRecord
@VevRows(8)
@Table(name = "kotlin_default", schema = "vev_it")
data class KotlinDefaultEntry(
    @field:Id @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    @field:Column(name = "id", nullable = false) val id: Long,
    @field:TenantKey @field:Column(name = "tenant_id", nullable = false) val tenantId: Int,
    @field:Version @field:Column(name = "version", nullable = false) val version: Long,
    @field:Column(name = "enabled", nullable = false, options = "DEFAULT true") val enabled: Boolean,
    @field:Column(name = "label", nullable = true, length = 64, options = "DEFAULT 'kotlin'::character varying") val label: String?,
    @field:Column(name = "attempts", nullable = true, options = "DEFAULT 7") val attempts: Int?
)
