// Kernel contract: The single permission evaluation contract
// CON-PLT-011: a kernel module depends on no domain module. Enforced structurally —
// no module subproject appears below, and architecture-tests asserts it on bytecode.
dependencies {
    api(project(":shared-kernel"))
    // ScopeResolver's parameter type. Acyclic: tenant-context-contract depends only on
    // :shared-kernel, so authorization -> tenant-context closes no loop and CON-PLT-016 holds.
    api(project(":platform-kernel:tenant-context-contract"))
}
