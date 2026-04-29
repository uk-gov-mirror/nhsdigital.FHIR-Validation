FROM hapiproject/hapi:v8.8.0-1 AS base

FROM eclipse-temurin:21-jdk

# Copy the war from the base image
COPY --from=base /app/main.war /tmp/main.war
COPY --from=base /app/opentelemetry-javaagent.jar /app/opentelemetry-javaagent.jar

# Install zip tools
RUN apt-get update && apt-get install -y unzip zip && rm -rf /var/lib/apt/lists/*

# Extract the WAR, add your JAR into WEB-INF/lib, repack
# Use -0 (store only, no compression) for nested jars/wars so Spring Boot can read them
RUN mkdir -p /tmp/war-contents && \
    cd /tmp/war-contents && \
    unzip /tmp/main.war && \
    mkdir -p WEB-INF/lib

COPY target/term-interceptor-1.0.jar /tmp/war-contents/WEB-INF/lib/term-interceptor.jar

RUN cd /tmp/war-contents && \
    zip -0 -r /app/main.war . && \
    rm -rf /tmp/war-contents /tmp/main.war

WORKDIR /app

ENTRYPOINT ["java", "-jar", "/app/main.war"]