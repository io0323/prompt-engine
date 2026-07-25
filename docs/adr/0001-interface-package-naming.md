# ADR-0001: Interface層のパッケージ名を `promptengine.interfaces` とする

## ステータス

Accepted

## コンテキスト

[設計書](../PromptEngine_設計書.md) §3.1 のパッケージ構成図では、Interface層（REST/gRPC/SDKエンドポイント、
DTO、認可フィルタ）のパッケージ名として `promptengine.interface` が記載されている。

しかし `interface` はKotlin・Java双方の予約語であり、バッククォートエスケープ
（`` package promptengine.`interface` ``）なしにはパッケージ名として使用できない。全ファイルでの
エスケープ記法の強制は可読性・保守性を損ない、`prompt-engine-interface` モジュール配下の全ソースに影響する。

## 決定

`prompt-engine-interface` モジュールのルートパッケージを `promptengine.interfaces`（複数形）とする。

設計書§3.1の図が指す「Interface層」という概念自体に変更はなく、文字通りのパッケージ名 `interface` を
複数形 `interfaces` に読み替えるのみ。他モジュール（domain / application / engine / infrastructure）の
パッケージ名は設計書の記載どおり変更しない。

## 影響範囲

- `modules/prompt-engine-interface` 配下の全パッケージ宣言・import文
- 今後、設計書§3.1の図・関連する節でパッケージ名を引用する際は `promptengine.interfaces` を正とする

## 参照

- [PromptEngine_設計書.md §3.1](../PromptEngine_設計書.md)
