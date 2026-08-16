        // identity [generic] implementation — domain, application, infrastructure.
        dependencies {
            api(project(":module:identity-contract"))
            implementation(project(":platform-events"))
            implementation(project(":platform-kernel:tenant-context-contract"))
implementation(project(":platform-kernel:authorization-contract"))
implementation(project(":platform-kernel:audit-contract"))
implementation(project(":platform-kernel:schema-registry-contract"))
implementation(project(":platform-kernel:rules-engine-contract"))

// No permitted cross-module dependency. Supplies principal existence only (ADR-041).
        }
