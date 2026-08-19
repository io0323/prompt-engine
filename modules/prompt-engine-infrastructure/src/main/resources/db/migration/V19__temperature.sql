-- ADR-0035（Benchmarkフェーズ(c)、決定5）: temperatureの追加。
--
-- benchmarks.temperature: Benchmark作成時に利用者が指定する生成パラメータ（未指定はNULL、
-- プロバイダ既定値を使う）。Determinismを要求したBenchmarkは、そのTargetのN回実行を
-- 強制的にtemperature=0で行う（Benchmark.create時にDeterminism×非0温度の組み合わせを
-- バリデーションエラーとする）。
ALTER TABLE benchmarks ADD COLUMN temperature DOUBLE PRECISION;

-- execution_logs.temperature: 実行時に実際に使われたtemperature（RenderedPrompt.modelHints
-- 由来）。renderHashの算出には含めないため（Render出力バイトに影響しない実行時パラメータ、
-- ModelHintsのKDoc参照）、後からDeterminism（「temperature=0でのバイト一致率」）の測定結果を
-- 事後検証できるよう実行記録側に残す。modelHintsを伴わない実行（通常経路）はNULLのまま。
ALTER TABLE execution_logs ADD COLUMN temperature DOUBLE PRECISION;
