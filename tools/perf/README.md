# tools/perf/

## render_load_test.sh
NFR-003（Render p99 ≤ 200ms）の性能測定（P11）。`deploy/docker/Dockerfile`でビルドした実イメージを
CPU/メモリ制限付きで起動し、`/render`へcurl（接続再利用）で負荷をかける。手順はREADME
「性能測定」節を参照。
