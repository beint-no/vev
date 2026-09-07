package no.beint.vev.processor;

import java.util.List;
import javax.lang.model.element.TypeElement;

record CompiledModel(
        TypeElement declaration,
        String packageName,
        String simpleName,
        String qualifiedName,
        List<EntityMapping> entities,
        String fingerprint,
        String tenantType) {
    boolean sharedOnly() {
        return entities.stream().allMatch(EntityMapping::shared);
    }
}
