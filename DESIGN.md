# 設計ドキュメント

> チーム名：E
> メンバー：若松和弥、小平

---

## 1. 課題の整理

スターターコードの `UserRegistrationService.register()` には、以下の問題があった。

**構造的な問題**
- 1つの `register()` メソッドに6つの責務（バリデーション／重複チェック／パスワードハッシュ化／DB保存／メール送信／ログ記録）が密集している（単一責任原則違反）。
- `new Database()` / `new EmailClient()` をフィールドで直接生成しており、依存を差し替えられない。そのため単体テストで本物のDB・メール送信を呼んでしまい、テストが書きにくい。
- 例外がすべて `IllegalArgumentException`、メソッドは `throws Exception`。入力不正・重複登録・システム障害を呼び出し側が区別できず、HTTPステータス（400/409/500）に振り分けられない。

**気づいたが今回スコープ外とした問題（→ §5）**
- パスワードハッシュ化が `password + "_hashed"` というダミー実装（実質平文）。
- ログにメールアドレス（個人情報）を出力している。
- 重複チェックがアプリ側のみで、競合（TOCTOU）に弱い。ID採番が時刻ベース。

加えて今回は、**GitHubログイン機能の追加**と、**今後Google・LINEを足しやすい構造**を設計要件として加えた。

---

## 2. 設計方針

1. **単一責任の原則でクラス分割する。** `register()` は「流れの調整（オーケストレーション）」だけを担い、各処理は専用クラスに委譲する。
2. **依存はコンストラクタDIで注入する。** インターフェースに依存させ、テスト時にモックを差し替え可能にする。
3. **例外を型で分ける。** 業務エラー（入力不正・重複）とシステム障害を別の型にし、呼び出し側が判断できるようにする。
4. **ソーシャルログインは「差し替えの箱」を最小化する。** プロバイダ固有処理を「認可コード → 正規化プロフィール」だけに閉じ込め、ユーザー作成・永続化・セッション発行・監査は全プロバイダ共通にする。これによりGoogle・LINEの追加を「プロバイダ1クラス追加」だけで済むようにする。

---

## 3. クラス・メソッド構成

```
■ 共有コア（プロバイダ非依存）
UserRegistrationService     // メール/パスワード登録のオーケストレーション
├── UserValidator           // ① 入力検証
├── PasswordHasher (IF)     // ③ ハッシュ化（FakePasswordHasher：後でbcryptに差替）
├── UserRepository (IF)     // ②④ 重複確認・provider検索・保存（DbUserRepository）
├── EmailService (IF)       // ⑤ 確認メール（ClientEmailService）
└── AuditLogger             // ⑥ 監査ログ（PIIを避けuserIdを出力）

SocialLoginService          // ソーシャルログインのオーケストレーション
├── OAuthProviderRegistry   // ProviderType → 実装の解決
│     └ OAuthProvider (IF)  // ★差し替えの箱：type/authorizationUrl/fetchProfile
│          └ FakeGithubProvider   // GitHub（今回はダミー実装）
├── UserRegistrar           // find-or-create（パスワード登録とソーシャルで共有）
├── SessionIssuer           // セッション発行
└── AuditLogger

■ 例外階層
RegistrationException (RuntimeException)
├── ValidationException     // 400相当
└── DuplicateUserException  // 409相当

■ 値オブジェクト
ProviderType { GITHUB, GOOGLE, LINE }
OAuthUserProfile { provider, providerUserId, email, name }  // 各社の差異を吸収する正規化型
```

**register() の流れ（分割後）**
`validate → userRegistrar.registerWithPassword（重複確認・作成・保存をまとめた共通の幹）→ sendWelcome → logRegistration` を順に呼ぶだけの薄い実装になった。ユーザーの作成・保存はソーシャルログインと同じ `UserRegistrar` を通る。

**ソーシャルログインの流れ**
`registry.get(type) → provider.fetchProfile(code) → userRegistrar.findOrCreateBySocial → sessionIssuer.issue → auditLogger.logSocialLogin`。
`SocialLoginService` の中に "GitHub" という語は一切登場しない。

---

## 4. 工夫したポイント

- **「どこまでを別の箱にするか」を設計の主題にした。** プロバイダ固有の箱を“コード→正規化プロフィール”だけに絞り、それ以外を共有コアに置いた。結果、Google追加は `GoogleOAuthProvider` を1クラス書いてレジストリに1行足すだけで、`SocialLoginService`・`UserRegistrar`・`UserRepository` は無改修で済む（Open/Closed原則）。
- **ユーザー生成を `UserRegistrar` の「共通の幹」に一元化した。** 「User を作って保存する」処理を `createBaseUser()` という1本の幹にまとめ、メール登録（パスワード付与）とソーシャル登録（provider付与）の違いだけを枝分かれさせた。これにより「ユーザーを作る」ロジックの二重化（コピペ）を排除し、作成ルールの変更を1か所で済むようにした。
- **モック（Database/EmailClient）を直接使わず Adapter（DbUserRepository/ClientEmailService）で包んだ。** アプリ本体が具象モックを知らない状態にし、将来の実DB・実メール送信への差し替えを局所化した。
- **ダミー実装を「隔離」した。** 偽ハッシュ化やGitHubのダミー応答を `FakePasswordHasher`・`FakeGithubProvider` に閉じ込め、本実装への差し替え範囲を1クラスに限定した。

---

## 5. できなかったこと・今後の改善点

時間とスコープの都合で、以下は「認識した上で意図的に後回し」とした（判断の理由は DECISIONS.md 参照）。

- **OAuthの実HTTP通信・トークン交換**：今回は設計の箱とダミー実装まで。`FakeGithubProvider.fetchProfile()` を実装に差し替えるのが次ステップ。
- **複数ログイン手段の紐付け**：今回は単純1:1（User が provider を1つ持つ）。1ユーザーに複数SNSを紐付けるには `UserIdentity` を別エンティティに分離する必要がある。
- **セキュリティ実装**：パスワードの本ハッシュ化（bcrypt/Argon2）、ログからのPII除去、アカウント列挙対策、レート制限。
- **データ整合性**：DBのUNIQUE制約・トランザクション境界、メール送信のOutboxパターン化、ID採番のUUID化。
