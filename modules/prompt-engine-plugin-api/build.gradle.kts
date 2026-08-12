plugins {
    id("promptengine.kotlin-conventions")
    `maven-publish`
}

// Plugin実装は prompt-engine-plugin-api と prompt-engine-domain の公開型のみを参照する（CLAUDE.md）。
// このモジュール自体も同じ制約に従い、domain 以外のモジュールに依存しない。
//
// 現時点でこのモジュールは.gitkeepのみで実体クラスが無く、domainへの依存は不要
// （FR-024監査、README「FR-001〜FR-024実装状況」参照）。project()依存を明示的に持たせない
// 理由がもう一つある: Gradle 9.xの`maven-publish`は、publishするコンポーネントの依存グラフに
// project()依存が1件でも含まれると（scopeを問わず）POM生成時に
// `ProjectDependency.getDependencyProject()`（Gradle 9で削除されたAPI）を内部で呼び出し
// クラッシュする既知の非互換がある（release.ymlでのGitHub Packages publish検証中に実際に発生、
// Issue #88）。実際のPlugin API型（domain型を参照する）を追加する際にこの問題へ再度当たるため、
// Issue #88で追跡する。

// `v*`タグのpush時（release.yml）にのみ`-Pversion=`が渡される。ローカルbuild等では
// 未設定のままで良い（publish自体を手元から行うことは想定しない）。
version = (findProperty("version") as String?) ?: "0.0.0-local"

// release.yml（実装ガイド§3.4）: `prompt-engine-plugin-api`をGitHub Packages（Maven）へpublishする。
// このモジュールはPlugin実装が依存する唯一の公開APIサーフェスであり、CLAUDE.mdが
// 「後方互換必須」と定める境界そのもの（.github/CODEOWNERS想定でも同じ節）。
publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            groupId = "promptengine"
            artifactId = "prompt-engine-plugin-api"
        }
    }
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/${System.getenv("GITHUB_REPOSITORY") ?: "io0323/prompt-engine"}")
            credentials {
                username = System.getenv("GITHUB_ACTOR")
                password = System.getenv("GITHUB_TOKEN")
            }
        }
    }
}
