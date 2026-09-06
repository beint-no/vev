package no.beint.vev.fixtures

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import no.beint.vev.TenantKey
import no.beint.vev.VevReadOnly

@Entity
@JvmRecord
@VevReadOnly
@Table(name = "kotlin_readonly", schema = "vev_it")
data class KotlinReadOnly(
    @field:Id @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    @field:Column(name = "id", nullable = false) val id: Int,
    @field:TenantKey @field:Column(name = "tenant_id", nullable = false) val tenantId: Int,
    @field:Column(name = "label", nullable = false, length = 64, insertable = false, updatable = false) val label: String
)
