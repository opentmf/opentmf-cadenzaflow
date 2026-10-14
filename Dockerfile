# the lightweight alpine does not support arm64
# hence another lightweight distro noble for broader coverage
# JRE 25 matches the GraalJS/Truffle 25.x runtime requirement. JS script tasks still run
# interpreted: as of GraalVM 25, in-process JIT of guest code requires a GraalVM JDK.
# Pinned 2026-10-14: this digest of eclipse-temurin:25-jre-noble ships libssl3t64
# 3.0.13-0ubuntu3.16, noble's fixed version for CVE-2026-84782 (openssl HIGH) -
# closed at the image, no .trivyignore entry, no apt-get upgrade (upgrades belong
# to the base bump). Re-measure and re-point the digest on the next base bump.
FROM eclipse-temurin:25-jre-noble@sha256:d9a39a23634650173f1e2bbc176227af9728587ecf0f4b62d53e9355cd7a19ab AS builder
WORKDIR /application
ARG JAR_FILE=target/*.jar
COPY ${JAR_FILE} application.jar
# Spring Boot 4 removed the layertools jarmode; the tools jarmode with
# --layers --launcher produces the same layered, JarLauncher-ready layout.
RUN java -Djarmode=tools -jar application.jar extract --layers --launcher --destination extracted

FROM eclipse-temurin:25-jre-noble@sha256:d9a39a23634650173f1e2bbc176227af9728587ecf0f4b62d53e9355cd7a19ab
# The uid is PINNED because a deployment's securityContext.runAsUser has to name it
# and must not silently drift when the base image changes. 1001, not 1000: Ubuntu
# noble already ships an `ubuntu` user at 1000, so an unpinned adduser lands on 1001
# anyway - pinning records the value the released images have always had rather than
# changing it.
RUN apt-get update && \
    apt-get install -y curl jq iputils-ping procps rsync && \
    rm -rf /var/lib/apt/lists/* && \
    addgroup --gid 1001 java && \
    adduser --uid 1001 --ingroup java --disabled-password java
USER java
WORKDIR /application
COPY --chown=java:java --from=builder /application/extracted/dependencies/ ./
COPY --chown=java:java --from=builder /application/extracted/spring-boot-loader/ ./
COPY --chown=java:java --from=builder /application/extracted/snapshot-dependencies/ ./
COPY --chown=java:java --from=builder /application/extracted/application/ ./

ENV SERVER_PORT=8080
ENV DEBUG_PORT=5005
EXPOSE $SERVER_PORT
EXPOSE ${DEBUG_PORT}
# The actuator - health probes and the /actuator/prometheus scrape endpoint - is served
# on management.server.port (16000), not SERVER_PORT. Declared literally rather than via
# ENV: an env var of that name would bind to the property and restate its own default.
EXPOSE 16000

# The log shape. logback-spring.xml selects the root appender by plain variable
# substitution (`${LOGGING_APPENDER:-CONSOLE}`), so an image logs masked JSON while a
# developer running the jar gets the console. Unlike the port variables above this is a
# pure Logback variable - there is no `logging.appender` Spring property for it to bind
# to - so it is real configuration, not a restated default.
ENV LOGGING_APPENDER=JSON

ENV JVM_OPTS="-Duser.timezone=UTC -Dserver.port=${SERVER_PORT}"
ENV DEBUG_OPTS="-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:${DEBUG_PORT}"

# Provenance (DNMS template convention): the deploy repin script matches
# image.revision against the tag's sha. Declared LAST so a changing commit does not
# invalidate the apt/layer cache above.
ARG APP_COMMIT=""
ARG APP_SOURCE=""
LABEL org.opencontainers.image.revision="${APP_COMMIT}" \
      org.opencontainers.image.source="${APP_SOURCE}"

ENTRYPOINT ["sh","-lc","exec java ${DEBUG_OPTS} ${JVM_OPTS} org.springframework.boot.loader.launch.JarLauncher"]
CMD []
