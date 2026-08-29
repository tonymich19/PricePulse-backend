FROM eclipse-temurin:21-jdk-jammy AS build

WORKDIR /workspace

COPY gradlew gradlew.bat settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
RUN chmod +x gradlew

COPY contracts ./contracts
COPY src ./src
RUN ./gradlew --no-daemon installDist

FROM eclipse-temurin:21-jre-jammy

RUN useradd --system --create-home --uid 10001 pricepulse

WORKDIR /app

COPY --from=build --chown=pricepulse:pricepulse /workspace/build/install/PricePulse-backend/ ./
COPY docker/entrypoint.sh /usr/local/bin/pricepulse-entrypoint

RUN chmod 0555 /usr/local/bin/pricepulse-entrypoint

USER pricepulse

EXPOSE 8080

ENTRYPOINT ["/usr/local/bin/pricepulse-entrypoint"]
CMD ["/app/bin/PricePulse-backend"]
