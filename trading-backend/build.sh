#!/bin/bash
# Trading Bot Build Script
# Ensures Java 25 is used for all Maven operations

export JAVA_HOME="/Library/Java/JavaVirtualMachines/jdk-25.jdk/Contents/Home"
export PATH="$JAVA_HOME/bin:$PATH"

echo "🔧 Using Java: $(java -version 2>&1 | head -1)"
echo ""

case "${1:-build}" in
    test)
        mvn test
        ;;
    build)
        mvn clean package -DskipTests
        ;;
    run)
        mvn clean package -DskipTests && java --enable-preview -jar target/trading-backend-1.0-SNAPSHOT.jar
        ;;
    full)
        mvn clean verify
        ;;
    *)
        echo "Usage: $0 {test|build|run|full}"
        exit 1
        ;;
esac
