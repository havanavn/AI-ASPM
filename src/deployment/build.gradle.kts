// Deployment topology as a typed, tested model. DOC-15.
//
// This subproject holds no domain logic and depends on no module. It exists because the deployment
// properties DOC-15 requires are otherwise expressed in orchestration manifests that nothing checks:
// a manifest is data a reviewer reads, and OPS-DEP-009's whole point is that credential separation is
// structural rather than procedural. Encoding the topology here means a placement violation, a bypass
// credential in a runtime environment, or a gate demoted to a warning fails the build that produced it.
//
// The manifests are generated FROM this model rather than checked against it. A checker and a manifest
// drift; a generator cannot.
