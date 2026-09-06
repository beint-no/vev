package no.beint.vev.consumer;

import java.util.UUID;

public final class PublishedSharedConsumer {
    private PublishedSharedConsumer() {
    }

    public static void main(String[] arguments) {
        var model = PublishedReferenceModelVev.POSTGRES;
        if (model.tenantType() != UUID.class || model.plans().size() != 1
                || PublishedReferenceVev.INSTANCE.scopeType() != UUID.class) {
            throw new AssertionError("Published shared-only metadata did not initialize with the declared scope type");
        }
        if (!PublishedReferenceVev.INSTANCE.externalIncomingReferences()) {
            throw new AssertionError("Published processor must retain the explicit read-only reference boundary");
        }
        var authority = PublishedReferenceModelVev.newTenantAuthority();
        try {
            authority.scope(new UUID(0L, 1L));
            throw new AssertionError("Unverified published authority granted a scope");
        } catch (IllegalStateException expected) {
            // A verified runtime must claim the authority before application access.
        }
    }
}
