package com.youtrust.hackathon;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * ユーザー登録 / ログインのオーケストレーション。
 *
 * 設計方針:
 *  - register() は「流れの調整」だけを担い、各処理は単一責務クラスへ委譲する（SRP）。
 *  - 依存はコンストラクタDIで注入し、テスト時にモック差し替え可能にする。
 *  - ソーシャルログインは OAuthProvider という「差し替えの箱」に固有処理を閉じ込め、
 *    GitHub / Google / LINE をプロバイダ追加だけで拡張できる形にする。
 *
 * 提出形態の都合で1ファイルに集約しているが、本来は1クラス1ファイルが望ましい。
 */
public class UserRegistrationService {

    // ---- 依存（コンストラクタDIで注入） ----
    private final UserValidator validator;
    private final PasswordHasher passwordHasher;
    private final UserRepository userRepository;
    private final UserRegistrar userRegistrar;
    private final EmailService emailService;
    private final AuditLogger auditLogger;

    /** 本番配線（Composition Root）。既存の呼び出し互換のためのデフォルト構成。 */
    public UserRegistrationService() {
        this(new UserValidator(),
             new FakePasswordHasher(),
             new DbUserRepository(),
             new ClientEmailService(),
             new AuditLogger());
    }

    /** テスト・差し替え用のDIコンストラクタ。 */
    public UserRegistrationService(UserValidator validator,
                                   PasswordHasher passwordHasher,
                                   UserRepository userRepository,
                                   EmailService emailService,
                                   AuditLogger auditLogger) {
        this.validator = validator;
        this.passwordHasher = passwordHasher;
        this.userRepository = userRepository;
        this.userRegistrar = new UserRegistrar(userRepository);
        this.emailService = emailService;
        this.auditLogger = auditLogger;
    }

    /**
     * メール / パスワードでユーザーを登録する（オーケストレーションのみ）。
     */
    public RegisterResult register(RegisterInput input) {
        validator.validate(input);                                  // ① 検証

        if (userRepository.existsByEmail(input.getEmail())) {       // ② 重複確認
            throw new DuplicateUserException("このメールアドレスはすでに登録されています");
        }

        User user = new User();                                     // ③④ 組み立て＋保存
        user.setEmail(input.getEmail());
        user.setName(input.getName());
        user.setPassword(passwordHasher.hash(input.getPassword()));
        userRepository.save(user);

        emailService.sendWelcome(user);                            // ⑤ 通知
        auditLogger.logRegistration(user);                         // ⑥ 監査

        return new RegisterResult(true, user.getId(), "登録が完了しました");
    }


    // ============================================================
    //  例外階層（業務エラーを型で区別し、呼び出し側が 400/409 に振り分け可能）
    // ============================================================

    static class RegistrationException extends RuntimeException {
        public RegistrationException(String message) { super(message); }
    }

    /** 入力不正（HTTP 400 相当）。 */
    static class ValidationException extends RegistrationException {
        public ValidationException(String message) { super(message); }
    }

    /** 重複登録（HTTP 409 相当）。 */
    static class DuplicateUserException extends RegistrationException {
        public DuplicateUserException(String message) { super(message); }
    }


    // ============================================================
    //  単一責務クラス（register の各ブロックを分離）
    // ============================================================

    /** ① 入力検証の責務。 */
    static class UserValidator {
        public void validate(RegisterInput input) {
            if (input.getEmail() == null || !input.getEmail().contains("@")) {
                throw new ValidationException("メールアドレスが無効です");
            }
            if (input.getPassword() == null || input.getPassword().length() < 8) {
                throw new ValidationException("パスワードは8文字以上必要です");
            }
            if (input.getName() == null || input.getName().trim().isEmpty()) {
                throw new ValidationException("名前は必須です");
            }
        }
    }

    /** ③ パスワードハッシュ化の責務（実装を差し替え可能にする）。 */
    interface PasswordHasher {
        String hash(String rawPassword);
    }

    /** 現状のダミー実装。後で BcryptPasswordHasher に差し替えるだけで済むよう隔離。 */
    static class FakePasswordHasher implements PasswordHasher {
        @Override
        public String hash(String rawPassword) {
            return rawPassword + "_hashed"; // TODO: bcrypt / Argon2 + salt に置換する
        }
    }

