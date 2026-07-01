#!/usr/bin/env bash
set -euo pipefail

smoke_profile="${VOXY_CLIENT_SMOKE_PROFILE:-sodium}"
timeout_seconds="${VOXY_CLIENT_SMOKE_TIMEOUT:-180}"
log_file="${VOXY_CLIENT_SMOKE_LOG:-build/client-smoke/${smoke_profile}/runClient.log}"
marker="Voxy client initialization completed"

case "$smoke_profile" in
    sodium-min)
        copy_task="copyClientSmokeMinSodiumMods"
        ;;
    sodium-min-iris)
        copy_task="copyClientSmokeMinSodiumIrisMods"
        ;;
    sodium)
        copy_task="copyClientSmokeMods"
        ;;
    sodium-iris|sodium-iris-shaderpack)
        copy_task="copyClientSmokeIrisMods"
        ;;
    *)
        echo "Unknown VOXY_CLIENT_SMOKE_PROFILE: ${smoke_profile}"
        exit 2
        ;;
esac

shaderpack_name="voxy-ci-empty"
shaderpack_marker=""
if [[ "$smoke_profile" == "sodium-iris-shaderpack" ]]; then
    shaderpack_marker="Using shaderpack: ${shaderpack_name}"
fi

markers_found() {
    grep -q "$marker" "$log_file" || return 1

    if [[ -n "$shaderpack_marker" ]]; then
        grep -q "$shaderpack_marker" "$log_file" || return 1
    fi
}

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

if [[ "$smoke_profile" == "sodium-iris-shaderpack" ]]; then
    echo "Preparing Iris smoke shaderpack" >>"$log_file"
    mkdir -p "runs/client/shaderpacks/${shaderpack_name}/shaders"
    mkdir -p "runs/client/config"
    cat >"runs/client/config/iris.properties" <<EOF
shaderPack=${shaderpack_name}
enableShaders=true
allowUnknownShaders=false
enableDebugOptions=false
disableUpdateMessage=true
maxShadowRenderDistance=32
colorSpace=SRGB
EOF
fi

set +e
xvfb-run -a ./gradlew runClient --console=plain >>"$log_file" 2>&1 &
client_pid=$!
set -e

deadline=$((SECONDS + timeout_seconds))
while kill -0 "$client_pid" 2>/dev/null; do
    if markers_found; then
        echo "Smoke markers found"
        kill "$client_pid" 2>/dev/null || true
        wait "$client_pid" 2>/dev/null || true
        exit 0
    fi

    if grep -Eiq "(NoClassDefFoundError|Mixin apply failed|InvalidMixinException|Failed to start Minecraft|Crash report saved|Failed to load the shaderpack|Could not load the shaderpack|Falling back to normal rendering without shaders)" "$log_file"; then
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

if markers_found; then
    echo "Smoke markers found"
    exit 0
fi

echo "Client exited before smoke marker with exit code ${exit_code}"
tail -200 "$log_file"
exit "$exit_code"
