package promptengine.application.view

import promptengine.application.command.ArchiveResult
import promptengine.application.command.CreatePromptResult
import promptengine.application.command.CreateVersionResult
import promptengine.application.command.DeprecateResult
import promptengine.application.command.PublishResult
import promptengine.application.command.RollbackResult
import promptengine.application.command.SetAliasResult
import promptengine.application.command.UpdatePromptMetadataResult

/**
 * CRUD系Commandハンドラの結果（`key`/`semVer`等がdomain型）をString主体のViewへ変換する
 * （[PromptViews.kt][promptengine.application.view]のKDoc参照）。
 */
data class KeySemVerView(val key: String, val semVer: String)

fun CreatePromptResult.toView(): KeySemVerView = KeySemVerView(key.value, semVer.toString())

fun CreateVersionResult.toView(): KeySemVerView = KeySemVerView(key.value, semVer.toString())

fun PublishResult.toView(): KeySemVerView = KeySemVerView(key.value, semVer.toString())

fun DeprecateResult.toView(): KeySemVerView = KeySemVerView(key.value, semVer.toString())

data class RollbackView(val key: String, val targetSemVer: String)

fun RollbackResult.toView(): RollbackView = RollbackView(key.value, targetSemVer.toString())

data class ArchiveView(val key: String, val semVer: String, val structuralInboundDependencyCount: Int)

fun ArchiveResult.toView(): ArchiveView = ArchiveView(key.value, semVer.toString(), structuralInboundDependencyCount)

data class SetAliasView(val key: String, val alias: String, val semVer: String)

fun SetAliasResult.toView(): SetAliasView = SetAliasView(key.value, alias, semVer.toString())

data class KeyOnlyView(val key: String)

fun UpdatePromptMetadataResult.toView(): KeyOnlyView = KeyOnlyView(key.value)
