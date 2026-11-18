# Security

Report suspected vulnerabilities privately through the repository's GitHub
security reporting channel before publishing sensitive details.

The supported boundary is generated queries used through lexical sessions on
the latest JDK and PostgreSQL 18. Application SQL, migrations, connection providers,
and authentication decisions are trusted inputs. Public runtime execution primitives
exist for generated code; handwritten use bypasses compile-time query validation.
Generation proves that tenant queries scan `tenant_id` tables only for the bound tenant
(see the README); it does not prove authorization within a tenant or certify RLS policies.

The build uses disposable synthetic databases. Do not supply production data or
credentials. Database constraints, grants and row policies remain essential runtime
controls. Unsupported mappings fail generation; unexpected result values fail
execution instead of being silently coerced.
