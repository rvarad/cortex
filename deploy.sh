#!/bin/bash
set -e

# ==========================================
# CONFIGURATION
# ==========================================
# GCP Project & Region (Auto-detected from VM environment)
PROJECT_ID=$(gcloud config get-value project)
REGION="asia-south1" # You can also auto-detect this if you want, but hardcoding region is usually fine.
DB_INSTANCE_CONNECTION_NAME="${PROJECT_ID}:${REGION}:cortex-prod-db"
GCS_BUCKET="cortex-prod-bucket"

# Kafka (Aiven)
# KAFKA_BOOTSTRAP_SERVER will be fetched from secrets

# Secrets (Names in Secret Manager)
SECRET_KAFKA_URL="aiven-kafka-bootstrap-servers"
SECRET_DB_PASS="cortex-prod-db-password"
SECRET_GROQ_KEY="cortex-groq-api-key"
SECRET_GEMINI_KEY="cortex-gemini-api-key"
SECRET_KAFKA_CA="aiven-kafka-ca-cert"
SECRET_KAFKA_CERT="aiven-kafka-service-cert"
SECRET_KAFKA_KEY="aiven-kafka-service-key"

# ==========================================
# 1. FETCH SECRETS & SETUP SSL
# ==========================================
echo "🔐 Fetching secrets..."
mkdir -p certs

# Helper to fetch secret
fetch_secret() {
    gcloud secrets versions access latest --secret="$1"
}

# Fetch Certificates
fetch_secret "$SECRET_KAFKA_CA" > certs/ca.pem
fetch_secret "$SECRET_KAFKA_CERT" > certs/service.cert
fetch_secret "$SECRET_KAFKA_KEY" > certs/service.key

echo "🛠️ Converting certificates to Java KeyStore..."

# Create TrustStore (CA Cert)
rm -f certs/truststore.jks
keytool -import -noprompt -alias ca -file certs/ca.pem -keystore certs/truststore.jks -storepass changeit

# Create KeyStore (Client Cert + Key)
rm -f certs/keystore.p12 certs/keystore.jks

# Convert PEM -> PKCS12
openssl pkcs12 -export -in certs/service.cert -inkey certs/service.key \
    -out certs/keystore.p12 -name service -CAfile certs/ca.pem -caname root -passout pass:changeit

# Convert PKCS12 -> JKS
keytool -importkeystore -deststorepass changeit -destkeypass changeit -destkeystore certs/keystore.jks \
    -srckeystore certs/keystore.p12 -srcstoretype PKCS12 -srcstorepass changeit -alias service

# Cleanup raw keys
rm certs/service.key certs/service.cert certs/keystore.p12

# ==========================================
# 2. EXPORT ENVIRONMENT VARIABLES
# ==========================================
export KAFKA_BOOTSTRAP_SERVERS=$(fetch_secret "$SECRET_KAFKA_URL")
export DB_PASSWORD=$(fetch_secret "$SECRET_DB_PASS")
export GROQ_API_KEY=$(fetch_secret "$SECRET_GROQ_KEY")
export GEMINI_API_KEY=$(fetch_secret "$SECRET_GEMINI_KEY")

export GCP_PROJECT_ID=$PROJECT_ID
export GCP_LOCATION=$REGION
export GCS_BUCKET_NAME=$GCS_BUCKET
export DB_INSTANCE_CONNECTION_NAME=$DB_INSTANCE_CONNECTION_NAME
# KAFKA_BOOTSTRAP_SERVERS is already exported above

# SSL Paths (Inside Docker)
export KAFKA_SSL_TRUSTSTORE_LOCATION=/etc/kafka/secrets/truststore.jks
export KAFKA_SSL_KEYSTORE_LOCATION=/etc/kafka/secrets/keystore.jks
export KAFKA_SSL_KEY_PASSWORD=changeit
export KAFKA_SSL_KEYSTORE_PASSWORD=changeit
export KAFKA_SSL_TRUSTSTORE_PASSWORD=changeit

# ==========================================
# 3. DEPLOY
# ==========================================

echo "🚀 Starting Docker containers..."
# --build ensures the multi-stage build happens inside Docker
docker-compose -f docker-compose.prod.yaml up -d --build --remove-orphans

echo "✅ Deployment Complete!"
