# the lightweight alpine does not support arm64
# hence another lightweight distro noble for broader coverage
# JRE 25 matches the GraalJS/Truffle 25.x runtime requirement. JS script tasks still run
# interpreted: as of GraalVM 25, in-process JIT of guest code requires a GraalVM JDK.
FROM eclipse-temurin:25-jre-noble AS builder
WORKDIR /application
ARG JAR_FILE=target/*.jar
COPY ${JAR_FILE} application.jar
# Spring Boot 4 removed the layertools jarmode; the tools jarmode with
# --layers --launcher produces the same layered, JarLauncher-ready layout.
RUN java -Djarmode=tools -jar application.jar extract --layers --launcher --destination extracted

FROM eclipse-temurin:25-jre-noble
RUN apt-get update && \
    apt-get install -y curl jq iputils-ping procps rsync && \
    rm -rf /var/lib/apt/lists/* && \
    addgroup java && \
    adduser --ingroup java --disabled-password java
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

ENV JVM_OPTS="-Duser.timezone=UTC -Dserver.port=${SERVER_PORT}"
ENV DEBUG_OPTS="-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:${DEBUG_PORT}"

ENTRYPOINT ["sh","-lc","exec java ${DEBUG_OPTS} ${JVM_OPTS} org.springframework.boot.loader.launch.JarLauncher"]
CMD []
