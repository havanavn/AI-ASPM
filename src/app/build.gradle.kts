// The application tier's entrypoint. ADR-057 selects the JDK's own HTTP server, so `application`
// here supplies the launch script and the runtime classpath and nothing else — there is no server
// to configure.
plugins {
    application
}

application {
    mainClass.set("aspm.app.runtime.AspmApplication")
}

    // The application assembly. This is the ONLY subproject permitted to depend on an -impl,
    // because assembling the modular monolith of ADR-003 into one deployable requires seeing
    // every module's wiring. It exports nothing and no module depends on it, so the
    // permission does not widen any module's reach.
    dependencies {
        // ADR-057: the HTTP runtime is the JDK's own, so there is no server dependency here. The driver
        // is the only runtime coordinate the application tier adds, and ADR-049 already pinned it.
        implementation(rootProject.libs.postgresql)
        // ICU4J. INT-UIX-008 requires ICU message formatting and says why: "ICU is required rather
        // than simple substitution because plural rules differ in ways a substitution cannot express."
        // java.text.MessageFormat is NOT ICU — it has no `plural`, and a bundle written in ICU syntax
        // fails at format time rather than at build time. Using it and calling the result ICU would be
        // the claim this requirement exists to prevent.
        implementation(rootProject.libs.icu4j)
        // SEC-SEC-014 requires a MEMORY-HARD password hashing function with per-credential salt and
        // stored parameters. The JDK has PBKDF2, which is iteration-hard and not memory-hard: a GPU or
        // ASIC attacker gains far more from parallelism against PBKDF2 than against Argon2id, and the
        // requirement names the property rather than an algorithm. Using PBKDF2 and calling it
        // compliant would be the documented-conformance failure ASVS L3 exists to prevent.
        implementation(rootProject.libs.bouncycastle)

        // Contracts, explicitly. An -impl declares its dependencies as `implementation`, so a contract
        // does not reach this classpath transitively — which is ADR-050's point and not an inconvenience
        // to work around: the composition root names what it composes.
        implementation(project(":shared-kernel"))
        implementation(project(":platform-kernel:tenant-context-contract"))
        implementation(project(":platform-kernel:authorization-contract"))
        implementation(project(":module:organization-scope-contract"))

        implementation(project(":platform-kernel:tenant-context-impl"))
implementation(project(":platform-kernel:authorization-impl"))
implementation(project(":platform-kernel:audit-impl"))
implementation(project(":platform-kernel:schema-registry-impl"))
implementation(project(":platform-kernel:rules-engine-impl"))
implementation(project(":module:organization-scope-impl"))
implementation(project(":module:asset-inventory-impl"))
implementation(project(":module:knowledge-impl"))
implementation(project(":module:vulnerability-management-impl"))
implementation(project(":module:ingestion-impl"))
implementation(project(":module:composition-analysis-impl"))
implementation(project(":module:assessment-impl"))
implementation(project(":module:risk-prioritization-impl"))
implementation(project(":module:work-management-impl"))
implementation(project(":module:capacity-impl"))
implementation(project(":module:identity-impl"))
implementation(project(":module:notification-impl"))
implementation(project(":module:integration-impl"))
implementation(project(":module:insight-impl"))
implementation(project(":module:ai-assistance-impl"))

        // The authorization assertion inventory of DOC-16 section 6 exercises real enforcement points
        // across modules rather than doubles, because a double asserts what the test author believed the
        // module does. Test-scope only: it widens nothing on the main classpath.
        testImplementation(project(":shared-kernel"))
        testImplementation(project(":platform-kernel:rules-engine-contract"))
        testImplementation(project(":module:assessment-impl"))
        testImplementation(project(":module:composition-analysis-impl"))
        testImplementation(project(":module:risk-prioritization-impl"))
        testImplementation(project(":module:work-management-impl"))

        // A real engine for the persistence guard of TenantConnections. The properties it defends —
        // a transaction-local setting dying at commit, a write disappearing when nothing commits —
        // are engine behaviour, and a double asserting them would only assert what its author
        // believed PostgreSQL does. Same coordinates the kernel verification tier already uses.
        testImplementation(rootProject.libs.embedded.postgres)
        testRuntimeOnly(rootProject.libs.embedded.postgres.binaries)
    }
