// Root conventions.
//
// Every structural control of DOC-02 section 7 that is expressible as a build property lives
// here, so a new module INHERITS it rather than opting in. A control a module can forget to
// apply is a control that erodes, which is the failure DOC-02 section 7.1 describes.

import net.ltgt.gradle.errorprone.errorprone

plugins {
    `java-library`
    id("net.ltgt.errorprone") version "4.3.0" apply false
}

// ADR-050 floor: Java 25 LTS or later.
val javaLanguageVersion = 25

// Version catalogue accessor, usable inside the subprojects blocks below.
val catalog = the<org.gradle.accessors.dm.LibrariesForLibs>()

subprojects {
    // `java-library` rather than `java`: the api/implementation distinction is load-bearing for
    // ADR-050. A contract declares its published dependencies as `api` so consumers see them; an
    // impl declares everything as `implementation` so nothing it uses leaks onto a consumer's
    // compile classpath. Without that distinction, a transitive dependency would silently widen
    // the reach CON-PLT-013 restricts.
    apply(plugin = "java-library")

    repositories { mavenCentral() }

    extensions.configure<JavaPluginExtension> {
        toolchain { languageVersion.set(JavaLanguageVersion.of(javaLanguageVersion)) }
    }

    // ADR-050 required property: deterministic dependency resolution. `dependencyLocking`
    // writes a lockfile per configuration; the catalogue pins every coordinate and no dynamic
    // or snapshot selector is permitted.
    dependencyLocking { lockAllConfigurations() }

    tasks.withType<JavaCompile>().configureEach {
        options.compilerArgs.add("-Xlint:all")
    }

    // Every module gets the test framework rather than declaring it. TST-PLT-004 requires a test
    // per invariant asserting it cannot be violated through any write path, so a module without a
    // test source set is a module that owes tests it has no place to put.
    dependencies {
        "testImplementation"(catalog.junit.jupiter)
        "testRuntimeOnly"(catalog.junit.platform.launcher)
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        // S13 needs the requirement register. Passed as a property rather than resolved relative to a
        // working directory, which differs between an IDE run and a Gradle run.
        systemProperty("aspm.corpusRoot", rootProject.projectDir.parentFile.absolutePath)
    }
}

// DOC-03 section 5.3 row 3 makes Authorization a Published Language to ALL contexts: "every context
// enforces authorization through a single published evaluation contract. Contexts do not implement their
// own checks — a per-context check is how enforcement points get omitted."
//
// A module's CONTRACT therefore legitimately names permissions, so every module contract gets the
// authorization kernel contract. Encoded here rather than added per module as each one discovers it,
// because rediscovering a corpus-wide rule fifteen times is fifteen chances to resolve it differently.
//
// Acyclic: authorization-contract depends on tenant-context-contract and :shared-kernel only, so no
// module is involved and CON-PLT-016 holds.
configure(subprojects.filter { it.name.endsWith("-contract") && it.parent?.name == "module" }) {
    dependencies {
        "api"(project(":platform-kernel:authorization-contract"))
    }
}

// Error Prone is applied to every subproject except :build-checks, which defines the checks and
// therefore cannot depend on itself.
configure(subprojects.filter { it.name != "build-checks" }) {
    apply(plugin = "net.ltgt.errorprone")

    dependencies {
        "errorprone"(catalog.errorprone.core)
        // The platform's own checks, loaded by ServiceLoader from :build-checks.
        "errorprone"(project(":build-checks"))
    }

    tasks.withType<JavaCompile>().configureEach {
        options.errorprone {
            // OPS-DEP-026: every gate blocks rather than warns. A platform check is never
            // advisory, so both are pinned to ERROR here regardless of their declared default.
            error("RoleIdentifierComparison", "DirectInfrastructureClientAccess")
            disableWarningsInGeneratedCode.set(true)
        }
    }
}
