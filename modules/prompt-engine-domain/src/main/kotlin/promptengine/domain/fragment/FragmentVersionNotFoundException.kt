package promptengine.domain.fragment

import promptengine.domain.shared.SemVer

/**
 * Fragment Aggregate内に指定したSemVerのVersionが存在しないときに投げるドメイン例外。
 */
class FragmentVersionNotFoundException(semVer: SemVer) :
    NoSuchElementException("FragmentVersion not found: $semVer")
