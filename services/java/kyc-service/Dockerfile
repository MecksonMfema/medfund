FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /app
COPY gradle gradle
COPY gradlew build.gradle.kts settings.gradle.kts ./
COPY shared/build.gradle.kts shared/
COPY shared/src shared/src
COPY rules-engine/build.gradle.kts rules-engine/
COPY rules-engine/src rules-engine/src
COPY kyc-service/build.gradle.kts kyc-service/
COPY kyc-service/src kyc-service/src
RUN chmod +x gradlew && ./gradlew :kyc-service:bootJar --no-daemon

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /app/kyc-service/build/libs/*.jar app.jar
EXPOSE 8087
ENTRYPOINT ["java", "-jar", "app.jar"]
