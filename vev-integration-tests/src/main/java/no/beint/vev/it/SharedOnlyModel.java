package no.beint.vev.it;

import no.beint.vev.VevModel;
import no.beint.vev.fixtures.KotlinOnlyNote;

@VevModel(entities = {OnlyCategory.class, KotlinOnlyNote.class}, tenantType = int.class)
public final class SharedOnlyModel {
    private SharedOnlyModel() {
    }
}
