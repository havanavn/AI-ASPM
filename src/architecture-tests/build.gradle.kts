    // Structural assertions on compiled bytecode. These cover what the subproject partition
    // cannot see: intra-module layering, persistence confinement, and slice cycles.
    //
    // This is the ONE subproject permitted to depend on every -impl, because its purpose is
    // to inspect them. architecture-tests exports nothing and is not on any other
    // subproject's classpath, so the permission does not widen any module's reach.
    dependencies {
        testImplementation(project(":platform-events"))
    testImplementation(rootProject.libs.archunit.junit5)
        testImplementation(rootProject.libs.junit.jupiter)
        testRuntimeOnly(rootProject.libs.junit.platform.launcher)
        testImplementation(project(":platform-kernel:tenant-context-impl"))
testImplementation(project(":platform-kernel:authorization-impl"))
testImplementation(project(":platform-kernel:audit-impl"))
testImplementation(project(":platform-kernel:schema-registry-impl"))
testImplementation(project(":platform-kernel:rules-engine-impl"))
testImplementation(project(":module:organization-scope-impl"))
testImplementation(project(":module:asset-inventory-impl"))
testImplementation(project(":module:knowledge-impl"))
testImplementation(project(":module:vulnerability-management-impl"))
testImplementation(project(":module:ingestion-impl"))
testImplementation(project(":module:composition-analysis-impl"))
testImplementation(project(":module:assessment-impl"))
testImplementation(project(":module:risk-prioritization-impl"))
testImplementation(project(":module:work-management-impl"))
testImplementation(project(":module:capacity-impl"))
testImplementation(project(":module:identity-impl"))
testImplementation(project(":module:notification-impl"))
testImplementation(project(":module:integration-impl"))
testImplementation(project(":module:insight-impl"))
testImplementation(project(":module:ai-assistance-impl"))
    }
