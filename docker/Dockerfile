FROM eclipse-temurin:21-jdk-jammy AS build

# Only bootstraps the build. The sbt version actually used is the one pinned in
# project/build.properties; this launcher just downloads and runs it.
ARG SBT_VERSION=1.9.9

RUN apt-get update \
 && apt-get install -y --no-install-recommends curl ca-certificates \
 && rm -rf /var/lib/apt/lists/* \
 && curl -fsSL "https://github.com/sbt/sbt/releases/download/v${SBT_VERSION}/sbt-${SBT_VERSION}.tgz" | tar -xz -C /opt

ENV PATH="/opt/sbt/bin:${PATH}"

WORKDIR /app

# Resolve dependencies in their own layer, before the sources are copied, so that editing
# source does not force every dependency to be downloaded again.
COPY project ./project
COPY build.sbt ./
RUN sbt update

COPY . .

RUN sbt compile stage
RUN chmod a+rx /app/target/universal/stage/bin/*

FROM eclipse-temurin:21-jre-jammy

WORKDIR /app

COPY --from=build /app/target/universal/stage /app

COPY --chmod=755 docker/entrypoint.sh /app/entrypoint.sh
RUN sed -i 's/\r$//' /app/entrypoint.sh

ENTRYPOINT ["/app/entrypoint.sh"]
