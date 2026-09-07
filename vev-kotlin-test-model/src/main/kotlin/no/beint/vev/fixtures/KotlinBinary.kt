package no.beint.vev.fixtures

import jakarta.persistence.*
import no.beint.vev.Binary
import no.beint.vev.TenantKey
import no.beint.vev.VevBinary
import no.beint.vev.VevRows

@Entity
@Table(name = "kotlin_binary", schema = "vev_it")
@JvmRecord
@VevRows(4)
data class KotlinBinary(
    @field:Id @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    @field:Column(name = "id", nullable = false) val id: Int,
    @field:TenantKey @field:Column(name = "tenant_id", nullable = false) val tenantId: Int,
    @field:Version @field:Column(name = "version", nullable = false) val version: Int,
    @field:VevBinary(maximumBytes = 128, check = "kotlin_binary_payload_max")
    @field:Column(name = "payload", nullable = true) val payload: Binary?
)
