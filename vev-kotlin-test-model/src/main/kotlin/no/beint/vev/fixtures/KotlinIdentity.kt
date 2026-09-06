package no.beint.vev.fixtures

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import no.beint.vev.TenantKey

@Entity
@Table(name = "kotlin_identity", schema = "vev_it")
@JvmRecord
data class KotlinIdentity(
    @field:Id @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    @field:Column(name = "id", nullable = false) val id: Long,
    @field:TenantKey @field:Column(name = "tenant_id", nullable = false) val tenantId: Int,
    @field:Version @field:Column(name = "version", nullable = false) val version: Long,
    @field:Column(name = "label", nullable = false, length = 64) val label: String,
    @field:Column(name = "note", nullable = true, length = 64) val note: String?
)
