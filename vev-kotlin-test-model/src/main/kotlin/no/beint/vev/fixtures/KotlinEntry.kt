package no.beint.vev.fixtures

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import no.beint.vev.TenantKey
import no.beint.vev.VevIndex

@Entity
@Table(name = "kotlin_entry", schema = "vev_it")
@JvmRecord
data class KotlinEntry(
    @field:Id @field:Column(name = "id", nullable = false) val id: Long,
    @field:TenantKey @field:Column(name = "tenant_id", nullable = false) val tenantId: Int,
    @field:Version @field:Column(name = "version", nullable = false) val version: Long,
    @field:VevIndex(name = "kotlin_entry_label_idx")
    @field:Column(name = "label", nullable = false, length = 128) val label: String,
    @field:Column(name = "alias", nullable = true, length = 64) val alias: String?
)
