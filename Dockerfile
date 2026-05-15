FROM eclipse-temurin:17-jre-alpine

WORKDIR /workspace

COPY target/scala-3.5.2/nebflow-assembly-*.jar /app/nebflow.jar

# Create config directory
RUN mkdir -p /root/.config/nebflow

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --retries=3 \
  CMD wget -qO- http://localhost:8080/api/health || exit 1

ENTRYPOINT ["java", "--add-opens", "java.base/java.lang=ALL-UNNAMED", "-jar", "/app/nebflow.jar"]
CMD ["--server"]
