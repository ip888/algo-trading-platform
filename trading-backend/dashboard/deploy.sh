#!/bin/bash
set -e

echo "🚀 Deploying Trading Dashboard to Cloud Run..."
echo "----------------------------------------"

# Configuration
PROJECT_ID="crazy-java-bot-2026"
REGION="us-central1"
SERVICE_NAME="trading-dashboard"
BACKEND_URL="https://trading-backend-281335928142.us-central1.run.app"

# Navigate to dashboard directory
cd "$(dirname "$0")"

echo "📦 Building React application..."
npm run build

echo "🐳 Building and deploying Docker image..."
gcloud run deploy $SERVICE_NAME \
  --source . \
  --region $REGION \
  --project $PROJECT_ID \
  --platform managed \
  --allow-unauthenticated \
  --port 8080 \
  --memory 256Mi \
  --cpu 1 \
  --min-instances 0 \
  --max-instances 10 \
  --set-env-vars="BACKEND_URL=$BACKEND_URL"

echo "----------------------------------------"
echo "🎉 Dashboard Deployment Complete!"
echo "Dashboard URL: $(gcloud run services describe $SERVICE_NAME --region $REGION --project $PROJECT_ID --format='value(status.url)')"
echo "----------------------------------------"
