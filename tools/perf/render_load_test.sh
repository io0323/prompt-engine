#!/usr/bin/env bash
# P11 性能測定（NFR-003: Render p99 <= 200ms）。deploy/docker/Dockerfileでビルドした
# イメージを、CPU/メモリ制限を付けた状態で実際に起動し、/render に対してcurl（接続再利用）で
# 負荷をかけてp50/p99を計測する。README「性能測定」節から呼び出す想定。
#
# 前提: リポジトリルートで実行すること。docker / docker compose / curl / ./gradlew が使えること。
#
# 環境変数で調整可能な条件（既定値はREADMEに記録した実測時の値と一致させてある）:
#   IMAGE_TAG          ビルド対象イメージタグ（既定: prompt-engine:p11-perf）
#   PERF_CPUS          docker run --cpus（既定: 1）
#   PERF_MEMORY        docker run --memory（既定: 1g）
#   WARMUP_REQUESTS    ウォームアップリクエスト数（既定: 5000。README「ウォームアップ回数の根拠」参照）
#   MEASURE_REQUESTS   測定対象リクエスト総数（既定: 2000）
#   CONCURRENCY        並列クライアント数（既定: 10。各クライアントは自分の接続を使い回す）
#   JWKS_PORT          tools/perf/DevJwks.java が待ち受けるポート（既定: 8099）
set -euo pipefail
cd "$(dirname "$0")/../.."

IMAGE_TAG="${IMAGE_TAG:-prompt-engine:p11-perf}"
PERF_CPUS="${PERF_CPUS:-1}"
PERF_MEMORY="${PERF_MEMORY:-1g}"
WARMUP_REQUESTS="${WARMUP_REQUESTS:-5000}"
MEASURE_REQUESTS="${MEASURE_REQUESTS:-2000}"
CONCURRENCY="${CONCURRENCY:-10}"
JWKS_PORT="${JWKS_PORT:-8099}"
APP_CONTAINER=pe-perf-app
WORKDIR=$(mktemp -d)
trap cleanup EXIT

cleanup() {
  echo "--- cleanup ---"
  [ -n "${JWKS_PID:-}" ] && kill "$JWKS_PID" 2>/dev/null || true
  docker rm -f "$APP_CONTAINER" >/dev/null 2>&1 || true
  rm -rf "$WORKDIR"
}

echo "--- 1. build image ---"
DOCKER_BUILDKIT=1 docker build -f deploy/docker/Dockerfile -t "$IMAGE_TAG" .

echo "--- 2. start postgres (compose) ---"
# host側のlocalhost:5432は、開発機に別途ネイティブPostgresが常駐しているとポート競合で
# そちらへ誤接続することがある（IPv4/IPv6ループバック双方、このリポジトリでの検証中に
# 実際に発生）。またDocker Desktop for MacはコンテナのブリッジIPをホストから直接
# ルーティングできない（Linuxと異なる既知の制約）ため、コンテナIP直接指定でも回避できない。
# ホスト側から接続するツール（RenderLoadSeeder）専用に、compose.yamlは変更せず
# オーバーレイでpostgresを別ホストポートにも公開する。
POSTGRES_HOST_PORT="${POSTGRES_HOST_PORT:-55432}"
cat > "$WORKDIR/compose.override.yml" <<EOF
services:
  postgres:
    ports:
      - "${POSTGRES_HOST_PORT}:5432"
EOF
docker compose -f compose.yaml -f "$WORKDIR/compose.override.yml" up -d postgres
echo "waiting for postgres host port ${POSTGRES_HOST_PORT}..."
for i in $(seq 1 30); do
  pg_isready -h localhost -p "$POSTGRES_HOST_PORT" -U prompt_engine >/dev/null 2>&1 && break
  sleep 1
done
POSTGRES_CID=$(docker compose ps -q postgres)
COMPOSE_NETWORK=$(docker inspect -f '{{range $k,$v := .NetworkSettings.Networks}}{{$k}}{{end}}' "$POSTGRES_CID")