    /** ②④ 永続化の責務（重複確認・provider検索・保存をまとめる）。 */
    interface UserRepository {
        boolean existsByEmail(String email);
        User findByProvider(ProviderType provider, String providerUserId);
        User save(User user);
    }

    /** モック Database を内部に隠蔽する Adapter。アプリ本体は Database を知らない。 */
    static class DbUserRepository implements UserRepository {
        private final Database database = new Database();

        @Override
        public boolean existsByEmail(String email) {
            return database.findByEmail(email) != null;
        }

        @Override
        public User findByProvider(ProviderType provider, String providerUserId) {
            // モックには provider 検索が無いため常に未登録扱い（初回ログインで作成される）。
            return null;
        }

        @Override
        public User save(User user) {
            database.save(user);
            return user;
        }
    }

    /** ⑤ 通知の責務。メール文面の生成もここに閉じ込める。 */
    interface EmailService {
        void sendWelcome(User user);
    }

    static class ClientEmailService implements EmailService {
        private final EmailClient emailClient = new EmailClient();

        @Override
        public void sendWelcome(User user) {
            String subject = "【ハッカソン】登録完了のお知らせ";
            String body = user.getName() + " 様\n\nご登録ありがとうございます。";
            emailClient.send(user.getEmail(), subject, body);
        }
    }

    /** ⑥ 監査ログの責務。PII を避けるため email ではなく userId を出力。 */
    static class AuditLogger {
        private static final Logger logger = Logger.getLogger(AuditLogger.class.getName());

        public void logRegistration(User user) {
            logger.info("ユーザー登録完了: userId=" + user.getId());
        }

        public void logSocialLogin(User user, ProviderType provider) {
            logger.info("ソーシャルログイン: userId=" + user.getId() + ", provider=" + provider);
        }
    }


    // ============================================================
    //  ソーシャルログイン（共有コア）
    //  ─ SocialLoginService 内に "GitHub" という語は一切登場しない。
    // ============================================================

    /** 対応プロバイダ。新規追加時はここに1要素足す。 */
    enum ProviderType { GITHUB, GOOGLE, LINE }

    /** 各プロバイダの出力を統一する正規化プロフィール（不変）。 */
    static class OAuthUserProfile {
        private final ProviderType provider;
        private final String providerUserId;
        private final String email;
        private final String name;

        public OAuthUserProfile(ProviderType provider, String providerUserId,
                                String email, String name) {
            this.provider = provider;
            this.providerUserId = providerUserId;
            this.email = email;
            this.name = name;
        }
        public ProviderType getProvider() { return provider; }
        public String getProviderUserId() { return providerUserId; }
        public String getEmail() { return email; }
        public String getName() { return name; }
    }

    /** ★差し替えの箱。プロバイダ固有処理は「認可コード → 正規化プロフィール」だけに限定。 */
    interface OAuthProvider {
        ProviderType type();
        String authorizationUrl(String state);
        OAuthUserProfile fetchProfile(String authorizationCode);
    }

    /** GitHub のダミー実装。実HTTP/トークン交換はこの箱の中だけの差し替え事項。 */
    static class FakeGithubProvider implements OAuthProvider {
        @Override
        public ProviderType type() { return ProviderType.GITHUB; }

        @Override
        public String authorizationUrl(String state) {
            return "https://github.com/login/oauth/authorize"
                    + "?client_id=DUMMY&scope=user:email&state=" + state;
        }

        @Override
        public OAuthUserProfile fetchProfile(String authorizationCode) {
            // TODO: 本実装では code→access_token→/user, /user/emails を叩いて翻訳する。
            //       ここでは設計の箱を示すため固定プロフィールを返す。
            return new OAuthUserProfile(
                    ProviderType.GITHUB,
                    "gh_123456",
                    "octocat@example.com",
                    "octocat");
        }
    }

    /** ProviderType → 実装の解決のみを担うレジストリ。 */
    static class OAuthProviderRegistry {
        private final Map<ProviderType, OAuthProvider> providers =
                new EnumMap<>(ProviderType.class);

        public OAuthProviderRegistry(List<OAuthProvider> all) {
            for (OAuthProvider p : all) providers.put(p.type(), p);
        }

