package no.beint.vev.it;

import no.beint.vev.VevModel;

@VevModel(entities = {Account.class, AuditEvent.class})
public final class IntegrationModel {
    private IntegrationModel() {
    }
}
