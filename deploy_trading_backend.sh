#!/bin/bash
set -e

# Colors
GREEN='\033[0;32m'
BLUE='\033[0;34m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
NC='\033[0m'

echo -e "${BLUE}🚀 Trading Backend - Production Deployer${NC}"
echo -e "${RED}⚠️  WARNING: LIVE TRADING MODE - Real money at risk!${NC}"
echo "----------------------------------------"

# 1. Check Prerequisites
echo -e "${BLUE}📋 Checking prerequisites...${NC}"

# Check Java
if ! command -v java &> /dev/null; then
    echo -e "${RED}❌ Java not found${NC}"
    exit 1
fi
JAVA_VERSION=$(java -version 2>&1 | head -1 | cut -d'"' -f2 | cut -d'.' -f1)
if [ "$JAVA_VERSION" -lt "25" ]; then
    echo -e "${RED}❌ Java 25+ required (found: $JAVA_VERSION)${NC}"
    exit 1
fi
echo -e "${GREEN}✅ Java $JAVA_VERSION${NC}"

# Check Maven
if ! command -v mvn &> /dev/null; then
    echo -e "${RED}❌ Maven not found${NC}"
    exit 1
fi
echo -e "${GREEN}✅ Maven$(mvn --version | head -1 | cut -d' ' -f3)${NC}"

# Check gcloud
GCLOUD_BIN="gcloud"
if ! command -v gcloud &> /dev/null; then
    echo -e "${RED}❌ gcloud CLI not found${NC}"
    echo "Install: brew install --cask google-cloud-sdk"
    exit 1
fi
echo -e "${GREEN}✅ gcloud CLI${NC}"

# 2. Configuration
echo -e "${BLUE}📋 Configuration${NC}"
PROJECT_ID=${GOOGLE_CLOUD_PROJECT:-"crazy-java-bot-2026"}
REGION=${REGION:-"us-central1"}
REPO_NAME="algo-bot-repo"

echo "Project: $PROJECT_ID"
echo "Region: $REGION"

$GCLOUD_BIN config set project $PROJECT_ID

# 3. Load API Keys
echo -e "${BLUE}🔑 Loading API keys...${NC}"
CONFIG_FILE="trading-backend/config.properties"

if [ ! -f "$CONFIG_FILE" ]; then
    echo -e "${RED}❌ Config file not found: $CONFIG_FILE${NC}"
    exit 1
fi

# Load keys
ALPACA_KEY=$(grep "ALPACA_API_KEY" $CONFIG_FILE | cut -d'=' -f2 | xargs)
ALPACA_SECRET=$(grep "ALPACA_API_SECRET" $CONFIG_FILE | cut -d'=' -f2 | xargs)
KRAKEN_KEY=$(grep "KRAKEN_API_KEY" $CONFIG_FILE | cut -d'=' -f2-)
KRAKEN_SECRET=$(grep "KRAKEN_API_SECRET" $CONFIG_FILE | cut -d'=' -f2-)

if [ -z "$ALPACA_KEY" ]; then
    echo -e "${RED}❌ ALPACA_API_KEY not found in config${NC}"
    exit 1
fi

echo -e "${GREEN}✅ Alpaca API key loaded${NC}"
if [ -n "$KRAKEN_KEY" ]; then
    echo -e "${GREEN}✅ Kraken API key loaded${NC}"
fi

# 4. Build Frontend & Embed
echo -e "${BLUE}⚛️  Building React Dashboard...${NC}"
cd trading-backend/dashboard
if [ ! -d "node_modules" ]; then
    npm install
fi
npm run build
cd ../..

echo -e "${BLUE}📦 Embedding Dashboard into Backend...${NC}"
mkdir -p trading-backend/src/main/resources/public
# Clean old files
rm -rf trading-backend/src/main/resources/public/*
# Copy new build
cp -r trading-backend/dashboard/dist/* trading-backend/src/main/resources/public/

# 5. Build JAR
echo -e "${BLUE}☕ Building production JAR...${NC}"
cd trading-backend

export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-25.jdk/Contents/Home
mvn clean package -DskipTests

if [ ! -f "target/trading-backend-1.0-SNAPSHOT.jar" ]; then
    echo -e "${RED}❌ JAR build failed${NC}"
    exit 1
fi

JAR_SIZE=$(du -h target/trading-backend-1.0-SNAPSHOT.jar | cut -f1)
echo -e "${GREEN}✅ JAR built successfully ($JAR_SIZE)${NC}"

# 5. Enable Cloud Services
echo -e "${BLUE}🔧 Enabling Cloud APIs...${NC}"
$GCLOUD_BIN services enable artifactregistry.googleapis.com run.googleapis.com cloudbuild.googleapis.com

# 6. Create Artifact Repository
if ! $GCLOUD_BIN artifacts repositories describe $REPO_NAME --location=$REGION &>/dev/null; then
    echo "Creating artifact repository..."
    $GCLOUD_BIN artifacts repositories create $REPO_NAME \
        --repository-format=docker \
        --location=$REGION
fi

# 7. Build and Push Docker Image
echo -e "${BLUE}🐳 Building Docker image...${NC}"
$GCLOUD_BIN builds submit --tag $REGION-docker.pkg.dev/$PROJECT_ID/$REPO_NAME/trading-backend:latest .

# 8. Deploy to Cloud Run
echo -e "${BLUE}🚀 Deploying to Cloud Run...${NC}"
echo -e "${RED}⚠️  LIVE TRADING MODE - Real money at risk!${NC}"
sleep 3

$GCLOUD_BIN run deploy trading-backend \
    --image $REGION-docker.pkg.dev/$PROJECT_ID/$REPO_NAME/trading-backend:latest \
    --platform managed \
    --region $REGION \
    --allow-unauthenticated \
    --memory 2Gi \
    --cpu 2 \
    --timeout 3600 \
    --min-instances 1 \
    --max-instances 1 \
    --set-env-vars "ALPACA_API_KEY=$ALPACA_KEY,ALPACA_API_SECRET=$ALPACA_SECRET,KRAKEN_API_KEY=$KRAKEN_KEY,KRAKEN_API_SECRET=$KRAKEN_SECRET,JAVA_TOOL_OPTIONS=-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0"

# 9. Get Service URL
BACKEND_URL=$($GCLOUD_BIN run services describe trading-backend --platform managed --region $REGION --format 'value(status.url)')

echo "----------------------------------------"
echo -e "${GREEN}🎉 Deployment Complete!${NC}"
echo -e "Backend URL: ${BLUE}$BACKEND_URL${NC}"
echo -e "Dashboard: ${BLUE}$BACKEND_URL${NC} (embedded)"
echo "----------------------------------------"
echo -e "${YELLOW}📊 Next Steps:${NC}"
echo "1. Test health: curl $BACKEND_URL/health"
echo "2. Access dashboard: open $BACKEND_URL"
echo "3. Monitor logs: gcloud logging read \"resource.type=cloud_run_revision AND resource.labels.service_name=trading-backend\" --limit 50"
echo "4. Watch for 1 hour before enabling full trading"
echo "----------------------------------------"
echo -e "${RED}⚠️  REMEMBER: This is LIVE trading with real money!${NC}"
echo -e "${RED}⚠️  Start with small positions and monitor closely!${NC}"
echo "----------------------------------------"

cd ..
