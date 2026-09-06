package no.beint.vev.it;

import no.beint.vev.VevModel;
import no.beint.vev.fixtures.KotlinEntry;

@VevModel(entities = {Account.class, AuditEvent.class, WorkItem.class, SnapshotProbe.class, KotlinEntry.class})
public final class IntegrationModel {
    private IntegrationModel() {
    }
}
