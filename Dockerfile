# docs0 image for Cloud Run. Built by Cloud Build (`gcloud run deploy --source .`); see deploy/deploy.sh.

FROM eclipse-temurin:25-jdk AS build
WORKDIR /build
# Dependencies first (cached layer until pom.xml changes). go-offline is best effort: the real build below
# downloads anything it missed.
COPY pom.xml mvnw ./
COPY .mvn .mvn
RUN chmod +x mvnw && (./mvnw -B -q dependency:go-offline || true)
COPY src src
RUN ./mvnw -B -DskipTests package \
    && find target -maxdepth 1 -name '*.jar' ! -name '*.original' -exec cp {} /build/docs0.jar \;

FROM eclipse-temurin:25-jre
RUN useradd --system --uid 10001 --no-create-home docs0
WORKDIR /app
COPY --from=build /build/docs0.jar /app/docs0.jar
# The app writes only to /tmp (Tomcat scratch space). Memory settings leave headroom inside Cloud Run's 1 GiB.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -XX:+ExitOnOutOfMemoryError"
USER docs0
EXPOSE 8080
# Cloud Run injects PORT; the app reads it.
ENTRYPOINT ["java", "-jar", "/app/docs0.jar"]
