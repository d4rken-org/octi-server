#!/bin/bash
#
# Octi Server Entrypoint Script
#
# Environment variables:
# - OCTI_PORT: Server port (default: 8080)
# - OCTI_DEBUG: Enable debug mode (default: false)
# - OCTI_DATA_DIR: Override data directory path (default: auto-detect)
# - OCTI_TRUSTED_PROXY_IPS: Comma-separated trusted proxy IPs (default: loopback)
#
# Default data path: /etc/octi-server
# Deprecated path: /etc/octi-sync-server (still supported via auto-detection)
#

set -e

# Process environment variables
OCTI_PORT=${OCTI_PORT:-8080}
OCTI_DEBUG=${OCTI_DEBUG:-false}

# Validate port
if ! [[ "$OCTI_PORT" =~ ^[0-9]+$ ]] || [ "$OCTI_PORT" -lt 1 ] || [ "$OCTI_PORT" -gt 65535 ]; then
    echo "Error: Invalid port number. Must be between 1-65535"
    exit 1
fi

# Resolve data directory
OLD_DATA_DIR="/etc/octi-sync-server"
NEW_DATA_DIR="/etc/octi-server"

if [ -n "$OCTI_DATA_DIR" ]; then
    DATA_DIR="$OCTI_DATA_DIR"
    echo "Using data directory from OCTI_DATA_DIR: $DATA_DIR"
elif mountpoint -q "$OLD_DATA_DIR" 2>/dev/null && mountpoint -q "$NEW_DATA_DIR" 2>/dev/null; then
    echo "ERROR: Both $OLD_DATA_DIR and $NEW_DATA_DIR are mounted. Set OCTI_DATA_DIR to choose." >&2
    exit 1
elif mountpoint -q "$OLD_DATA_DIR" 2>/dev/null; then
    echo "WARNING: $OLD_DATA_DIR is deprecated, please migrate your volume mount to $NEW_DATA_DIR" >&2
    DATA_DIR="$OLD_DATA_DIR"
else
    DATA_DIR="$NEW_DATA_DIR"
fi

# Build command arguments
CMD_ARGS=("--datapath=$DATA_DIR" "--port=$OCTI_PORT")

# Add debug flag if enabled
if [ "$OCTI_DEBUG" = "true" ]; then
    CMD_ARGS+=("--debug")
fi

add_optional_arg() {
    local env_name="$1"
    local flag_name="$2"
    local value="${!env_name:-}"
    if [ -n "$value" ]; then
        CMD_ARGS+=("$flag_name=$value")
    fi
}

add_optional_arg OCTI_ACCOUNT_QUOTA_MB "--account-quota-mb"
add_optional_arg OCTI_MAX_BLOB_MB "--max-blob-mb"
add_optional_arg OCTI_MAX_MODULE_DOCUMENT_KB "--max-module-document-kb"
add_optional_arg OCTI_MAX_BLOB_PATCH_KB "--max-blob-patch-kb"
add_optional_arg OCTI_MIN_FREE_DISK_MB "--min-free-disk-mb"
add_optional_arg OCTI_MAX_UPLOAD_SESSIONS_PER_DEVICE "--max-upload-sessions-per-device"
add_optional_arg OCTI_MAX_UPLOAD_SESSIONS_PER_ACCOUNT "--max-upload-sessions-per-account"
add_optional_arg OCTI_IDLE_SESSION_TTL_SECONDS "--idle-session-ttl-seconds"
add_optional_arg OCTI_COMPLETE_IDLE_SESSION_TTL_SECONDS "--complete-idle-session-ttl-seconds"
add_optional_arg OCTI_ABSOLUTE_SESSION_TTL_SECONDS "--absolute-session-ttl-seconds"
add_optional_arg OCTI_MAX_DEVICES_PER_ACCOUNT "--max-devices-per-account"
add_optional_arg OCTI_MAX_MODULES_PER_DEVICE "--max-modules-per-device"
add_optional_arg OCTI_MAX_BLOB_REFS_PER_MODULE "--max-blob-refs-per-module"
add_optional_arg OCTI_ACCOUNT_RATE_LIMIT "--account-rate-limit"
add_optional_arg OCTI_ACCOUNT_RATE_LIMIT_WINDOW_SECONDS "--account-rate-limit-window-seconds"
add_optional_arg OCTI_RATE_LIMIT "--rate-limit"
add_optional_arg OCTI_RATE_LIMIT_WINDOW_SECONDS "--rate-limit-window-seconds"
add_optional_arg OCTI_PAYLOAD_LIMIT_KB "--payload-limit-kb"
add_optional_arg OCTI_TRUSTED_PROXY_IPS "--trusted-proxy-ips"

if [ "${OCTI_DISABLE_RATE_LIMITS:-false}" = "true" ]; then
    CMD_ARGS+=("--disable-rate-limits")
fi

# Execute the application
exec ./bin/octi-server "${CMD_ARGS[@]}"
