FROM maven:3.9.16-eclipse-temurin-17-alpine@sha256:097d7c4ac16bef6c3a8bdc53cf5abe82af5c43547a888a4d2d9f6009fcdcad9f AS maven
WORKDIR /build

FROM maven AS build
COPY pom.xml ./
COPY src/main ./src/main
RUN --mount=type=cache,target=/root/.m2,id=finguard-copilot-m2,sharing=locked \
    mvn -B -ntp -Daether.connector.connectTimeout=10000 -Daether.connector.requestTimeout=30000 -DskipTests package \
    && cp target/finguard-copilot-*.jar app.jar

FROM maven AS core-build
COPY --from=core-source pom.xml ./
COPY --from=core-source src/main ./src/main
RUN --mount=type=cache,target=/root/.m2,id=finguard-core-m2,sharing=locked \
    mvn -B -ntp -Daether.connector.connectTimeout=10000 -Daether.connector.requestTimeout=30000 -DskipTests package \
    && cp target/finguard-core-*.jar app.jar

FROM eclipse-temurin:17-jre-alpine@sha256:5c658c46ee58c35b00394b0ccd9c52dc76f34e66ad7bc426de7be8a466a20431 AS java-runtime
RUN addgroup -g 10001 finguard && adduser -D -H -u 10001 -G finguard finguard
WORKDIR /app
USER 10001:10001

FROM build AS seed-build
COPY scripts/e2e/PublishedKnowledgeSeed.java ./
RUN jar xf app.jar BOOT-INF/classes BOOT-INF/lib \
    && javac -cp 'BOOT-INF/classes:BOOT-INF/lib/*' -d BOOT-INF/classes PublishedKnowledgeSeed.java

FROM java-runtime AS knowledge-seed
COPY --from=seed-build /build/BOOT-INF /app
ENTRYPOINT ["java", "-cp", "/app/classes:/app/lib/*", "PublishedKnowledgeSeed"]

FROM java-runtime AS core-runtime
COPY --from=core-build /build/app.jar /app/app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/app.jar"]

FROM java-runtime AS runtime
COPY --from=build /build/app.jar /app/app.jar
EXPOSE 8080 9091
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/app.jar"]
