# Build Stage
FROM ghcr.io/graalvm/jdk-community:25 AS build
WORKDIR /app

# Oracle Linux 9 uses microdnf. Download Maven manually to avoid extra JDK dependencies.
RUN microdnf install -y tar gzip curl ca-certificates && \
    curl -o maven.tar.gz https://archive.apache.org/dist/maven/maven-3/3.9.6/binaries/apache-maven-3.9.6-bin.tar.gz && \
    mkdir -p /opt/maven && \
    tar -xzf maven.tar.gz -C /opt/maven --strip-components=1 && \
    rm maven.tar.gz

ENV PATH=/opt/maven/bin:$PATH

# Verify only 1 JDK exists (should be 25)
RUN java -version

COPY . .
RUN mvn -B clean package -DskipTests

# Run Stage
FROM ghcr.io/graalvm/jdk-community:25
WORKDIR /app
COPY --from=build /app/target/alpaca-bot-core-1.0-SNAPSHOT.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
