// The shared events package of DOC-02 section 7.3 — the one permitted asymmetry.
// A module may subscribe to another module's event without a compile-time dependency on
// that module, because the event contract is published here rather than owned by the
// publisher. This is how audit, notification and insight observe everything without
// depending on everything.
dependencies {
    api(project(":shared-kernel"))
}
