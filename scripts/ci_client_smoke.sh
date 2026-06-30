#!/usr/bin/env bash
set -euo pipefail

timeout_seconds="${VOXY_CLIENT_SMOKE_TIMEOUT:-180}"
log_file="${VOXY_CLIENT_SMOKE_LOG:-build/client-smoke/runClient.log}"
marker="Voxy client initialization completed"

mkdir -p "$(dirname "$log_file")"
rm -f "$log_file"

echo "Starting NeoForge client smoke test"
echo "Timeout: ${timeout_seconds}s"
echo "Log: ${log_file}"

set +e
xvfb-run -a ./gradlew runClient --console=plain >"$log_file" 2>&1 &
client_pid=$!
set -e

deadline=$((SECONDS + timeout_seconds))
while kill -0 "$client_pid" 2>/dev/null; do
    if grep -q "$marker" "$log_file"; then
        echo "Smoke marker found: ${marker}"
        kill "$client_pid" 2>/dev/null || true
        wait "$client_pid" 2>/dev/null || true
        exit 0
    fi

    if grep -Eiq "(NoClassDefFoundError|ClassNotFoundException|Mixin apply failed|InvalidMixinException|Failed to start Minecraft|Crash report saved)" "$log_file"; then
        echo "Client smoke test failed before marker"
        tail -200 "$log_file"
        kill "$client_pid" 2>/dev/null || true
        wait "$client_pid" 2>/dev/null || true
        exit 1
    fi

    if (( SECONDS >= deadline )); then
        echo "Client smoke test timed out before marker"
        tail -200 "$log_file"
        kill "$client_pid" 2>/dev/null || true
        wait "$client_pid" 2>/dev/null || true
        exit 1
    fi

    sleep 2
done

set +e
wait "$client_pid"
exit_code=$?
set -e

if grep -q "$marker" "$log_file"; then
    echo "Smoke marker found: ${marker}"
    exit 0
fi

echo "Client exited before smoke marker with exit code ${exit_code}"
tail -200 "$log_file"
exit "$exit_code"
