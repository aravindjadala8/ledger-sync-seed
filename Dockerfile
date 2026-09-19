# Builds and runs ledger-sync's CLI.
#
# NOTE ON VERIFICATION: this Dockerfile and docker-compose.yml were written
# but could not be built or run in the sandbox this project was implemented
# in - that sandbox has neither a Docker daemon nor network access to Maven
# Central (see README "Environment limitations encountered"). Please
# build/run these yourself before relying on them. verify.sh remains the
# zero-dependency way to confirm the core pipeline works (JDK 21 only, no
# Docker, no network, no database) and IS verified in this repository's
# history.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY . .
# No Gradle wrapper is committed (see README's "framework decision" and
# "environment limitations" sections) - built with plain javac, same
# toolchain as verify.sh. The H2 driver is fetched directly from Maven
# Central for the runtime classpath (migrate/ingest/report/backfill/
# consistency-check all need it; verify.sh's SelfCheck path does not).
RUN mkdir -p build/classes lib && \
    javac -d build/classes $(find src/main/java -name '*.java') && \
    curl -fsSL -o lib/h2.jar \
      https://repo1.maven.org/maven2/com/h2database/h2/2.2.224/h2-2.2.224.jar

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/build/classes /app/classes
COPY --from=build /app/lib /app/lib
COPY --from=build /app/db /app/db
COPY --from=build /app/fixtures /app/fixtures
COPY --from=build /app/verify.sh /app/verify.sh
ENTRYPOINT ["java", "-cp", "/app/classes:/app/lib/h2.jar", "in.simplifymoney.ledgersync.App"]
CMD ["report", "submission"]
