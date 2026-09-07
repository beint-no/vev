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
import no.beint.vev.VevTenantReference

@Entity
@JvmRecord
@VevRows(8)
@Table(name = "kotlin_registry_entry", schema = "vev_it")
data class KotlinRegistryEntry(
    @field:Id @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    @field:Column(name = "id", nullable = false) val id: Long,
    @field:TenantKey @field:VevTenantReference(name = "kotlin_registry_tenant_fk", schema = "vev_it", table = "tenant_registry", column = "id")
    @field:Column(name = "tenant_id", nullable = false) val tenantId: Int,
    @field:Version @field:Column(name = "version", nullable = false) val version: Long,
    @field:Column(name = "label", nullable = true, length = 64) val label: String?
)
