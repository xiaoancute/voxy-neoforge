#!/usr/bin/env bash
set -euo pipefail

smoke_profile="${VOXY_CLIENT_SMOKE_PROFILE:-sodium}"
timeout_seconds="${VOXY_CLIENT_SMOKE_TIMEOUT:-180}"
log_file="${VOXY_CLIENT_SMOKE_LOG:-build/client-smoke/${smoke_profile}/runClient.log}"
marker="Voxy client initialization completed"

case "$smoke_profile" in
    sodium)
        copy_task="copyClientSmokeMods"
        ;;
    sodium-iris)
        copy_task="copyClientSmokeIrisMods"
        ;;
    *)
        echo "Unknown VOXY_CLIENT_SMOKE_PROFILE: ${smoke_profile}"
        exit 2
        ;;
esac

mkdir -p "$(dirname "$log_file")"
rm -f "$log_file"

echo "Starting NeoForge client smoke test"
echo "Profile: ${smoke_profile}"
echo "Timeout: ${timeout_seconds}s"
echo "Log: ${log_file}"

echo "Installing smoke mod dependencies"
if ! ./gradlew "$copy_task" --console=plain >"$log_file" 2>&1; then
    echo "Failed to install smoke mod dependencies"
    tail -200 "$log_file"
    exit 1
fi

set +e
xvfb-run -a ./gradlew runClient --console=plain >>"$log_file" 2>&1 &
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

    if grep -Eiq "(NoClassDefFoundError|Mixin apply failed|InvalidMixinException|Failed to start Minecraft|Crash report saved)" "$log_file"; then
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
