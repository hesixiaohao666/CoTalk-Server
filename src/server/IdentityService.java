package server;

import model.IdentityAccount;
import store.Database;
import store.IdentityEventStore;
import store.IdentityStore;

import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * 群聊身份注册、登录、入群审核与移除。密码只保存 PBKDF2 verifier。
 */
public class IdentityService {

    public static final int LOGIN_OK = 0;
    public static final int LOGIN_PENDING = 1;
    public static final int LOGIN_FAILED = 2;

    private static final Pattern USERNAME =
            Pattern.compile("^[A-Za-z][A-Za-z0-9]*$");

    private final ServerConfig config;
    private final IdentityStore store;
    private final IdentityEventStore events;
    private final Consumer<String> logger;

    public IdentityService(Database database,
                           ServerConfig config,
                           Consumer<String> logger) {
        this.config = config;
        this.store = new IdentityStore(database);
        this.events = new IdentityEventStore(database);
        this.logger = logger == null ? message -> { } : logger;
    }

    public RegisterResult register(String username,
                                   String password,
                                   String nickname,
                                   boolean adminVerified) throws Exception {
        String normalized = normalizeUsername(username);
        if (!usernameValid(normalized)) {
            return RegisterResult.failure("用户名需以字母开头，仅包含字母和数字");
        }
        if (!passwordValid(password)) {
            return RegisterResult.failure("密码需为 "
                    + config.adminIdentityPasswordMin + "–"
                    + config.adminIdentityPasswordMax
                    + " 位，且至少包含大写字母、小写字母、数字、特殊字符中的两类");
        }
        String nick = nickname == null ? "" : nickname.trim();
        if (nick.isEmpty() || nick.length() > 16) {
            return RegisterResult.failure("昵称需为 1–16 个字符");
        }
        if (store.findByUsername(normalized) != null) {
            return RegisterResult.failure("用户名已存在");
        }

        long now = System.currentTimeMillis();
        IdentityAccount account = new IdentityAccount();
        account.id = "cid-" + UUID.randomUUID().toString()
                .replace("-", "").substring(0, 12);
        account.username = normalized;
        account.nickname = nick;
        account.salt = AdminAuth.generateSalt();
        account.iterations = config.adminPasswordIterations;
        account.verifier = AdminAuth.createVerifier(
                password, account.salt, account.iterations);
        account.admin = adminVerified;
        account.status = config.adminJoinReviewEnabled
                ? IdentityAccount.STATUS_PENDING
                : IdentityAccount.STATUS_APPROVED;
        account.createdAt = now;
        account.updatedAt = now;
        account.lastLoginAt = 0;
        account.lastSeenAt = 0;
        store.insert(account);
        events.record(account.id, account.username, "register",
                "system", account.status);
        logger.accept("身份注册：" + account.username
                + "，状态 " + account.status
                + (account.admin ? "，管理员" : ""));
        return RegisterResult.success(account);
    }

    public LoginResult login(String username, String password) throws Exception {
        String normalized = normalizeUsername(username);
        IdentityAccount account = store.findByUsername(normalized);
        if (account == null) {
            return new LoginResult(LOGIN_FAILED, null, "用户名或密码错误");
        }
        if (!verify(password, account.salt, account.iterations,
                account.verifier)) {
            return new LoginResult(LOGIN_FAILED, null, "用户名或密码错误");
        }
        if (account.pending()) {
            return new LoginResult(LOGIN_PENDING, account, "等待管理员审核");
        }
        if (!account.approved()) {
            return new LoginResult(LOGIN_FAILED, null, "账号状态不可用");
        }
        long now = System.currentTimeMillis();
        store.updateLogin(account.id, now);
        account.lastLoginAt = now;
        events.record(account.id, account.username, "login",
                account.username, "");
        return new LoginResult(LOGIN_OK, account, "");
    }

