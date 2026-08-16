        // knowledge [supporting] implementation — domain, application, infrastructure.
        dependencies {
            api(project(":module:knowledge-contract"))
            implementation(project(":platform-events"))
            implementation(project(":platform-kernel:tenant-context-contract"))
implementation(project(":platform-kernel:authorization-contract"))
implementation(project(":platform-kernel:audit-contract"))
implementation(project(":platform-kernel:schema-registry-contract"))
implementation(project(":platform-kernel:rules-engine-contract"))

// No permitted cross-module dependency. Publishes guidance referenced by finding class and checklist item.
        }
