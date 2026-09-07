package no.beint.vev.fixtures

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import no.beint.vev.AppendOnly
import no.beint.vev.TenantKey

@Entity
@AppendOnly
@Table(name = "kotlin_nullable_mismatch", schema = "synthetic")
@JvmRecord
data class KotlinNullableMismatch(
    @field:Id @field:Column(name = "id", nullable = false) val id: Long,
    @field:TenantKey @field:Column(name = "tenant_id", nullable = false) val tenantId: Int,
    @field:Column(name = "label", nullable = true, length = 64) val label: String
)

@Entity
@AppendOnly
@Table(name = "kotlin_required_mismatch", schema = "synthetic")
@JvmRecord
data class KotlinRequiredMismatch(
    @field:Id @field:Column(name = "id", nullable = false) val id: Long,
    @field:TenantKey @field:Column(name = "tenant_id", nullable = false) val tenantId: Int,
    @field:Column(name = "label", nullable = false, length = 64) val label: String?
)

@Entity
@AppendOnly
@Table(name = "kotlin_constructor", schema = "synthetic")
@JvmRecord
data class KotlinConstructor(
    @field:Id @field:Column(name = "id", nullable = false) val id: Long,
    @field:TenantKey @field:Column(name = "tenant_id", nullable = false) val tenantId: Int,
    @field:Column(name = "label", nullable = false, length = 64) val label: String
) {
    init {
        require(label.isNotBlank())
    }
}
