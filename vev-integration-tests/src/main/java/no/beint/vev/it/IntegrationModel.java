package no.beint.vev.it;

import no.beint.vev.VevModel;
import no.beint.vev.fixtures.KotlinEntry;
import no.beint.vev.fixtures.KotlinIdentity;
import no.beint.vev.fixtures.KotlinBinary;

@VevModel(entities = {Account.class, AuditEvent.class, WorkItem.class, SnapshotProbe.class, KotlinEntry.class, IdentityEntry.class, IdentityCounter.class, IdentityEvent.class, KotlinIdentity.class, LargeText.class, BinaryAsset.class, BinarySample.class, KotlinBinary.class})
public final class IntegrationModel {
    private IntegrationModel() {
    }
}
