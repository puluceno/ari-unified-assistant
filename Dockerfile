# Build stage: the JDK and Maven only exist here, so nothing needs installing on the host.
FROM eclipse-temurin:25-jdk AS build
WORKDIR /src
COPY mvnw pom.xml ./
COPY .mvn .mvn
RUN ./mvnw -B -q dependency:go-offline
COPY src src
RUN ./mvnw -B package

FROM eclipse-temurin:25-jre
RUN useradd --system --uid 10001 ari
USER ari
WORKDIR /app
COPY --from=build /src/target/ari-*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
