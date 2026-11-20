# --- build stage ---
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q -DskipTests package && cp target/paysure-backend-*.jar /app.jar

# --- runtime stage ---
FROM eclipse-temurin:17-jre-alpine
RUN addgroup -S paysure && adduser -S paysure -G paysure
USER paysure
WORKDIR /app
COPY --from=build /app.jar app.jar
EXPOSE 8080
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
