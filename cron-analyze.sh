#!/usr/bin/env bash

set -euo pipefail

BACKEND_URL="${BACKEND_URL:?BACKEND_URL env var is required}"
ADMIN_USERNAME="${ADMIN_USERNAME:?ADMIN_USERNAME env var is required}"
ADMIN_PASSWORD="${ADMIN_PASSWORD:?ADMIN_PASSWORD env var is required}"
NORAD_ID="${NORAD_ID:-1245}"

MAX_WAKE_ATTEMPTS=20
WAKE_INTERVAL=15

echo "Orbit Daily Job — $(date -u '+%Y-%m-%d %H:%M:%S UTC')"
echo "Target satellite: NORAD ${NORAD_ID}"
echo "Backend: ${BACKEND_URL}"

echo ""
echo "[1/4] Waking up backend..."
attempt=0
until curl -sf --max-time 10 "${BACKEND_URL}/actuator/health" > /dev/null 2>&1; do
    attempt=$((attempt + 1))
    if [ "$attempt" -ge "$MAX_WAKE_ATTEMPTS" ]; then
        echo "ERROR: Backend did not respond after $((MAX_WAKE_ATTEMPTS * WAKE_INTERVAL))s. Aborting."
        exit 1
    fi
    echo "  Waiting for backend... (attempt ${attempt}/${MAX_WAKE_ATTEMPTS})"
    sleep "$WAKE_INTERVAL"
done
echo "  Backend is up!"

echo ""
echo "[2/4] Authenticating as '${ADMIN_USERNAME}'..."
LOGIN_RESPONSE=$(curl -sf --max-time 15 \
    -X POST "${BACKEND_URL}/api/auth/login" \
    -H "Content-Type: application/json" \
    -d "{\"username\":\"${ADMIN_USERNAME}\",\"password\":\"${ADMIN_PASSWORD}\"}" \
    || { echo "ERROR: Login request failed"; exit 1; })

TOKEN=$(echo "$LOGIN_RESPONSE" | jq -r '.token // empty')
if [ -z "$TOKEN" ]; then
    echo "ERROR: Authentication failed. Response: ${LOGIN_RESPONSE}"
    exit 1
fi
echo "  Authenticated successfully."

echo ""
echo "[3/4] Fetching latest TLEs from Space-Track..."
TLE_RESPONSE=$(curl -sf --max-time 120 \
    -X POST "${BACKEND_URL}/api/tle/fetch/all" \
    -H "Authorization: Bearer ${TOKEN}" \
    -H "Content-Type: application/json" \
    || { echo "ERROR: TLE fetch request failed"; exit 1; })

echo "  TLE fetch result: ${TLE_RESPONSE}"

echo ""
echo "[4/4] Triggering conjunction analysis for NORAD ${NORAD_ID}..."
ANALYSIS_RESPONSE=$(curl -sf --max-time 300 \
    -X POST "${BACKEND_URL}/api/conjunction/analyze/${NORAD_ID}" \
    -H "Authorization: Bearer ${TOKEN}" \
    -H "Content-Type: application/json" \
    || { echo "ERROR: Analysis request failed"; exit 1; })

echo ""
echo "Analysis result:"
echo "$ANALYSIS_RESPONSE" | jq . 2>/dev/null || echo "$ANALYSIS_RESPONSE"
echo "Completed at $(date -u '+%Y-%m-%d %H:%M:%S UTC')"