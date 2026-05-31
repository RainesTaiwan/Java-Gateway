# 建置階段：使用 Maven 編譯 Spring Boot 可執行 JAR
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app

COPY pom.xml .
RUN mvn -B -ntp dependency:go-offline

COPY src ./src
RUN mvn -B -ntp -DskipTests package

# 執行階段：僅保留 JRE 與應用程式 JAR
FROM eclipse-temurin:17-jre-jammy
WORKDIR /app

COPY --from=build /app/target/java-gateway-0.0.1-SNAPSHOT.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