echo "--- 3. start DevJwks (local JWKS + JWT) ---"
java "$(dirname "$0")/DevJwks.java" "$JWKS_PORT" > "$WORKDIR/devjwks.log" 2>&1 &
JWKS_PID=$!
JWT=""
for i in $(seq 1 30); do
  sleep 1
  JWT=$(grep '^JWT=' "$WORKDIR/devjwks.log" 2>/dev/null | cut -d= -f2- || true)
  [ -n "$JWT" ] && break
done
JWKS_URI=$(grep '^JWKS_URI=' "$WORKDIR/devjwks.log" 2>/dev/null | cut -d= -f2- || true)
if [ -z "$JWT" ]; then
  echo "DevJwks failed to start"
  cat "$WORKDIR/devjwks.log"
  exit 1
fi

echo "--- 4. run app container (cpus=$PERF_CPUS memory=$PERF_MEMORY) ---"
docker run -d --name "$APP_CONTAINER" \
  --network "$COMPOSE_NETWORK" \
  --cpus "$PERF_CPUS" --memory "$PERF_MEMORY" \
  -p 8080:8080 \
  -e PE_DATASOURCE_URL=jdbc:postgresql://postgres:5432/prompt_engine \
  -e PE_DATASOURCE_USERNAME=prompt_engine \
  -e PE_DATASOURCE_PASSWORD=prompt_engine \
  -e PE_CIAP_JWKS_URI="$JWKS_URI" \
  "$IMAGE_TAG"

echo "--- 5. wait for readiness ---"
for i in $(seq 1 60); do
  if curl -sf http://localhost:8080/actuator/health/readiness >/dev/null 2>&1; then
    echo "ready after ${i}s"
    break
  fi
  sleep 1
done
curl -sf http://localhost:8080/actuator/health/readiness >/dev/null || { echo "app did not become ready"; docker logs "$APP_CONTAINER"; exit 1; }

echo "--- 6. seed a Published Prompt version via RenderLoadSeeder ---"
PERF_JWT="$JWT" PERF_APP_BASE_URL="http://localhost:8080" PERF_SEED_OUTPUT="$WORKDIR/seed.env" \
  PE_DATASOURCE_URL="jdbc:postgresql://localhost:${POSTGRES_HOST_PORT}/prompt_engine" \
  PE_DATASOURCE_USERNAME=prompt_engine PE_DATASOURCE_PASSWORD=prompt_engine \
  ./gradlew :modules:prompt-engine-bootstrap:test --tests "promptengine.bootstrap.perf.RenderLoadSeeder" \
  -DincludeTags=perf --rerun
# shellcheck disable=SC1090
source "$WORKDIR/seed.env"
RENDER_URL="http://localhost:8080/api/v1/prompts/${PERF_PROMPT_KEY}/render"
echo "render URL: $RENDER_URL"

# curlの-Kは、silent/output等を1回だけ書いて複数のurlを列挙しても、2件目以降の
# レスポンスボディ抑制には適用されないことを実測で確認した（curl 8.7.1で確認、
# 恐らく実装依存の挙動）。`next`区切りで毎回フルセットのオプションを書き直すことで
# 確実に抑制する（`next`はHTTP接続自体は保持したままリクエストだけを区切る）。
build_curl_config() {
  local file="$1" count="$2"
  {
    for i in $(seq 1 "$count"); do
      echo "header = \"Authorization: Bearer $JWT\""
      echo "header = \"Content-Type: application/json\""
      echo "request = \"POST\""
      echo "data = \"{\\\"versionRef\\\":\\\"${PERF_SEMVER}\\\",\\\"modelProfile\\\":\\\"gpt-class-large\\\"}\""
      echo "silent"
      echo "output = \"/dev/null\""
      echo 'write-out = "%{http_code} %{time_total}\n"'
      echo "url = \"$RENDER_URL\""
      if [ "$i" -lt "$count" ]; then echo "next"; fi
    done
  } > "$file"
}

