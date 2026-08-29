#!/usr/bin/env sh
set -eu

load_secret() {
    secret_file="$1"
    environment_name="$2"

    if [ ! -r "$secret_file" ]; then
        echo "Missing unreadable secret file for $environment_name" >&2
        exit 1
    fi

    secret_value="$(cat "$secret_file")"
    if [ -z "$secret_value" ]; then
        echo "Secret file for $environment_name is empty" >&2
        exit 1
    fi

    export "$environment_name=$secret_value"
}

if [ -z "${OPENAI_API_KEY:-}" ]; then
    load_secret "${OPENAI_API_KEY_FILE:-}" "OPENAI_API_KEY"
fi

if [ -z "${FIREBASE_SERVICE_ACCOUNT_JSON:-}" ]; then
    load_secret "${FIREBASE_SERVICE_ACCOUNT_FILE:-}" "FIREBASE_SERVICE_ACCOUNT_JSON"
fi

exec "$@"
