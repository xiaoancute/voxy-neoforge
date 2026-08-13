#!/usr/bin/env bash
set -euo pipefail

timeout_seconds="${VOXY_SERVER_SMOKE_TIMEOUT:-480}"
work_dir="build/server-smoke"
server_dir="${work_dir}/packaged-server"
build_log="${work_dir}/build.log"
install_log="${work_dir}/install.log"
server_log="${work_dir}/runServer.log"
status_log="${work_dir}/server-status.log"
marker="Voxy dedicated server companion initialized"
rcon_password="voxy-packaged-server-smoke"
server_pid=""

cleanup() {
    if [[ -n "$server_pid" ]]; then
        kill "$server_pid" 2>/dev/null || true
        wait "$server_pid" 2>/dev/null || true
    fi
}
trap cleanup EXIT

mkdir -p "$work_dir" "$server_dir"

echo "Building and validating the production mod JAR"
./gradlew -Pserver_distribution=true jar validateServerArtifact --console=plain >"$build_log" 2>&1

shopt -s nullglob
mod_jars=(build/libs/*.jar)
if (( ${#mod_jars[@]} != 1 )); then
    echo "Expected exactly one production mod JAR, found ${#mod_jars[@]}"
    printf '%s\n' "${mod_jars[@]}"
    exit 1
fi
mod_jar="${mod_jars[0]}"

neoforge_version="$(sed -n 's/^neoforge_version[[:space:]]*=[[:space:]]*//p' gradle.properties | tail -1)"
if [[ -z "$neoforge_version" ]]; then
    echo "Unable to read neoforge_version from gradle.properties"
    exit 1
fi

installer="${work_dir}/neoforge-${neoforge_version}-installer.jar"
installer_url="https://maven.neoforged.net/releases/net/neoforged/neoforge/${neoforge_version}/neoforge-${neoforge_version}-installer.jar"
echo "Downloading NeoForge ${neoforge_version} installer"
curl --fail --location --silent --show-error "$installer_url" --output "$installer"
expected_sha1="$(curl --fail --location --silent --show-error "${installer_url}.sha1" | tr -d '[:space:]')"
printf '%s  %s\n' "$expected_sha1" "$installer" | sha1sum --check -

installer_path="$(pwd)/${installer}"
echo "Installing a clean NeoForge dedicated server"
(
    cd "$server_dir"
    java -jar "$installer_path" --installServer
) >"$install_log" 2>&1

mkdir -p "$server_dir/mods"
cp "$mod_jar" "$server_dir/mods/voxy.jar"
printf 'eula=true\n' >"$server_dir/eula.txt"
cat >"$server_dir/server.properties" <<EOF
enable-rcon=true
level-name=world
level-seed=voxy-packaged-server-ci
level-type=minecraft:flat
online-mode=false
rcon.password=${rcon_password}
rcon.port=25575
server-port=25565
simulation-distance=3
spawn-protection=0
view-distance=3
EOF

echo "Starting packaged NeoForge dedicated server"
(
    cd "$server_dir"
    bash run.sh nogui
) >"$server_log" 2>&1 &
server_pid=$!
deadline=$((SECONDS + timeout_seconds))

while kill -0 "$server_pid" 2>/dev/null; do
    if grep -q "$marker" "$server_log" && grep -Eiq "Done \(" "$server_log"; then
        python3 scripts/ci_rcon.py 127.0.0.1 25575 "$rcon_password" "voxy server status" >"$status_log" 2>/dev/null || true
        if grep -Eq "ingested=[1-9][0-9]*" "$status_log"; then
            echo "Packaged dedicated-server smoke markers found"
            cat "$status_log"
            exit 0
        fi
    fi

    if grep -Eiq "(NoClassDefFoundError|NoSuchMethodError|UnsatisfiedLinkError|Mixin apply failed|InvalidMixinException|Failed to start|Crash report saved|Missing or unsupported mandatory dependencies|Mod loading errors)" "$server_log"; then
        echo "Packaged dedicated-server smoke test failed"
        tail -240 "$server_log"
        exit 1
    fi

    if (( SECONDS >= deadline )); then
        echo "Packaged dedicated-server smoke test timed out"
        cat "$status_log" 2>/dev/null || true
        tail -240 "$server_log"
        exit 1
    fi
    sleep 2
done

set +e
wait "$server_pid"
exit_code=$?
set -e
tail -240 "$server_log"
exit "$exit_code"
