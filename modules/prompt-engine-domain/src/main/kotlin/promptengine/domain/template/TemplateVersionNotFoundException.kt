package promptengine.domain.template

import promptengine.domain.shared.SemVer

/**
 * Template Aggregate内に指定したSemVerのVersionが存在しないときに投げるドメイン例外。
 */
class TemplateVersionNotFoundException(semVer: SemVer) :
    NoSuchElementException("TemplateVersion not found: $semVer")
