package no.beint.vev.consumer;

import no.beint.vev.VevModel;

@VevModel(entities = PublishedReference.class, tenantType = java.util.UUID.class)
public final class PublishedReferenceModel {
    private PublishedReferenceModel() {
    }
}
