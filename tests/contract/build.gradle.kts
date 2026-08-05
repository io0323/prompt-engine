plugins {
    id("promptengine.kotlin-conventions")
}

// api/openapi.yaml（静的ファイル）の構造検証のみを行う。springdoc生成物との実際の差分検出
// （Issue #13）はDocker/Testcontainersを必要とする`generateOpenApiDocs`実行を前提とするため
// `.github/workflows/contract.yml`（CIワークフロー）側の責務とし、本モジュールはDockerを
// 必要としない範囲（コミット済みYAML自体の構造）のみを検証する。

dependencies {
    testImplementation(libs.snakeyaml)
}
