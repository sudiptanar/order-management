# Build context is the repository ROOT (see docker-compose.yml), because this module needs the
# parent pom and the two common modules. Poms are copied before sources so that a source-only
# change reuses the cached dependency layer.
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build

COPY pom.xml ./
COPY common-events/pom.xml common-events/
COPY common-messaging/pom.xml common-messaging/
COPY order-service/pom.xml order-service/
COPY inventory-service/pom.xml inventory-service/
COPY payment-service/pom.xml payment-service/
COPY notification-service/pom.xml notification-service/

RUN mvn -B -pl order-service -am -DskipTests dependency:go-offline || true

COPY common-events/src common-events/src
COPY common-messaging/src common-messaging/src
COPY order-service/src order-service/src

RUN mvn -B -pl order-service -am -DskipTests package

FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
RUN addgroup -S app && adduser -S app -G app
COPY --from=build /build/order-service/target/*.jar app.jar
RUN chown -R app:app /app
USER app
EXPOSE 8081
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75"
ENTRYPOINT ["sh","-c","exec java $JAVA_OPTS -jar /app/app.jar"]