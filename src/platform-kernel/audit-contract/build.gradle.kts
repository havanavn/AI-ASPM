// Kernel contract: Event recording with integrity
// CON-PLT-011: a kernel module depends on no domain module. Enforced structurally —
// no module subproject appears below, and architecture-tests asserts it on bytecode.
dependencies {
    api(project(":shared-kernel"))
}
