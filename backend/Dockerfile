# --- build stage ---
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
# Download dependencies first so they are cached between source changes.
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B package -DskipTests

# --- runtime stage ---
FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd --system --uid 1001 app
COPY --from=build /app/target/ops-*.jar app.jar
USER app
EXPOSE 8081
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
