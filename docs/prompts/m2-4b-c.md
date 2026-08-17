M2-4b フェーズ(c): 非同期ワーカー + Consistency / Determinism + temperature
作業前に最新の main から feat/m2-4b-c-worker ブランチを切ること。
ADR-0035 の決定に従う。追加の方針提示は不要。

■ 実装スコープ
- Benchmark 実行ワーカー（@Scheduled、項目単位の claim + フェンシング）
- 中断（cancel フラグを項目間で確認）と再開（結果が永続化済みの項目をスキップ）
- Consistency / Determinism の BenchmarkScoringRule 実装
- RenderedPrompt.modelHints への temperature の追加
- 実行時の temperature を execution_logs または PromptExecuted の payload に記録
  （renderHash に含めない以上、後から Determinism の結果を検証できないため。
   スキーマ追加なら ADR と設計書§12 の両方を更新すること）
- FakeExecutionAdapter に、応答リストを巡回するシナリオを追加
  （Consistency が「出力が一致する場合」と「ばらつく場合」を
   区別できることをテストするために必要）

■ 特に検証すること

1. フェンシングが実際に効くこと（設計しただけで終わらせない）
   - ワーカー A が項目を claim → claim_timeout 経過 →
     ワーカー B が再 claim → A が確定を試みる → 確定できないこと
   - P10a では実装したフェンシングの検証テストが無く、
     CodeRabbit の指摘で初めて欠落が判明した。同じ経路を辿らないこと
   - 統合テストで、2つのワーカーが同じ項目を二重実行しないことを検証する
     （実プロバイダでは二重課金に直結する）

2. 中断と再開
   - cancel 後、実行中の項目が終わり次第停止すること
   - 再起動後、完了済み項目が再実行されないこと
   - 中断された Benchmark の状態が中途半端に残らないこと

3. Consistency / Determinism の算出
   - 出力が完全に一致する場合と、ばらつく場合の両方で
     期待どおりのスコアになること
   - N の既定値と設定可能性
   - estimatedExecutionCount が実際の実行回数と一致すること

4. temperature
   - modelHints 経由で ExecutionAdapter まで届くこと
   - renderHash が temperature によって変わらないこと
   - 実行記録に temperature が残ること

■ 報告
CLAUDE.md の追記事項に従い、上記テスト要件それぞれに対して
「それを満たすテストクラス・メソッド」の対応表を含めること。

---

補足（ユーザー、プロンプト提示時のコメント）:

1 が今回の要点です。フェンシングは実装しただけでは検証されません。P10a では実装後に
CodeRabbit の指摘で欠落が判明しました。今回は設計段階で織り込んでいるので実装は
正しいはずですが、それを確認するテストが無ければ、正しいかどうかは誰も知らない
という状態は同じです。
