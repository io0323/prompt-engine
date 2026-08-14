package promptengine.application.view

import promptengine.application.command.ApproveResult
import promptengine.application.command.RejectResult
import promptengine.application.command.SubmitReviewResult

/**
 * `ReviewCase`関連Commandハンドラの結果をString主体のViewへ変換する（[KeySemVerView]は
 * `CommandResultViews.kt`で定義済みのものを再利用する。同ファイルのKDoc参照）。
 */
fun SubmitReviewResult.toView(): KeySemVerView = KeySemVerView(key.value, semVer.toString())

fun ApproveResult.toView(): KeySemVerView = KeySemVerView(key.value, semVer.toString())

fun RejectResult.toView(): KeySemVerView = KeySemVerView(key.value, semVer.toString())