    public IdentityAccount approve(String identityId,
                                   String operator) throws Exception {
        IdentityAccount account = store.findById(identityId);
        if (account == null || !account.pending()) {
            throw new IllegalArgumentException("申请不存在或已处理");
        }
        store.updateStatus(account.id, IdentityAccount.STATUS_APPROVED);
        account.status = IdentityAccount.STATUS_APPROVED;
        account.updatedAt = System.currentTimeMillis();
        events.record(account.id, account.username, "approve",
                operator, "");
        return account;
    }

    public IdentityAccount reject(String identityId,
                                  String operator) throws Exception {
        IdentityAccount account = store.findById(identityId);
        if (account == null || !account.pending()) {
            throw new IllegalArgumentException("申请不存在或已处理");
        }
        store.delete(account.id);
        events.record(account.id, account.username, "reject",
                operator, "username released");
        return account;
    }

    public IdentityAccount kick(String identityId,
                                String operator) throws Exception {
        IdentityAccount account = store.findById(identityId);
        if (account == null) {
            return null;
        }
        store.delete(account.id);
        events.record(account.id, account.username, "kick",
                operator, "username and password released");
        return account;
    }

    public void touchSeen(IdentityAccount account) throws Exception {
        if (account == null) {
            return;
        }
        store.updateSeen(account.id, System.currentTimeMillis());
    }

    public List<IdentityAccount> pending(int limit) throws Exception {
        return store.listPending(limit);
    }

    public List<IdentityAccount> approved(int limit) throws Exception {
        return store.listApproved(limit);
    }

    public IdentityAccount findById(String id) throws Exception {
        return store.findById(id);
    }

    public IdentityAccount findByUsername(String username) throws Exception {
        return store.findByUsername(normalizeUsername(username));
    }

    public int adminCount() throws Exception {
        return store.countAdmins();
    }

    public boolean usernameValid(String username) {
        String value = normalizeUsername(username);
        return value.length() >= config.adminIdentityUsernameMin
                && value.length() <= config.adminIdentityUsernameMax
                && USERNAME.matcher(value).matches();
    }

    public boolean passwordValid(String password) {
        if (password == null) {
            return false;
        }
        int length = password.length();
        if (length < config.adminIdentityPasswordMin
                || length > config.adminIdentityPasswordMax) {
            return false;
        }
        boolean upper = false;
        boolean lower = false;
        boolean digit = false;
        boolean special = false;
        for (int i = 0; i < password.length(); i++) {
            char c = password.charAt(i);
            if (Character.isUpperCase(c)) {
                upper = true;
            } else if (Character.isLowerCase(c)) {
                lower = true;
            } else if (Character.isDigit(c)) {
                digit = true;
            } else if (!Character.isWhitespace(c)) {
                special = true;
            }
        }
        int categories = (upper ? 1 : 0) + (lower ? 1 : 0)
                + (digit ? 1 : 0) + (special ? 1 : 0);
        return categories >= 2;
    }

    private static boolean verify(String password,
                                  String salt,
                                  int iterations,
                                  String expectedVerifier) {
        try {
            String actual = AdminAuth.createVerifier(
                    password, salt, iterations);
            return MessageDigest.isEqual(
                    Base64.getUrlDecoder().decode(actual),
                    Base64.getUrlDecoder().decode(expectedVerifier));
        } catch (Exception e) {
            return false;
        }
    }

    private static String normalizeUsername(String username) {
        return username == null ? "" : username.trim()
                .toLowerCase(Locale.ROOT);
    }

    public static final class RegisterResult {
        public final boolean success;
        public final String message;
        public final IdentityAccount account;

        private RegisterResult(boolean success, String message,
                               IdentityAccount account) {
            this.success = success;
            this.message = message;
            this.account = account;
        }

        static RegisterResult success(IdentityAccount account) {
            return new RegisterResult(true, "", account);
        }

        static RegisterResult failure(String message) {
            return new RegisterResult(false, message, null);
        }
    }

    public static final class LoginResult {
        public final int status;
        public final IdentityAccount account;
        public final String message;

        LoginResult(int status, IdentityAccount account, String message) {
            this.status = status;
            this.account = account;
            this.message = message;
        }
    }
}
