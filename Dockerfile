FROM eclipse-temurin:17-jre-alpine

WORKDIR /app
COPY target/scala-3.5.2/nebflow-assembly-1.0.0.jar nebflow.jar

# Create config directory
RUN mkdir -p /root/.config/nebflow

EXPOSE 8080

ENTRYPOINT ["java", "--add-opens", "java.base/java.lang=ALL-UNNAMED", "-jar", "nebflow.jar"]
CMD ["--server"]
