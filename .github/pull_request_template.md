## Change

Describe the supported behavior or internal improvement and why it belongs in Vev's experimental scope.

## Safety boundary

Describe what remains unsupported, how it fails closed, and any transaction, schema, concurrency, or tenant impact.

## Verification

List the exact commands and synthetic fixtures used.

## Checklist

- [ ] I used JDK 26 and ran `./gradlew clean check integrationTest`.
- [ ] Tests, examples, logs, and generated sources contain only synthetic data.
- [ ] New mapping behavior has positive and negative compiler coverage.
- [ ] Database behavior has PostgreSQL 18 integration coverage.
- [ ] I reviewed rollback and resource cleanup for failure paths.
- [ ] I reviewed the tenant threat model, or this change cannot affect tenancy.
- [ ] I updated the supported profile, limitations, migration notes, or roadmap as needed.
- [ ] Every new production dependency has an architectural justification covering ownership and runtime, security, and publication impact.
- [ ] Performance-sensitive changes include comparable before-and-after evidence against Hibernate ORM `8.0.0.Beta1`, or this change cannot affect performance.
- [ ] Dependency and benchmark changes preserve isolated classpaths and disclose prerelease versions.
- [ ] This pull request makes no unsupported production-readiness, TCK, or universal performance claim.
