# Build Stage - Ubuntu with OpenJDK 25
FROM ubuntu:24.04 AS build
WORKDIR /app

# Install Java 25.0.1, Maven, and Node.js
RUN apt-get update && apt-get install -y curl wget nodejs npm && \
    wget -q https://download.java.net/java/GA/jdk25.0.1/2fbf10d8c78e40bd87641c434705079d/8/GPL/openjdk-25.0.1_linux-x64_bin.tar.gz && \
    mkdir -p /opt/java && \
    tar -xzf openjdk-25.0.1_linux-x64_bin.tar.gz -C /opt/java --strip-components=1 && \
    rm openjdk-25.0.1_linux-x64_bin.tar.gz && \
    curl -o maven.tar.gz https://archive.apache.org/dist/maven/maven-3/3.9.6/binaries/apache-maven-3.9.6-bin.tar.gz && \
    mkdir -p /opt/maven && \
    tar -xzf maven.tar.gz -C /opt/maven --strip-components=1 && \
    rm maven.tar.gz && \
    apt-get clean && rm -rf /var/lib/apt/lists/*

ENV JAVA_HOME=/opt/java
ENV PATH=/opt/java/bin:/opt/maven/bin:$PATH

# Verify Java 25
RUN java -version

# Copy everything
COPY . .

# Build dashboard FIRST, then copy to resources for embedding in JAR
RUN cd dashboard && npm ci && npm run build && \
    mkdir -p ../src/main/resources/public && \
    cp -r dist/* ../src/main/resources/public/

# Now build JAR with embedded dashboard
RUN mvn -B clean package -DskipTests

# Run Stage - Ubuntu with OpenJDK 25
FROM ubuntu:24.04
WORKDIR /app

# Install Java 25.0.1 and curl for healthchecks
RUN apt-get update && apt-get install -y curl wget && \
    wget -q https://download.java.net/java/GA/jdk25.0.1/2fbf10d8c78e40bd87641c434705079d/8/GPL/openjdk-25.0.1_linux-x64_bin.tar.gz && \
    mkdir -p /opt/java && \
    tar -xzf openjdk-25.0.1_linux-x64_bin.tar.gz -C /opt/java --strip-components=1 && \
    rm openjdk-25.0.1_linux-x64_bin.tar.gz && \
    apt-get clean && rm -rf /var/lib/apt/lists/*

ENV JAVA_HOME=/opt/java
ENV PATH=/opt/java/bin:$PATH

COPY --from=build /app/target/trading-backend-1.0-SNAPSHOT.jar app.jar
COPY --from=build /app/config.properties config.properties

# Create directory for database (if using file-based DB)
RUN mkdir -p /app/data

EXPOSE 8080

# Health check
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
    CMD curl -f http://localhost:8080/api/health || exit 1

# Use correct main class: com.trading.bot.TradingBot
ENTRYPOINT ["java", "--enable-preview", "-jar", "app.jar"]
