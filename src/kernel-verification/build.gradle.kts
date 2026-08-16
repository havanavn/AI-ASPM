// Verification against a live engine. Separate from :architecture-tests because those assertions
// run on bytecode and must stay fast, while these need a database and are skipped without one.
//
// The migrations under test belong to the kernel modules that own them (CON-PLT-015), so this
// subproject reads them from those modules' resources rather than holding its own copy — a second
// copy would drift and the drift would be invisible until a deployment failed.
dependencies {
    testImplementation(rootProject.libs.postgresql)
    // A real PostgreSQL, started as the current user. No docker daemon and no root: the binaries are a
    // Maven artifact and initdb/pg_ctl have never needed privilege. This is what turns 31 written
    // verifications into 31 executed ones.
    testImplementation(rootProject.libs.embedded.postgres)
    testRuntimeOnly(rootProject.libs.embedded.postgres.binaries)
    testRuntimeOnly(project(":platform-kernel:tenant-context-impl"))
    testRuntimeOnly(project(":platform-kernel:audit-impl"))
    testRuntimeOnly(project(":module:organization-scope-impl"))
    // testImplementation, not testRuntimeOnly: the ranking-agreement assertion compares the
    // SQL function against the domain enum, so it needs the type at compile time.
    testImplementation(project(":module:asset-inventory-impl"))
    testRuntimeOnly(project(":module:ingestion-impl"))
    testRuntimeOnly(project(":module:vulnerability-management-impl"))
    // testImplementation, not testRuntimeOnly: the action-class agreement assertion compares the SQL
    // CHECK against the domain enum, so it needs the type at compile time.
    testImplementation(project(":module:risk-prioritization-impl"))
    testRuntimeOnly(project(":module:work-management-impl"))
    testRuntimeOnly(project(":module:assessment-impl"))
    testRuntimeOnly(project(":module:composition-analysis-impl"))
    testRuntimeOnly(project(":module:insight-impl"))
}

tasks.withType<Test>().configureEach {
    // Supply a superuser URL to run these. Absent, every test skips with the reason stated.
    systemProperty("aspm.verification.jdbcUrl", System.getProperty("aspm.verification.jdbcUrl", ""))
    systemProperty("aspm.verification.user", System.getProperty("aspm.verification.user", ""))
    systemProperty("aspm.verification.password", System.getProperty("aspm.verification.password", ""))
}
