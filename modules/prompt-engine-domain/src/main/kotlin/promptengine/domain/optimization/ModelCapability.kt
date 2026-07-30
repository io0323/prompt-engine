package promptengine.domain.optimization

/**
 * [ModelProfile.capabilities]が表す能力フラグ（設計書§2.11・§4.4「capabilities」）。
 *
 * 現時点でOptimizationRuleが実際に参照する条件は、Expansionルールの適用条件
 * （§2.11「ModelProfileが指示追従弱と定義する場合」）のみのため、対応する
 * [WEAK_INSTRUCTION_FOLLOWING]のみを定義する（ADR-0013決定7）。列挙値の追加は
 * 非破壊的であり、将来別の条件が必要になれば値を追加するのみでよい。
 */
enum class ModelCapability {
    WEAK_INSTRUCTION_FOLLOWING,
}
