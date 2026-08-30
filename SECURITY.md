# Security policy

Vev is experimental and not production-ready. There is no supported production release, security SLA, or bug-bounty program.

## Reporting a vulnerability

Do not disclose a suspected vulnerability in a public issue, discussion, pull request, benchmark result, or generated-source sample.

Use GitHub's private vulnerability reporting for this repository. If that facility is unavailable, contact a maintainer privately through an established channel. You may open a public issue asking how to establish private contact, but include no technical detail or indication of affected deployments.

Include, when safe:

- the affected commit and module;
- the smallest synthetic reproduction;
- expected and observed behavior;
- impact, preconditions, and whether tenant isolation is involved;
- any suggested mitigation;
- whether the report or exploit has been shared elsewhere.

Never send credentials, production data, private schemas, database dumps, real tenant identifiers, or customer information.

## Sensitive classes of defect

Treat the following as security reports:

- cross-tenant reads or writes;
- SQL injection or unsafe identifier generation;
- writes that commit after a failed Vev operation;
- optimistic-version checks that can be omitted or bypassed;
- schema verification bypass;
- generated code that exposes secrets or bind values;
- connection or transaction state leaking across requests or concurrent tasks;
- malicious annotation-processor input causing execution beyond normal compiler privileges;
- unbounded query, batch, allocation, or generated-source resource exhaustion;
- dependency or publication compromise.

Ordinary unsupported mappings that fail closed are compatibility bugs, not vulnerabilities. An unsupported mapping that is silently accepted may be security-relevant when it can weaken a tenant, transaction, or data-integrity invariant.

## Supported versions

No version is supported for production. Security fixes are developed on the current `main` branch and may be released only as experimental snapshots. Maintainers may decline to backport fixes.

## Disclosure

Maintainers will coordinate scope, mitigation, and disclosure with the reporter when possible, but this experimental project makes no response-time commitment. Public disclosure should wait until a fix or documented mitigation is available.
