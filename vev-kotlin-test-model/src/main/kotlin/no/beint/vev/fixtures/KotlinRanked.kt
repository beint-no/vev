package no.beint.vev.fixtures

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import no.beint.vev.VevIndex
import no.beint.vev.VevReadOnly
import no.beint.vev.VevRows
import no.beint.vev.VevShared
import java.math.BigDecimal

@Entity
@JvmRecord
@VevReadOnly
@VevShared
@VevRows(8)
@Table(name = "kotlin_ranked", schema = "vev_it")
data class KotlinRanked(
    @field:Id @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    @field:Column(name = "id", nullable = false) val id: Int,
    @field:VevIndex(name = "kotlin_ranked_category_idx", orderBy = "position", direction = VevIndex.Direction.DESC)
    @field:Column(name = "category", nullable = true, length = 64) val category: String?,
    @field:Column(name = "position", nullable = false, precision = 19, scale = 2) val position: BigDecimal
)
