# stage 1 – сборка
FROM maven:3.9.6-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -q dependency:go-offline
COPY src ./src
RUN mvn -q package -DskipTests

# stage 2 – лёгкий рантайм
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /app/target/app-jar-with-dependencies.jar app.jar
ENTRYPOINT ["java","-jar","app.jar"]