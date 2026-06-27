#!/bin/bash
set -e

# ==========================================
# 1. VERIFY .env FILE EXISTS
# ==========================================
if [ ! -f ../.env ]; then
    echo "❌ Error: ../.env file is missing!"
    echo "Please create a '.env' file in the parent directory (alongside cortex/) before deploying."
    echo "You can use the template below as a guide:"
    echo ""
    echo "=========================================="
    echo "# GCP"
    echo "GCP_PROJECT_ID=your-gcp-project-id"
    echo "GCP_LOCATION=asia-south1"
    echo "GCS_BUCKET_NAME=your-gcs-bucket-name"
    echo "DOMAIN_NAME=cortex-media.in"
    echo ""
    echo "# Database"
    echo "DB_PASSWORD=your-secure-db-password"
    echo ""
    echo "# Kafka"
    echo "KAFKA_BOOTSTRAP_SERVERS=your-aiven-kafka-bootstrap-servers"
    echo ""
    echo "# AI Keys"
    echo "GROQ_API_KEY=your-groq-api-key"
    echo "GEMINI_API_KEY=your-gemini-api-key"
    echo ""
    echo "# Gateway & Security"
    echo "GOOGLE_CLIENT_ID=your-google-oauth2-client-id"
    echo "GOOGLE_CLIENT_SECRET=your-google-oauth2-client-secret"
    echo "INTERNAL_JWT_SECRET=your-internal-jwt-secret"
    echo ""
    echo "# Kafka SSL (paths inside Docker containers - keep these defaults)"
    echo "KAFKA_SSL_TRUSTSTORE_LOCATION=/etc/kafka/secrets/truststore.jks"
    echo "KAFKA_SSL_KEYSTORE_LOCATION=/etc/kafka/secrets/keystore.jks"
    echo "KAFKA_SSL_KEY_PASSWORD=changeit"
    echo "KAFKA_SSL_KEYSTORE_PASSWORD=changeit"
    echo "KAFKA_SSL_TRUSTSTORE_PASSWORD=changeit"
    echo "=========================================="
    exit 1
fi

# ==========================================
# 1b. VERIFY GCP SERVICE-ACCOUNT KEY EXISTS
# ==========================================
GCP_CREDENTIALS_FILE="../gcp-credentials.json"
if [ ! -f "$GCP_CREDENTIALS_FILE" ]; then
    echo "❌ Error: GCP service-account key '$GCP_CREDENTIALS_FILE' is missing!"
    echo "The Oracle VM has no GCP metadata server, so GCS and Vertex AI require a key file."
    echo "Create a service account with these roles, download a JSON key, and save it as '$GCP_CREDENTIALS_FILE':"
    echo "  - roles/storage.objectAdmin   (GCS uploads/chunks)"
    echo "  - roles/aiplatform.user       (Vertex AI / Gemini)"
    echo "Then lock it down: chmod 600 $GCP_CREDENTIALS_FILE"
    exit 1
fi
echo "✅ GCP service-account key found."

# ==========================================
# 2. CONVERT CERTS & SETUP SSL
# ==========================================
RAW_CERT_DIR="../certs/raw"
OUTPUT_CERT_DIR="../certs"

echo "🔐 Checking for Aiven certificates..."
if [ ! -f "$RAW_CERT_DIR/ca.pem" ] || [ ! -f "$RAW_CERT_DIR/service.cert" ] || [ ! -f "$RAW_CERT_DIR/service.key" ]; then
    echo "❌ Error: One or more Aiven certificates are missing in '$RAW_CERT_DIR/'!"
    echo "Please ensure the following three files exist:"
    echo "  - $RAW_CERT_DIR/ca.pem"
    echo "  - $RAW_CERT_DIR/service.cert"
    echo "  - $RAW_CERT_DIR/service.key"
    exit 1
fi

echo "🛠️ Converting certificates to Java KeyStore..."
mkdir -p "$OUTPUT_CERT_DIR"

# Create TrustStore
rm -f "$OUTPUT_CERT_DIR/truststore.jks"
keytool -import -noprompt -alias ca -file "$RAW_CERT_DIR/ca.pem" -keystore "$OUTPUT_CERT_DIR/truststore.jks" -storepass changeit

# Create KeyStore (PEM -> PKCS12 -> JKS)
rm -f "$OUTPUT_CERT_DIR/keystore.p12" "$OUTPUT_CERT_DIR/keystore.jks"
openssl pkcs12 -export -in "$RAW_CERT_DIR/service.cert" -inkey "$RAW_CERT_DIR/service.key" \
    -out "$OUTPUT_CERT_DIR/keystore.p12" -name service -CAfile "$RAW_CERT_DIR/ca.pem" -caname root -passout pass:changeit
keytool -importkeystore -deststorepass changeit -destkeypass changeit -destkeystore "$OUTPUT_CERT_DIR/keystore.jks" \
    -srckeystore "$OUTPUT_CERT_DIR/keystore.p12" -srcstoretype PKCS12 -srcstorepass changeit -alias service

# Cleanup temporary PKCS12 file (keep raw certificates in certs/raw/ for future deployments)
rm -f "$OUTPUT_CERT_DIR/keystore.p12"

echo "✅ JKS certificates generated successfully in '$OUTPUT_CERT_DIR/'."

# ==========================================
# 3. DEPLOY
# ==========================================
echo "🚀 Starting Docker containers..."
docker compose -f docker-compose.prod.yaml --env-file ../.env up -d --build --remove-orphans

echo "✅ Deployment complete!"
