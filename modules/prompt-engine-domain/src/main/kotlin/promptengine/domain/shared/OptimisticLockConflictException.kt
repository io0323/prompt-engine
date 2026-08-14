package promptengine.domain.shared

/**
 * 楽観ロック衝突（VERSION_CONFLICT、設計書§13.3、ADR-0006/ADR-0008）の抽象マーカー型。
 *
 * `rowVersion`・SQLの`WHERE`句等、永続化技術に紐づく詳細は持たない（それらは
 * `prompt-engine-infrastructure`の具象クラス（`VersionConflictException`等）が持つ。
 * ADR-0006が「永続化技術に紐づく例外のためdomainには追加しない」とした決定は、この
 * 抽象型を除き維持する。ADR-0032決定5）。
 *
 * `prompt-engine-application`の`ErrorCodeResolver`は`prompt-engine-infrastructure`に
 * 依存できないため、具象クラスを個別に判定できない。この型を判定基準にすることで、
 * `VersionConflictException`/`TemplateVersionConflictException`/
 * `FragmentVersionConflictException`のいずれであってもVERSION_CONFLICTへ写像できる。
 */
abstract class OptimisticLockConflictException(message: String) : RuntimeException(message)