        public OAuthProvider get(ProviderType type) {
            OAuthProvider p = providers.get(type);
            if (p == null) {
                throw new IllegalArgumentException("未対応のプロバイダ: " + type);
            }
            return p;
        }
    }

    /** find-or-create を一元化（パスワード登録とソーシャルの両入口が共有）。 */
    static class UserRegistrar {
        private final UserRepository userRepository;

        public UserRegistrar(UserRepository userRepository) {
            this.userRepository = userRepository;
        }

        /** ソーシャル経由：初回ログインなら自動登録、既存なら取得（単純1:1連携）。 */
        public User findOrCreateBySocial(OAuthUserProfile profile) {
            User existing = userRepository.findByProvider(
                    profile.getProvider(), profile.getProviderUserId());
            if (existing != null) return existing;

            User user = new User();
            user.setEmail(profile.getEmail());
            user.setName(profile.getName());
            user.setProvider(profile.getProvider());
            user.setProviderUserId(profile.getProviderUserId());
            // ソーシャルのみのためパスワードは無し。
            return userRepository.save(user);
        }
    }

    /** セッション発行の責務（ダミー）。 */
    static class SessionIssuer {
        public String issue(User user) {
            return "session_" + user.getId();
        }
    }

    /** ソーシャルログインのオーケストレーション（プロバイダ非依存）。 */
    static class SocialLoginService {
        private final OAuthProviderRegistry registry;
        private final UserRegistrar userRegistrar;
        private final SessionIssuer sessionIssuer;
        private final AuditLogger auditLogger;

        public SocialLoginService(OAuthProviderRegistry registry,
                                  UserRegistrar userRegistrar,
                                  SessionIssuer sessionIssuer,
                                  AuditLogger auditLogger) {
            this.registry = registry;
            this.userRegistrar = userRegistrar;
            this.sessionIssuer = sessionIssuer;
            this.auditLogger = auditLogger;
        }

        public LoginResult login(ProviderType type, String authorizationCode) {
            OAuthProvider provider = registry.get(type);                     // 箱を選ぶ
            OAuthUserProfile profile = provider.fetchProfile(authorizationCode); // 固有処理はここだけ
            User user = userRegistrar.findOrCreateBySocial(profile);          // 共通
            String session = sessionIssuer.issue(user);                       // 共通
            auditLogger.logSocialLogin(user, type);                           // 共通
            return new LoginResult(true, user.getId(), session);
        }
    }

    /** ソーシャルログイン結果（不変）。 */
    static class LoginResult {
        private final boolean success;
        private final String userId;
        private final String sessionToken;

        public LoginResult(boolean success, String userId, String sessionToken) {
            this.success = success;
            this.userId = userId;
            this.sessionToken = sessionToken;
        }
        public boolean isSuccess() { return success; }
        public String getUserId() { return userId; }
        public String getSessionToken() { return sessionToken; }
    }


    // ============================================================
    //  以下はモッククラス（変更不要）
    // ============================================================

    static class Database {
        public User findByEmail(String email) { return null; }
        public void save(User user) { user.setId("user_" + System.currentTimeMillis()); }
    }

    static class EmailClient {
        public void send(String to, String subject, String body) {
            System.out.println("Email sent to: " + to);
        }
    }

    static class User {
        private String id;
        private String email;
        private String name;
        private String password;
        private ProviderType provider;      // ソーシャル連携（単純1:1）
        private String providerUserId;      // プロバイダ側のユーザーID
        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getEmail() { return email; }
        public void setEmail(String email) { this.email = email; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
        public ProviderType getProvider() { return provider; }
        public void setProvider(ProviderType provider) { this.provider = provider; }
        public String getProviderUserId() { return providerUserId; }
        public void setProviderUserId(String providerUserId) { this.providerUserId = providerUserId; }
    }

    static class RegisterInput {
        private String email;
        private String password;
        private String name;
        public String getEmail() { return email; }
        public void setEmail(String email) { this.email = email; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
    }

    static class RegisterResult {
        private final boolean success;
        private final String userId;
        private final String message;
        public RegisterResult(boolean success, String userId, String message) {
            this.success = success;
            this.userId = userId;
            this.message = message;
        }
        public boolean isSuccess() { return success; }
        public String getUserId() { return userId; }
        public String getMessage() { return message; }
    }
}
