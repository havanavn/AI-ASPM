            // Kernel implementation: domain, application and infrastructure packages.
            // Nothing outside :app declares a dependency on this subproject, which is what makes
            // its internals unreachable at compile time (CON-PLT-013, ADR-050).
            dependencies {
                api(project(":platform-kernel:audit-contract"))
                implementation(project(":platform-events"))
                implementation(project(":platform-kernel:tenant-context-contract"))
}
