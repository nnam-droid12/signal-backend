FROM eclipse-temurin:17-jre


ENV PORT 8080

COPY target/signal-0.0.1-SNAPSHOT.jar app.jar

ENTRYPOINT ["java", "-Dserver.port=${PORT}", "-jar", "/app.jar"]