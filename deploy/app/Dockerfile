# The application tier. ADR-057, ADR-050.
#
# The Gradle build runs on the HOST, not in a build stage here, because container egress is blocked in the
# environment this was developed in — DOC-15 section 14's air-gapped topology arriving by accident rather
# than by design. `gradle :app:installDist` produces the layout copied below.
#
# That is also why OPS-DEP-027 holds trivially: "signing keys MUST NOT be reachable from build steps that
# execute repository content". No build step here executes repository content.

FROM eclipse-temurin:25-jre

# A non-root user. This tier holds app_runtime and reads tenant data; a container process with more
# authority than the credential it carries is authority nobody accounted for.
RUN useradd --system --uid 10001 --create-home aspm
USER aspm
WORKDIR /opt/aspm

COPY --chown=aspm:aspm install/ /opt/aspm/

# Plain HTTP. DOC-15 section 3.1 places a TLS-terminating ingress in front, and OPS-DEP-017 forbids that
# ingress from making authorization decisions — so this port is internal and is not published directly in
# a real topology.
EXPOSE 8080

ENTRYPOINT ["/opt/aspm/bin/app"]
