package no.beint.vev.fixtures

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import no.beint.vev.VevIndex
import no.beint.vev.VevReadOnly
import no.beint.vev.VevRows
import no.beint.vev.VevShared

@Entity
@JvmRecord
@VevShared
@VevReadOnly(externalIncomingReferences = true)
@VevRows(8)
@Table(name = "only_note", schema = "vev_it")
data class KotlinOnlyNote(
    @field:Id @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    @field:VevIndex(name = "only_note_id_idx")
    @field:Column(name = "id", nullable = false) val id: Long,
    @field:Version @field:Column(name = "version", nullable = false) val version: Int,
    @field:Column(name = "label", nullable = true, length = 64) val label: String?
)
