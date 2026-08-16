// The platform's own Error Prone checks. Compiled first and applied to every other
// subproject's javac invocation, so a violation is a COMPILE ERROR rather than a report
// a later stage may or may not read.
//
// Error Prone itself is not applied to this subproject: a check cannot depend on itself.
dependencies {
    compileOnly(rootProject.libs.errorprone.check.api)
    compileOnly(rootProject.libs.errorprone.annotation)
    implementation(rootProject.libs.errorprone.core)
}

tasks.withType<JavaCompile>().configureEach {
    // The check API reaches javac internals; these exports are required to compile against it.
    options.compilerArgs.addAll(listOf(
        "--add-exports=jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED",
        "--add-exports=jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED",
        "--add-exports=jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED",
        "--add-exports=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED"
    ))
}
