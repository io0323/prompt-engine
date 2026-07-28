package promptengine.domain.shared

/**
 * [PublicationState] の遷移表に無い遷移が試みられたときに投げるドメイン例外
 * （Template/Fragment向け、ADR-0008）。`promptengine.domain.prompt
 * .InvalidStateTransitionException` とは意図的に別クラスとし、Promptの
 * 6状態遷移の例外型を変更しない。
 */
class InvalidStateTransitionException(fromState: String, operation: String) :
    IllegalStateException("cannot perform '$operation' from state '$fromState'")
