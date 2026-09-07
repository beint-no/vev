package no.beint.vev.fixtures

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import no.beint.vev.VevShared
import no.beint.vev.VevIndex
import no.beint.vev.VevReadOnly

@Entity
@JvmRecord
@VevReadOnly
@VevShared
@Table(name = "kotlin_shared", schema = "vev_it")
data class KotlinShared(
    @field:VevIndex(name = "kotlin_shared_id_idx")
    @field:Id @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    @field:Column(name = "id", nullable = false) val id: Int,
    @field:Column(name = "label", nullable = false, length = 64, insertable = false, updatable = false) val label: String
)
