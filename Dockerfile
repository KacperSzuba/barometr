# The application, as it runs anywhere that is not a developer's laptop.
#
# The jar is built outside this file and copied in. A Gradle build inside a Docker
# build would download the world on every run — the layer cache is not the dependency
# cache, and CI already has one that works.

FROM eclipse-temurin:25-jre AS layers

WORKDIR /layers
COPY app/build/libs/app.jar app.jar

# Boot's own layout: dependencies change rarely and the application changes every
# commit, so splitting them means a deploy ships megabytes rather than eighty of them.
RUN java -Djarmode=tools -jar app.jar extract --layers --launcher --destination extracted

FROM eclipse-temurin:25-jre

# Not root. Nothing here needs to write outside its own directory, and a container that
# could is one more thing standing between a bug and a bad afternoon.
RUN useradd --system --create-home --uid 10001 barometr
USER barometr
WORKDIR /app

# Ordered by how often each changes, which is the whole point of the split above.
COPY --from=layers --chown=barometr /layers/extracted/dependencies/ ./
COPY --from=layers --chown=barometr /layers/extracted/spring-boot-loader/ ./
COPY --from=layers --chown=barometr /layers/extracted/snapshot-dependencies/ ./
COPY --from=layers --chown=barometr /layers/extracted/application/ ./

EXPOSE 8080

# The container is the process: no shell, so signals reach the JVM and a `docker stop`
# is a clean shutdown rather than a kill after ten seconds.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "org.springframework.boot.loader.launch.JarLauncher"]
