# Build Stage
FROM ghcr.io/graalvm/jdk-community:25 AS build
WORKDIR /app

# Oracle Linux 9 uses microdnf. Download Maven manually to avoid extra JDK dependencies.
# Also install Node.js for building the dashboard
RUN microdnf install -y tar gzip curl ca-certificates nodejs npm && \
    curl -o maven.tar.gz https://archive.apache.org/dist/maven/maven-3/3.9.6/binaries/apache-maven-3.9.6-bin.tar.gz && \
    mkdir -p /opt/maven && \
    tar -xzf maven.tar.gz -C /opt/maven --strip-components=1 && \
    rm maven.tar.gz

ENV PATH=/opt/maven/bin:$PATH

# Verify only 1 JDK exists (should be 25)
RUN java -version

# Copy everything
COPY . .

# Build dashboard FIRST, then copy to resources for embedding in JAR
RUN cd dashboard && npm ci && npm run build && \
    mkdir -p ../src/main/resources/public && \
    cp -r dist/* ../src/main/resources/public/

# Now build JAR with embedded dashboard
RUN mvn -B clean package -DskipTests

# Run Stage
FROM ghcr.io/graalvm/jdk-community:25
WORKDIR /app

# Install curl for healthchecks
RUN microdnf install -y curl && microdnf clean all

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
