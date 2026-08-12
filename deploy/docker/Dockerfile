# syntax=docker/dockerfile:1
#
# P11（M1仕上げ）。マルチステージビルド: (1) Gradleでbootstrapモジュールのbootjarをビルド、
# (2) Spring Bootのjarmode toolsでレイヤー抽出（依存関係と自作コードを別Dockerレイヤーに
# 分離し、依存関係が変わらない限りその層の再pullを避ける）、(3) distroless nonrootの
# 最小ランタイムへコピー。
#
# distrolessにはシェルが無いため、Dockerfile内でのHEALTHCHECK（CMD経由）は使えない。
# liveness/readinessはKubernetes側のhttpGetプローブ（deploy/helm/prompt-engine、
# actuator /actuator/health/{liveness,readiness}）が担う前提とし、ここでは持たせない。
#
# ビルドコンテキストはリポジトリルート想定:
#   docker build -f deploy/docker/Dockerfile -t prompt-engine:local .

# ---- 1. build: Gradle multi-moduleビルド ----
FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /workspace

COPY gradlew ./
COPY gradle gradle
COPY buildSrc buildSrc
COPY settings.gradle.kts build.gradle.kts gradle.properties ./
COPY modules modules
COPY plugins plugins

RUN chmod +x gradlew
# --no-daemon に加えKotlinコンパイラも同一JVMプロセス内で実行する（in-process）。
# メモリに余裕の無いビルド環境（CI Runner・Docker Desktop既定割当）ではGradle daemonに
# 加えて別プロセスのKotlin compile daemonまで同時に立ち上がるとOOMでdaemonごと消える
# ことがあるため（本Dockerfileの検証中に実際に発生）、プロセス数を減らして安定させる。
ENV GRADLE_OPTS="-Xmx2g"
RUN --mount=type=cache,target=/root/.gradle/caches \
    --mount=type=cache,target=/root/.gradle/wrapper \
    ./gradlew :modules:prompt-engine-bootstrap:bootJar -x test -x ktlintCheck -x detekt \
    --no-daemon -Dkotlin.compiler.execution.strategy=in-process

# ---- 2. extract: bootJarをレイヤーへ分解 ----
FROM eclipse-temurin:21-jre-jammy AS extract
WORKDIR /builder
COPY --from=build /workspace/modules/prompt-engine-bootstrap/build/libs/prompt-engine-bootstrap.jar application.jar
RUN java -Djarmode=tools -jar application.jar extract --layers --destination extracted

# ---- 3. runtime: distroless nonroot ----
FROM gcr.io/distroless/java21-debian12:nonroot AS runtime
WORKDIR /application

COPY --from=extract /builder/extracted/dependencies/ ./
COPY --from=extract /builder/extracted/spring-boot-loader/ ./
COPY --from=extract /builder/extracted/snapshot-dependencies/ ./
COPY --from=extract /builder/extracted/application/ ./

# distroless/java21-debian12:nonroot は既定でuid 65532（nonroot）で実行する。
USER nonroot
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "application.jar"]
