# The migration runner as an image, for a cluster that cannot mount the repository (OPS-DEP-009: the
# only unit holding migration_runner). The compose deployment mounts ../src instead; both run the same
# apply.sh over the same files, gathered here by deploy/collect-migrations.sh into ./migrations before
# the build. Pin by digest in a real pipeline (OPS-DEP-028).
FROM postgres:18-alpine
COPY migrations/ /src/
COPY apply.sh /apply.sh
USER 70
ENTRYPOINT ["/bin/sh", "/apply.sh"]
