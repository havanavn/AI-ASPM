# Trivy, plus the two tools the worker needs to speak to the platform. Nothing else: this container
# holds a credential that can submit scan results, so its surface is kept to what the job requires.
FROM aquasec/trivy:0.58.2
RUN apk add --no-cache curl jq openssl coreutils
COPY scan.sh /scan.sh
RUN chmod +x /scan.sh
ENTRYPOINT ["/bin/sh", "/scan.sh"]
