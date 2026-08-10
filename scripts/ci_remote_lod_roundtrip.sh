#!/usr/bin/env bash
set -euo pipefail

timeout_seconds="${VOXY_REMOTE_LOD_TIMEOUT:-480}"
log_dir="${VOXY_REMOTE_LOD_LOG_DIR:-build/remote-lod-roundtrip}"
server_log="${log_dir}/runServer.log"
client_log="${log_dir}/runClient.log"
status_log="${log_dir}/server-status.log"
rcon_password="voxy-ci-roundtrip"
server_pid=""
client_pid=""

cleanup() {
    if [[ -n "$client_pid" ]]; then
        kill "$client_pid" 2>/dev/null || true
        wait "$client_pid" 2>/dev/null || true
    fi
    if [[ -n "$server_pid" ]]; then
        kill "$server_pid" 2>/dev/null || true
        wait "$server_pid" 2>/dev/null || true
    fi
}
trap cleanup EXIT

mkdir -p "$log_dir" runs/server runs/client
printf 'eula=true\n' > runs/server/eula.txt
cat > runs/client/options.txt <<EOF
onboardAccessibility:false
skipMultiplayerWarning:true
tutorialStep:none
EOF
cat > runs/server/server.properties <<EOF
allow-flight=true
enable-command-block=false
enable-rcon=true
enforce-secure-profile=false
level-name=world
level-seed=voxy-remote-lod-ci
level-type=minecraft:flat
max-players=2
online-mode=false
rcon.password=${rcon_password}
rcon.port=25575
server-port=25565
simulation-distance=3
spawn-protection=0
sync-chunk-writes=true
view-distance=3
EOF

echo "Preparing compiled classes and client smoke dependencies"
./gradlew classes copyClientSmokeMods --console=plain >"${log_dir}/prepare.log" 2>&1

echo "Starting dedicated server"
./gradlew runServer --console=plain >"$server_log" 2>&1 &
server_pid=$!
deadline=$((SECONDS + timeout_seconds))

while kill -0 "$server_pid" 2>/dev/null; do
    if grep -q "Voxy dedicated server companion initialized" "$server_log" && grep -Eiq "Done \(" "$server_log"; then
        break
    fi
    if grep -Eiq "(NoClassDefFoundError|Mixin apply failed|InvalidMixinException|Failed to start|Crash report saved|Missing or unsupported mandatory dependencies)" "$server_log"; then
        echo "Dedicated server failed before startup"
        tail -200 "$server_log"
        exit 1
    fi
    if (( SECONDS >= deadline )); then
        echo "Timed out waiting for dedicated server"
        tail -200 "$server_log"
        exit 1
    fi
    sleep 2
done
kill -0 "$server_pid" 2>/dev/null || {
    echo "Dedicated server exited before startup"
    tail -200 "$server_log"
    exit 1
}

echo "Starting client quick-play connection"
VOXY_CI_MULTIPLAYER_ADDRESS="127.0.0.1:25565" \
JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-} -Dvoxy.ci.remoteLodProbe=true" \
xvfb-run -a ./gradlew runClient --console=plain >"$client_log" 2>&1 &
client_pid=$!

probe_block=""
while kill -0 "$client_pid" 2>/dev/null && kill -0 "$server_pid" 2>/dev/null; do
    probe_block="$(sed -n 's/.*VOXY_REMOTE_LOD_PROBE stored key=[^ ]* block=\([-0-9]*,[-0-9]*,[-0-9]*\).*/\1/p' "$client_log" | tail -1)"
    if [[ -n "$probe_block" ]]; then
        break
    fi
    if grep -q "VOXY_REMOTE_LOD_PROBE failed" "$client_log"; then
        echo "Remote LOD client probe failed before cache write"
        tail -240 "$client_log"
        exit 1
    fi
    if grep -Eiq "(Failed to locate library: liblwjgl|Exception in thread \"Dedicated Voxy Worker)" "$server_log"; then
        echo "Dedicated server failed while generating remote LOD data"
        tail -240 "$server_log"
        exit 1
    fi
    if grep -Eiq "(NoClassDefFoundError|Mixin apply failed|InvalidMixinException|Failed to start Minecraft|Crash report saved|Connection refused|Failed to connect)" "$client_log"; then
        echo "Client failed before remote LOD cache write"
        tail -240 "$client_log"
        exit 1
    fi
    if (( SECONDS >= deadline )); then
        echo "Timed out waiting for remote LOD cache write"
        tail -240 "$client_log"
        exit 1
    fi
    sleep 2
done

if [[ -z "$probe_block" ]]; then
    echo "Client or server exited before the remote LOD cache write"
    tail -240 "$client_log"
    exit 1
fi

IFS=',' read -r block_x block_y block_z <<<"$probe_block"
echo "Mutating served section at ${block_x},${block_y},${block_z}"
python3 scripts/ci_rcon.py 127.0.0.1 25575 "$rcon_password" \
    "setblock ${block_x} ${block_y} ${block_z} minecraft:diamond_block replace" \
    >"${log_dir}/setblock.log"

while kill -0 "$client_pid" 2>/dev/null && kill -0 "$server_pid" 2>/dev/null; do
    if grep -q "VOXY_REMOTE_LOD_PROBE success" "$client_log"; then
        python3 scripts/ci_rcon.py 127.0.0.1 25575 "$rcon_password" "voxy server status" >"$status_log"
        if ! grep -Eq "updatedSections=[1-9][0-9]*" "$status_log"; then
            echo "Server did not report a live section update"
            cat "$status_log"
            exit 1
        fi
        if ! grep -Eq "served=([2-9]|[1-9][0-9]+)" "$status_log"; then
            echo "Server did not serve both initial and refreshed LOD data"
            cat "$status_log"
            exit 1
        fi
        if ! grep -Eq "invalidationPackets=[1-9][0-9]*" "$status_log"; then
            echo "Server did not send an invalidation packet"
            cat "$status_log"
            exit 1
        fi
        echo "Remote LOD roundtrip markers found"
        cat "$status_log"
        exit 0
    fi
    if grep -q "VOXY_REMOTE_LOD_PROBE failed" "$client_log"; then
        echo "Remote LOD client probe failed after block mutation"
        tail -240 "$client_log"
        exit 1
    fi
    if (( SECONDS >= deadline )); then
        echo "Timed out waiting for invalidation refresh"
        tail -240 "$client_log"
        tail -240 "$server_log"
        exit 1
    fi
    sleep 2
done

echo "Client or server exited before the invalidation refresh"
tail -240 "$client_log"
tail -240 "$server_log"
exit 1
