#!/usr/bin/env bash
# Requires Bash, curl and a JDK 21 (JAVA_HOME or JAVA). Accepts target names as arguments.
set -euo pipefail

root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
smoke="$root/build/smoke"
cache="$smoke/cache"
default_java=${JAVA:-${JAVA_HOME:+$JAVA_HOME/bin/}java}
startup_timeout=${SMOKE_TIMEOUT:-240}
jar_path=${JPI_MC_JAR:-$root/minecraft/build/libs/jpi-mini-mc-0.1.0.jar}
mkdir -p "$cache/libraries"
if [[ ! -f "$jar_path" ]]; then
    echo "Build the mod first: ./gradlew :minecraft:jar" >&2
    exit 1
fi
jar_path=$(cd "$(dirname "$jar_path")" && pwd)/$(basename "$jar_path")
server_pid=

cleanup() {
    if [[ -n "$server_pid" ]] && kill -0 "$server_pid" 2>/dev/null; then
        kill "$server_pid" 2>/dev/null || true
        wait "$server_pid" 2>/dev/null || true
    fi
}
trap cleanup EXIT
trap 'exit 130' INT TERM

download() {
    local url=$1 destination=$2
    if [[ ! -s "$destination" ]]; then
        curl --fail --location --retry 3 --connect-timeout 30 --max-time 600 \
            "$url" -o "$destination.part" || return 1
        mv "$destination.part" "$destination" || return 1
    fi
}

# These endpoints return flat version records in newest-first order.
latest_stable() {
    awk '/"version"[[:space:]]*:/ {v=$0; sub(/.*"version"[[:space:]]*:[[:space:]]*"/, "", v);
        sub(/".*/, "", v)} /"stable"[[:space:]]*:[[:space:]]*true/ {print v; exit}' "$1"
}

install_server() {
    local target=$1 mc=$2 kind=$3 directory=$4 version installer
    if [[ "$kind" == fabric ]]; then
        curl --fail --silent --show-error --location --retry 3 --max-time 120 \
            https://meta.fabricmc.net/v2/versions/loader -o "$cache/fabric-loaders.json" || return 1
        curl --fail --silent --show-error --location --retry 3 --max-time 120 \
            https://meta.fabricmc.net/v2/versions/installer -o "$cache/fabric-installers.json" || return 1
        version=$(latest_stable "$cache/fabric-loaders.json")
        installer=$(latest_stable "$cache/fabric-installers.json")
        [[ -n "$version" && -n "$installer" ]] || return 1
        download "https://meta.fabricmc.net/v2/versions/loader/$mc/$version/$installer/server/jar" \
            "$cache/fabric-$mc-$version-$installer.jar" || return 1
        cp "$cache/fabric-$mc-$version-$installer.jar" "$directory/server-launch.jar" || return 1
        if [[ ! -s "$cache/minecraft-$mc.jar" \
            && -s "$cache/libraries/net/minecraft/server/$mc/server-$mc.jar" ]]; then
            cp "$cache/libraries/net/minecraft/server/$mc/server-$mc.jar" "$cache/minecraft-$mc.jar" || return 1
        fi
        launch=("-Dfabric.installer.server.gameJar=$cache/minecraft-$mc.jar" -jar server-launch.jar nogui)
    else
        if [[ "$kind" == neoforge ]]; then
            version=21.1.233
            installer="neoforge-$version-installer.jar"
            download "https://maven.neoforged.net/releases/net/neoforged/neoforge/$version/$installer" \
                "$cache/$installer" || return 1
            launch=("@libraries/net/neoforged/neoforge/$version/unix_args.txt" nogui)
        else
            curl --fail --silent --show-error --location --retry 3 --max-time 120 \
                https://files.minecraftforge.net/net/minecraftforge/forge/promotions_slim.json \
                -o "$cache/forge-promotions.json" || return 1
            version=$(sed -n 's/.*"1.20.1-latest"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' \
                "$cache/forge-promotions.json")
            [[ "$version" == 47.* ]] || return 1
            installer="forge-$mc-$version-installer.jar"
            download "https://maven.minecraftforge.net/net/minecraftforge/forge/$mc-$version/$installer" \
                "$cache/$installer" || return 1
            launch=("@libraries/net/minecraftforge/forge/$mc-$version/unix_args.txt" nogui)
        fi
        if [[ ! -f "$directory/${launch[0]#@}" ]]; then
            (cd "$directory" && "$java_bin" -jar "$cache/$installer" --installServer > install.log 2>&1) || {
                tail -40 "$directory/install.log" >&2
                return 1
            }
        fi
    fi
    printf '%s\n' "$version" > "$directory/loader-version.txt"
    echo "$target: Minecraft $mc, $kind $version"
}

run_target() {
    local target=$1 mc kind nested=false
    case "$target" in
        neoforge-1.21.1) mc=1.21.1; kind=neoforge ;;
        fabric-1.21.1) mc=1.21.1; kind=fabric ;;
        forge-1.20.1) mc=1.20.1; kind=forge ;;
        fabric-1.20.1) mc=1.20.1; kind=fabric ;;
        fabric-jij-1.21.1) mc=1.21.1; kind=fabric; nested=true ;;
        *) echo "Unknown target: $target" >&2; return 1 ;;
    esac
    local java_bin=$default_java
    if [[ "$mc" == 1.20.1 ]]; then
        java_bin=${JAVA_17:-$default_java}
    fi
    local directory="$smoke/$target"
    mkdir -p "$directory/mods" || return 1
    if [[ ! -e "$directory/libraries" ]]; then
        ln -s "$cache/libraries" "$directory/libraries" || return 1
    fi
    "$java_bin" -version > "$directory/java-version.txt" 2>&1 || return 1
    local launch=()
    install_server "$target" "$mc" "$kind" "$directory" || return 1
    if [[ "$nested" == true ]]; then
        local loader_version
        loader_version=$(cat "$directory/loader-version.txt")
        download "https://maven.fabricmc.net/net/fabricmc/fabric-loader/$loader_version/fabric-loader-$loader_version.jar" \
            "$cache/fabric-loader-$loader_version.jar" || return 1
        "$java_bin" "$root/scripts/SmokeNesting.java" "$jar_path" "$directory/mods" \
            "$cache/fabric-loader-$loader_version.jar" || return 1
        rm -f "$directory/mods/$(basename "$jar_path")"
    else
        cp "$jar_path" "$directory/mods/" || return 1
    fi
    printf 'eula=true\n' > "$directory/eula.txt"
    cat > "$directory/server.properties" <<'PROPERTIES'
