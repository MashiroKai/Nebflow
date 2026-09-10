FROM eclipse-temurin:21-jre-alpine

# L2 rebrand: brand values from repo-root brand.conf — pass them as build
# args so the jar glob follows the assembly name without editing this file:
#   docker build --build-arg lower_name=$(...) -t ... .
ARG lower_name=nebflow

WORKDIR /workspace

COPY target/scala-*/${lower_name}-assembly-*.jar /app/app.jar

# Create config directory
RUN mkdir -p /root/.config/${lower_name}

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --retries=3 \
  CMD wget -qO- http://localhost:8080/api/health || exit 1

ENTRYPOINT ["java", "--add-opens", "java.base/java.lang=ALL-UNNAMED", "-jar", "/app/app.jar"]
CMD ["--server"]
