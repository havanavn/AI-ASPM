// Kernel contract: Typed attribute schemas for custom fields and type registries
// CON-PLT-011: a kernel module depends on no domain module. Enforced structurally —
// no module subproject appears below, and architecture-tests asserts it on bytecode.
dependencies {
    api(project(":shared-kernel"))
}
