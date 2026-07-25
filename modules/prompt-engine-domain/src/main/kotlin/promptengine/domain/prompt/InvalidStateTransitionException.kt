package promptengine.domain.prompt

/**
 * §2.5 のライフサイクル遷移表に無い遷移、またはガード条件を満たさない遷移が
 * 試みられたときに投げるドメイン例外。
 */
class InvalidStateTransitionException(fromState: String, operation: String) :
    IllegalStateException("cannot perform '$operation' from state '$fromState'")
