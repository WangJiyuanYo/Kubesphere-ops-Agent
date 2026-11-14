FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml settings-central.xml ./
COPY src ./src
RUN mvn -s settings-central.xml -B -ntp verify

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN mkdir -p /app/data && chown 10001:10001 /app/data
COPY --from=build /build/target/deployment-ops-agent-0.1.0-SNAPSHOT.jar /app/agent.jar
USER 10001:10001
ENV SERVER_ADDRESS=0.0.0.0
EXPOSE 8098
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/agent.jar"]
