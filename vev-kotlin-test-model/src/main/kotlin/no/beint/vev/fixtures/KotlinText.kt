package no.beint.vev.fixtures

import jakarta.persistence.*
import no.beint.vev.TenantKey
import no.beint.vev.VevText
import no.beint.vev.VevRows

@Entity
@Table(name = "kotlin_text", schema = "vev_it")
@JvmRecord
@VevRows(4)
data class KotlinText(
    @field:Id @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    @field:Column(name = "id", nullable = false) val id: Int,
    @field:TenantKey @field:Column(name = "tenant_id", nullable = false) val tenantId: Int,
    @field:Version @field:Column(name = "version", nullable = false) val version: Int,
    @field:VevText(check = "kotlin_text_payload_max")
    @field:Column(name = "payload", nullable = true, length = 64) val payload: String?
)
