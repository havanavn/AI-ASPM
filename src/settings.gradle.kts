        // Module topology per DOC-02 section 6.1. One subproject pair per module:
        // "-contract" is the only importable surface (DOC-02 section 7.2); "-impl" holds
        // domain, application and infrastructure and is on no other module's compile
        // classpath. That absence is the CON-PLT-013 enforcement mechanism per ADR-050.
        rootProject.name = "ai-aspm"

        dependencyResolutionManagement {
            repositories { mavenCentral() }
        }

        include(":shared-kernel")
        include(":platform-events")
        include(":build-checks")
        include(":architecture-tests")
include(":kernel-verification")
        include(":app")
include(":deployment")

        // --- platform kernel (DOC-02 section 6.2) ---
        include(":platform-kernel:tenant-context-contract")
include(":platform-kernel:tenant-context-impl")
include(":platform-kernel:authorization-contract")
include(":platform-kernel:authorization-impl")
include(":platform-kernel:audit-contract")
include(":platform-kernel:audit-impl")
include(":platform-kernel:schema-registry-contract")
include(":platform-kernel:schema-registry-impl")
include(":platform-kernel:rules-engine-contract")
include(":platform-kernel:rules-engine-impl")

        // --- domain, supporting, generic and projection modules (DOC-02 section 6.1) ---
        include(":module:organization-scope-contract")
include(":module:organization-scope-impl")
include(":module:asset-inventory-contract")
include(":module:asset-inventory-impl")
include(":module:knowledge-contract")
include(":module:knowledge-impl")
include(":module:vulnerability-management-contract")
include(":module:vulnerability-management-impl")
include(":module:ingestion-contract")
include(":module:ingestion-impl")
include(":module:composition-analysis-contract")
include(":module:composition-analysis-impl")
include(":module:assessment-contract")
include(":module:assessment-impl")
include(":module:risk-prioritization-contract")
include(":module:risk-prioritization-impl")
include(":module:work-management-contract")
include(":module:work-management-impl")
include(":module:capacity-contract")
include(":module:capacity-impl")
include(":module:identity-contract")
include(":module:identity-impl")
include(":module:notification-contract")
include(":module:notification-impl")
include(":module:integration-contract")
include(":module:integration-impl")
include(":module:insight-contract")
include(":module:insight-impl")
include(":module:ai-assistance-contract")
include(":module:ai-assistance-impl")
