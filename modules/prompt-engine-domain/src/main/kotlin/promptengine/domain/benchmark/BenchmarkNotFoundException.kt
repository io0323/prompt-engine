package promptengine.domain.benchmark

import java.util.UUID

/** `GET/POST /benchmarks/{id}...`（設計書§13.1、ADR-0035フェーズ(d)）が対象IDを解決できない場合。 */
class BenchmarkNotFoundException(benchmarkId: UUID) : NoSuchElementException("Benchmark not found: '$benchmarkId'")

/** `GET/POST /datasets/{id}...`（設計書§13.1、ADR-0035フェーズ(d)）が対象IDを解決できない場合。 */
class GoldenDatasetNotFoundException(datasetId: UUID) :
    NoSuchElementException("GoldenDataset not found: '$datasetId'")