online-mode=false
server-ip=127.0.0.1
server-port=0
view-distance=2
simulation-distance=2
spawn-chunk-radius=0
max-tick-time=-1
level-type=minecraft:flat
generator-settings={"biome":"minecraft:plains","layers":[{"block":"minecraft:bedrock","height":1},{"block":"minecraft:dirt","height":2},{"block":"minecraft:grass_block","height":1}],"lakes":false,"features":false}
generate-structures=false
sync-chunk-writes=false
PROPERTIES
    rm -f "$directory/console.fifo"
    mkfifo "$directory/console.fifo" || return 1
    exec 3<> "$directory/console.fifo" || return 1
    (cd "$directory" && exec "$java_bin" -Xms256M -Xmx1G "${launch[@]}" <&3 > console.log 2>&1) &
    server_pid=$!
    local deadline=$((SECONDS + startup_timeout)) ready=false
    while (( SECONDS < deadline )); do
        if grep -qE 'Done \([^)]*\)!' "$directory/console.log" 2>/dev/null; then
            ready=true
            break
        fi
        if ! kill -0 "$server_pid" 2>/dev/null || grep -qE \
            'ModLoadingException|ModLoadingFailed|LoadingFailedException|Failed to load mods|Error loading mods|Exception in thread "main"|Failed to start the minecraft server|Found duplicate mods' \
            "$directory/console.log"; then
            break
        fi
        sleep 1
    done
    printf 'stop\n' >&3
    deadline=$((SECONDS + 45))
    while kill -0 "$server_pid" 2>/dev/null && (( SECONDS < deadline )); do
        sleep 1
    done
    if kill -0 "$server_pid" 2>/dev/null; then
        kill "$server_pid" 2>/dev/null || true
        ready=false
    fi
    local status=0
    wait "$server_pid" || status=$?
    server_pid=
    exec 3>&-
    rm -f "$directory/console.fifo"
    grep -E 'jpimini|smokehost|Done \(|Stopping server' "$directory/console.log" \
        > "$directory/evidence.log" || true
    if [[ "$ready" == true && "$status" == 0 ]] && grep -q 'jpimini' "$directory/evidence.log"; then
        if [[ "$kind" != fabric ]] && ! grep -q 'jpimini loaded' "$directory/evidence.log"; then
            echo "$target: entrypoint did not run" >&2
            return 1
        fi
        if [[ "$nested" == true ]]; then
            if ! grep -q 'smokehost_a' "$directory/evidence.log" \
                || ! grep -q 'smokehost_b' "$directory/evidence.log" \
                || ! grep -q 'jpimini nested probe: copies=1; shared services available' "$directory/evidence.log"; then
                echo "$target: nested library was not deduplicated between both hosts" >&2
                return 1
            fi
        fi
        echo "$target: PASS"
        cat "$directory/evidence.log"
    else
        echo "$target: FAIL (ready=$ready, exit=$status); see $directory/console.log" >&2
        tail -40 "$directory/console.log" >&2
        return 1
    fi
}

if (( $# == 0 )); then
    set -- neoforge-1.21.1 fabric-1.21.1 forge-1.20.1 fabric-1.20.1 fabric-jij-1.21.1
fi
printf 'target\tresult\tloader\n' > "$smoke/results.tsv"
failed=0
for target in "$@"; do
    if run_target "$target"; then
        result=PASS
    else
        result=FAIL
        failed=1
    fi
    version=$(cat "$smoke/$target/loader-version.txt" 2>/dev/null || echo unknown)
    printf '%s\t%s\t%s\n' "$target" "$result" "$version" >> "$smoke/results.tsv"
done
cat "$smoke/results.tsv"
exit "$failed"