echo "--- 7. warmup ($WARMUP_REQUESTS requests, single connection) ---"
build_curl_config "$WORKDIR/warmup.curl" "$WARMUP_REQUESTS"
curl -K "$WORKDIR/warmup.curl" > "$WORKDIR/warmup.raw" 2>/dev/null

echo "--- 8. measurement ($MEASURE_REQUESTS requests, concurrency=$CONCURRENCY, connection reused per worker) ---"
PER_WORKER=$(( MEASURE_REQUESTS / CONCURRENCY ))
pids=()
for w in $(seq 1 "$CONCURRENCY"); do
  build_curl_config "$WORKDIR/measure-$w.curl" "$PER_WORKER"
  curl -K "$WORKDIR/measure-$w.curl" > "$WORKDIR/measure-$w.raw" 2>/dev/null &
  pids+=($!)
done
for pid in "${pids[@]}"; do wait "$pid"; done
cat "$WORKDIR"/measure-*.raw > "$WORKDIR/measure.raw"

# 各行は "<http_code> <time_total>"。200以外（失敗）は時間集計から除外し、成功率を別途報告する。
TOTAL_MEASURED=$(wc -l < "$WORKDIR/measure.raw" | tr -d ' ')
SUCCESS_MEASURED=$(awk '$1==200' "$WORKDIR/measure.raw" | wc -l | tr -d ' ')
awk '$1==200{print $2}' "$WORKDIR/warmup.raw" > "$WORKDIR/warmup.times"
awk '$1==200{print $2}' "$WORKDIR/measure.raw" > "$WORKDIR/measure.times"

percentile() {
  local file="$1" pct="$2"
  sort -n "$file" | awk -v p="$pct" '{a[NR]=$1} END{idx=int(NR*p); if(idx<1)idx=1; if(idx>NR)idx=NR; printf "%.4f", a[idx]*1000}'
}

WARMUP_TAIL_AVG=$(tail -n 200 "$WORKDIR/warmup.times" | awk '{s+=$1;n++} END{printf "%.4f", (s/n)*1000}')
MEASURE_HEAD_AVG=$(head -n 200 "$WORKDIR/measure.times" | awk '{s+=$1;n++} END{printf "%.4f", (s/n)*1000}')
P50=$(percentile "$WORKDIR/measure.times" 0.50)
P99=$(percentile "$WORKDIR/measure.times" 0.99)
MAX=$(sort -n "$WORKDIR/measure.times" | tail -1 | awk '{printf "%.4f", $1*1000}')

echo "=================================================================="
echo "RESULT"
echo "  image: $IMAGE_TAG"
echo "  resource limits: cpus=$PERF_CPUS memory=$PERF_MEMORY"
echo "  warmup requests: $WARMUP_REQUESTS (single connection, sequential)"
echo "  measured requests: $MEASURE_REQUESTS (concurrency=$CONCURRENCY, ${PER_WORKER}/worker, connection reused per worker)"
echo "  measured success rate: ${SUCCESS_MEASURED}/${TOTAL_MEASURED} (HTTP 200)"
echo "  warmup tail-200 avg (ms): $WARMUP_TAIL_AVG"
echo "  measured head-200 avg (ms): $MEASURE_HEAD_AVG   <- 上の値に近ければウォームアップ十分と判断"
echo "  p50 (ms): $P50"
echo "  p99 (ms): $P99"
echo "  max (ms): $MAX"
echo "=================================================================="

if [ -n "${RESULTS_DEBUG_DIR:-}" ]; then
  mkdir -p "$RESULTS_DEBUG_DIR"
  cp "$WORKDIR/warmup.raw" "$WORKDIR/measure.raw" "$RESULTS_DEBUG_DIR/" 2>/dev/null || true
fi
