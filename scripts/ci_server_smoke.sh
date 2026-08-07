#!/usr/bin/env bash
set -euo pipefail

timeout_seconds="${VOXY_SERVER_SMOKE_TIMEOUT:-240}"
log_file="${VOXY_SERVER_SMOKE_LOG:-build/server-smoke/runServer.log}"
marker="Voxy dedicated server companion initialized"

mkdir -p "$(dirname "$log_file")" "runs/server"
printf 'eula=true\n' > runs/server/eula.txt
rm -f "$log_file"

echo "Starting NeoForge dedicated-server smoke test"
echo "Timeout: ${timeout_seconds}s"
echo "Log: ${log_file}"

set +e
./gradlew runServer --console=plain >"$log_file" 2>&1 &
server_pid=$!
set -e

deadline=$((SECONDS + timeout_seconds))
while kill -0 "$server_pid" 2>/dev/null; do
    if grep -q "$marker" "$log_file" && grep -Eiq "Done \(" "$log_file"; then
        echo "Dedicated-server smoke markers found"
        kill "$server_pid" 2>/dev/null || true
        wait "$server_pid" 2>/dev/null || true
        exit 0
    fi

    if grep -Eiq "(NoClassDefFoundError|Mixin apply failed|InvalidMixinException|Failed to start|Crash report saved|Missing or unsupported mandatory dependencies)" "$log_file"; then
        echo "Dedicated-server smoke test failed before marker"
        tail -200 "$log_file"
        kill "$server_pid" 2>/dev/null || true
        wait "$server_pid" 2>/dev/null || true
        exit 1
    fi

    if (( SECONDS >= deadline )); then
        echo "Dedicated-server smoke test timed out"
        tail -200 "$log_file"
        kill "$server_pid" 2>/dev/null || true
        wait "$server_pid" 2>/dev/null || true
        exit 1
    fi
    sleep 2
done

set +e
wait "$server_pid"
exit_code=$?
set -e
tail -200 "$log_file"
exit "$exit_code"
