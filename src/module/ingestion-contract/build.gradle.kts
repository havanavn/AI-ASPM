// ingestion [supporting] — DOC-03 section 5.1 context 9
// The only importable surface of this module (DOC-02 section 7.2): commands, queries,
// events and shared value objects. Everything else is in -impl and unreachable.
dependencies {
    api(project(":shared-kernel"))
    api(project(":platform-events"))
}
