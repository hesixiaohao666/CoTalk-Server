package server;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import model.Attachment;
import model.Avatar;
import model.Member;
import model.Message;
import model.Room;
import protocol.Protocol;
import store.ConnectionLogStore;
import util.JsonUtil;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.SQLException;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 单客户端连接：按行读取 NDJSON，处理 join/leave/ping。
 * 消息、历史、资料、头像在后续 Step 扩展。
 */
public class ClientHandler implements Runnable {

    private static final int HANDSHAKE_TIMEOUT_MS = 5000;
    private static final int ADMIN_MAX_FAILURES = 5;
    private static final long ADMIN_CHALLENGE_TTL_MS = 60_000L;

    private enum State {
        CONNECTED,
        WAIT_AUTH,
        AUTHENTICATED,
        JOINED
    }

    private final Socket socket;
    private final ChatServer server;
    private final RoomService roomService;
    private final ConnectionLogStore connectionLogStore;
    private final MessageService messageService;
    private final AvatarService avatarService;
    private final AttachmentService attachmentService;
    private final ServerConfig config;
    private String ip;
    private final Object writeLock = new Object();
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final LinkedHashMap<String, Ack> recentAcks =
            new LinkedHashMap<String, Ack>(256, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Ack> eldest) {
                    return size() > 200;
                }
            };

    private BufferedReader reader;
    private BufferedWriter writer;
    private volatile boolean joined;
    private volatile State state = State.CONNECTED;
    private byte[] nonce;
    private Member member;
    private String clientTag = "";
    private int clientVersionCode = -1;
    private long rateWindowStart;
    private int rateCount;
    private String adminNonce = "";
    private long adminChallengeExpiresAt;
    private int adminAuthFailures;
    private long adminAuthNextAttemptAt;

    public ClientHandler(Socket socket,
                         ChatServer server,
                         RoomService roomService,
                         ConnectionLogStore connectionLogStore,
                         MessageService messageService,
                         AvatarService avatarService,
                         AttachmentService attachmentService,
                         ServerConfig config) {
        this.socket = socket;
        this.server = server;
        this.roomService = roomService;
        this.connectionLogStore = connectionLogStore;
        this.messageService = messageService;
        this.avatarService = avatarService;
        this.attachmentService = attachmentService;
        this.config = config;
        // accepted socket 的 getInetAddress() 是远端地址，即客户端 IP。
        this.ip = socket.getInetAddress() == null
                ? "unknown"
                : socket.getInetAddress().getHostAddress();
    }

    @Override
    public void run() {
        try {
            socket.setSoTimeout(HANDSHAKE_TIMEOUT_MS);
            reader = new BufferedReader(new InputStreamReader(
                    socket.getInputStream(), StandardCharsets.UTF_8));
            writer = new BufferedWriter(new OutputStreamWriter(
                    socket.getOutputStream(), StandardCharsets.UTF_8));

            String line;
            while (!closed.get() && (line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) {
                    continue;
                }
                JsonObject frame = JsonUtil.parseObject(line);
                if (frame == null) {
                    send(Protocol.error(Protocol.ERR_INVALID_FRAME,
                            "帧格式错误", null));
                    continue;
                }
                if (!handleFrame(frame)) {
                    break;
                }
            }
        } catch (SocketTimeoutException e) {
            if (state == State.CONNECTED) {
                send(Protocol.error(Protocol.ERR_HELLO_TIMEOUT,
                        "hello 超时", null));
                server.getEvents().onLog("hello 超时：" + ip);
            } else if (state == State.WAIT_AUTH) {
                send(Protocol.error(Protocol.ERR_AUTH_TIMEOUT,
                        "密钥验证超时", null));
                server.getEvents().onLog("密钥验证超时：" + ip);
            } else if (state == State.AUTHENTICATED) {
                server.getEvents().onLog("join 超时：" + ip);
            } else {
                server.getEvents().onLog("心跳超时：" + ip);
            }
        } catch (IOException e) {
            if (!closed.get()) {
                server.getEvents().onLog("客户端断开：" + ip + " - " + e.getMessage());
            }
        } catch (Exception e) {
            if (!closed.get()) {
                server.getEvents().onLog("客户端处理异常：" + e.getMessage());
            }
        } finally {
            close();
        }
    }

    private boolean handleFrame(JsonObject frame) {
        int version = frame.has("v") && frame.get("v").isJsonPrimitive()
                ? frame.get("v").getAsInt()
                : -1;
        String type = optString(frame, "type", "");
        String reqId = optString(frame, "reqId", "");

        if (version != Protocol.VERSION) {
            send(Protocol.error(Protocol.ERR_PROTOCOL,
                    "协议版本不兼容，请升级客户端", reqId));
            return false;
        }

        JsonObject data = frame.has("data") && frame.get("data").isJsonObject()
                ? frame.getAsJsonObject("data")
                : new JsonObject();

        if (state == State.CONNECTED) {
            if (Protocol.HELLO.equals(type)) {
                return handleHello(data, reqId);
            }
            send(Protocol.error(Protocol.ERR_AUTH_FAILED,
                    "请先完成密钥验证", reqId));
            return false;
        }

        if (state == State.WAIT_AUTH) {
            if (Protocol.AUTH_RESPONSE.equals(type)) {
                return handleAuthResponse(data, reqId);
            }
            send(Protocol.error(Protocol.ERR_AUTH_FAILED,
                    "请完成密钥验证", reqId));
            return false;
        }

        if (state == State.AUTHENTICATED) {
            if (Protocol.JOIN.equals(type)) {
                handleJoin(data, reqId);
                return !closed.get();
            }
            if (Protocol.PING.equals(type)) {
                handlePing(data, reqId);
                return true;
            }
            send(Protocol.error(Protocol.ERR_NOT_JOINED,
                    "请先 join", reqId));
            return true;
        }

        switch (type) {
            case Protocol.PING:
                handlePing(data, reqId);
                return true;
            case Protocol.LEAVE:
                return false;
            case Protocol.CHAT:
                handleChat(data, reqId);
                return true;
            case Protocol.HISTORY_REQ:
                handleHistory(data, reqId);
                return true;
            case Protocol.PROFILE_UPDATE:
                handleProfileUpdate(data, reqId);
                return true;
            case Protocol.AVATAR_REQ:
                handleAvatarRequest(data, reqId);
                return true;
            case Protocol.FILE_REQ:
                handleFileRequest(data, reqId);
                return true;
            case Protocol.RECALL_REQ:
                handleRecallRequest(data, reqId);
                return true;
            case Protocol.POKE_REQ:
                handlePoke(data, reqId);
                return true;
            case Protocol.ADMIN_AUTH_BEGIN:
                handleAdminAuthBegin(data, reqId);
                return true;
            case Protocol.ADMIN_AUTH_RESPONSE:
                handleAdminAuthResponse(data, reqId);
                return true;
            case Protocol.ROOM_ADMIN_UPDATE:
                handleRoomAdminUpdate(data, reqId);
                return true;
            default:
                send(Protocol.error(Protocol.ERR_UNSUPPORTED,
                        "不支持的请求类型", reqId));
                return true;
        }
    }

    private boolean handleHello(JsonObject data, String reqId) {
        int clientVersionCode = data.has("clientVersionCode")
                && data.get("clientVersionCode").isJsonPrimitive()
                ? data.get("clientVersionCode").getAsInt()
                : -1;
        String clientVersionName = optString(data, "clientVersionName", "");

        if (!VersionPolicy.isClientVersionSupported(clientVersionCode)) {
            send(Protocol.error(Protocol.ERR_CLIENT_VERSION,
                    "客户端版本不在允许范围（"
                            + VersionPolicy.MIN_CLIENT_VERSION_CODE + "–"
                            + VersionPolicy.MAX_CLIENT_VERSION_CODE + "）",
                    reqId));
            return false;
        }
        this.clientVersionCode = clientVersionCode;

        nonce = new byte[16];
        new SecureRandom().nextBytes(nonce);
        state = State.WAIT_AUTH;

        try {
            socket.setSoTimeout(HANDSHAKE_TIMEOUT_MS);
        } catch (IOException ignored) {
            // 保持原超时
        }

        JsonObject challenge = new JsonObject();
        challenge.addProperty("nonce",
                Base64.getEncoder().encodeToString(nonce));
        challenge.addProperty("serverVersion", ServerVersion.VERSION_NAME);
        challenge.addProperty("serverVersionCode", ServerVersion.VERSION_CODE);
        challenge.addProperty("minClientVersionCode",
                VersionPolicy.MIN_CLIENT_VERSION_CODE);
        challenge.addProperty("maxClientVersionCode",
                VersionPolicy.MAX_CLIENT_VERSION_CODE);
        send(Protocol.frame(Protocol.AUTH_CHALLENGE, reqId, challenge));

        String versionName = clientVersionName.length() > 40
                ? clientVersionName.substring(0, 40)
                : clientVersionName;
        server.getEvents().onLog("客户端版本 " + clientVersionCode
                + "（" + versionName + "）请求密钥验证");
        return true;
    }

    private boolean handleAuthResponse(JsonObject data, String reqId) {
        if (state != State.WAIT_AUTH || nonce == null) {
            return false;
        }
        String proof = optString(data, "proof", "");
        boolean valid = false;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(
                    config.authKey.getBytes(StandardCharsets.UTF_8),
                    "HmacSHA256"));
            byte[] expected = mac.doFinal(nonce);
            byte[] actual = Base64.getDecoder().decode(proof);
            valid = MessageDigest.isEqual(expected, actual);
        } catch (Exception e) {
            valid = false;
        }

        nonce = null;
        if (!valid) {
            send(Protocol.error(Protocol.ERR_AUTH_FAILED,
                    "连接密钥错误", reqId));
            return false;
        }

        state = State.AUTHENTICATED;
        try {
            socket.setSoTimeout(config.joinTimeoutMs);
        } catch (IOException ignored) {
            // 保持原超时
        }

        JsonObject ok = new JsonObject();
        ok.addProperty("serverVersion", ServerVersion.VERSION_NAME);
        ok.addProperty("serverVersionCode", ServerVersion.VERSION_CODE);
        ok.addProperty("protocolVersion", VersionPolicy.PROTOCOL_VERSION);
        ok.addProperty("minClientVersionCode",
                VersionPolicy.MIN_CLIENT_VERSION_CODE);
        ok.addProperty("maxClientVersionCode",
                VersionPolicy.MAX_CLIENT_VERSION_CODE);
        ok.addProperty("aiEnabled", server.isAiActive());
        send(Protocol.frame(Protocol.HELLO_OK, reqId, ok));
        server.getEvents().onLog("密钥验证通过：" + ip);
        return true;
    }

    private void handleJoin(JsonObject data, String reqId) {
        if (joined) {
            send(Protocol.error(Protocol.ERR_INVALID_INPUT,
                    "已经加入房间", reqId));
            return;
        }

        String nickname = optString(data, "nickname", "").trim();
        if (nickname.isEmpty() || nickname.length() > 16) {
            send(Protocol.error(Protocol.ERR_INVALID_INPUT,
                    "昵称需为 1–16 个字符", reqId));
            return;
        }
        String signature = optString(data, "signature", "").trim();
        if (signature.length() > 40) {
            signature = signature.substring(0, 40);
        }

        String avatar = optString(data, "avatar", "");
        AvatarService.AvatarData avatarData = null;
        if (!avatar.isEmpty()) {
            try {
                avatarData = avatarService.decode(avatar);
            } catch (IllegalArgumentException e) {
                send(Protocol.error(Protocol.ERR_AVATAR_TOO_LARGE,
                        e.getMessage(), reqId));
                return;
            }
        }

        String tag = optString(data, "clientTag", "");
        if (tag.length() > 128) {
            tag = tag.substring(0, 128);
        }
        clientTag = tag;

        String reportedIp = optString(data, "clientIp", "").trim();
        if (!reportedIp.isEmpty()) {
            ip = reportedIp;
        }
        server.getEvents().onLog("客户端连接，IP：" + ip);

        Member pending = server.claimPendingMember(tag);
        boolean rejoined = pending != null;
        if (rejoined) {
            member = pending;
            member.nickname = nickname;
            member.signature = signature;
        } else {
            member = roomService.join(nickname, signature, ip);
        }
        if (avatarData != null) {
            try {
                member.avatarVersion = avatarService.save(member.id, avatarData);
            } catch (Exception e) {
                if (rejoined) {
                    server.schedulePendingDisconnect(member, tag);
                } else {
                    roomService.leave(member.id);
                }
                member = null;
                send(Protocol.error(Protocol.ERR_INTERNAL,
                        "头像保存失败", reqId));
                return;
            }
        }
        try {
            socket.setSoTimeout(config.heartbeatTimeoutMs);
        } catch (IOException ignored) {
            // 保持原超时
        }

        // 必须先发送 join_ok，再把 joined 置为 true。
        // 否则其他客户端同时广播 member_list 时，可能抢在 join_ok 前到达新客户端。
        sendJoinOk(reqId);
        if (closed.get()) {
            if (rejoined) {
                server.schedulePendingDisconnect(member, tag);
            } else {
                roomService.leave(member.id);
            }
            return;
        }
        joined = true;
        state = State.JOINED;

        try {
            connectionLogStore.insert(member.id, ip, member.connectedAt);
        } catch (SQLException e) {
            server.getEvents().onLog("写入连接日志失败：" + e.getMessage());
        }

        server.broadcastMemberList();
        if (!rejoined) {
            server.broadcastSystemMessage(nickname + " 加入了群聊");
        }
        server.notifyMembersChanged();

        server.getEvents().onLog((rejoined ? nickname + " 重新加入" : nickname + " 加入")
                + "，临时 ID " + member.id
                + "，客户端 IP " + ip);
        if (!rejoined) {
            server.onMemberJoined(member);
        }
    }

    private void sendJoinOk(String reqId) {
        JsonObject data = new JsonObject();
        data.add("self", ChatServer.memberJson(member));

        Room room = roomService.getRoom();
        JsonObject roomJson = new JsonObject();
        roomJson.addProperty("name", room.name);
        roomJson.addProperty("announcement",
                room.announcement == null ? "" : room.announcement);
        roomJson.addProperty("lastSeq", messageService.lastSeq());
        roomJson.addProperty("avatarId", server.getRoomAvatarId());
        roomJson.addProperty("avatarVersion", server.getRoomAvatarVersion());
        data.add("room", roomJson);

        JsonArray members = new JsonArray();
        for (Member onlineMember : roomService.snapshot()) {
            members.add(ChatServer.memberJson(onlineMember));
        }
        data.add("members", members);

        JsonArray recent = new JsonArray();
        try {
            for (Message message : messageService.recent(config.historyRecent)) {
                recent.add(ChatServer.messageJsonForClient(
                        message,
                        true,
                        ChatServer.isSelf(message, clientTag),
                        clientVersionCode
                ));
            }
        } catch (Exception e) {
            server.getEvents().onLog("读取最近消息失败：" + e.getMessage());
        }
        data.add("recent", recent);

        JsonObject serverInfo = new JsonObject();
        serverInfo.addProperty("version", Protocol.VERSION);
        serverInfo.addProperty("versionName", ServerVersion.VERSION_NAME);
        serverInfo.addProperty("versionCode", ServerVersion.VERSION_CODE);
        serverInfo.addProperty("aiEnabled", server.isAiActive());
        serverInfo.addProperty("aiName", config.aiName);
        serverInfo.addProperty("adminEnabled", config.adminEnabled);
        serverInfo.addProperty("pushEnabled", config.pushEnabled);
        JsonObject features = new JsonObject();
        features.addProperty("image", config.featureImage);
        features.addProperty("voice", config.featureVoice);
        features.addProperty("emoji", config.featureEmoji);
        serverInfo.add("features", features);
        data.add("server", serverInfo);

        send(Protocol.frame(Protocol.JOIN_OK, reqId, data));
    }

    private void handleProfileUpdate(JsonObject data, String reqId) {
        String nickname = optString(data, "nickname", "").trim();
        if (!nickname.isEmpty()) {
            if (nickname.length() > 16) {
                send(Protocol.error(Protocol.ERR_INVALID_INPUT,
                        "昵称需为 1–16 个字符", reqId));
                return;
            }
            member.nickname = nickname;
        }
        if (data.has("signature")) {
            String signature = optString(data, "signature", "").trim();
            member.signature = signature.length() > 40
                    ? signature.substring(0, 40)
                    : signature;
        }

        String avatar = optString(data, "avatar", "");
        if (!avatar.isEmpty()) {
            try {
                AvatarService.AvatarData avatarData = avatarService.decode(avatar);
                member.avatarVersion = avatarService.save(member.id, avatarData);
            } catch (IllegalArgumentException e) {
                send(Protocol.error(Protocol.ERR_AVATAR_TOO_LARGE,
                        e.getMessage(), reqId));
                return;
            } catch (Exception e) {
                send(Protocol.error(Protocol.ERR_INTERNAL,
                        "头像保存失败", reqId));
                server.getEvents().onLog("头像保存失败：" + e.getMessage());
                return;
            }
        }

        server.broadcastMemberList();
        server.notifyMembersChanged();
        server.getEvents().onLog(member.nickname + " 更新了资料");
    }

    private void handleAvatarRequest(JsonObject data, String reqId) {
        String userId = optString(data, "userId", "");
        if (userId.isEmpty()) {
            send(Protocol.error(Protocol.ERR_INVALID_INPUT,
                    "缺少 userId", reqId));
            return;
        }
        int version = data.has("version") && data.get("version").isJsonPrimitive()
                ? data.get("version").getAsInt()
                : 0;
        try {
            Avatar avatar = avatarService.load(userId, version);
            JsonObject response = new JsonObject();
            response.addProperty("userId", userId);
            if (avatar == null) {
                response.addProperty("version", 0);
                response.addProperty("data", "");
            } else {
                response.addProperty("version", avatar.version);
                response.addProperty("data", avatarService.toDataUri(avatar));
            }
            send(Protocol.frame(Protocol.AVATAR_RES, reqId, response));
        } catch (Exception e) {
            send(Protocol.error(Protocol.ERR_DB,
                    "头像读取失败", reqId));
            server.getEvents().onLog("头像读取失败：" + e.getMessage());
        }
    }

    private void handleChat(JsonObject data, String reqId) {
        String clientMsgId = optString(data, "clientMsgId", "");
        if (clientMsgId.isEmpty()) {
            clientMsgId = "cm-" + UUID.randomUUID().toString()
                    .replace("-", "")
                    .substring(0, 12);
        }

        Ack cached = recentAcks.get(clientMsgId);
        if (cached != null) {
            sendAck(cached, reqId);
            return;
        }

        long now = System.currentTimeMillis();
        if (now - rateWindowStart > 5000) {
            rateWindowStart = now;
            rateCount = 0;
        }
        rateCount++;
        if (rateCount > 10) {
            send(Protocol.error(Protocol.ERR_RATE_LIMITED,
                    "发送过于频繁，请稍后再试", reqId));
            return;
        }

        String type = optString(data, "type", "text")
                .trim().toLowerCase(Locale.ROOT);
        if (type.isEmpty()) {
            type = "text";
        }
        String content = optString(data, "content", "").trim();

        if ("text".equals(type)) {
            if (content.isEmpty() || content.length() > 1000) {
                send(Protocol.error(Protocol.ERR_INVALID_INPUT,
                        "消息需为 1–1000 个字符", reqId));
                return;
            }
            try {
                Message message = messageService.createAndStore(
                        member, clientTag, content);
                acceptMessage(clientMsgId, message, reqId, content, message.seq);
                server.onChatMessage(member, message);
            } catch (Exception e) {
                send(Protocol.error(Protocol.ERR_DB,
                        "消息保存失败，请稍后重试", reqId));
                server.getEvents().onLog("消息落库失败：" + e.getMessage());
            }
            return;
        }

        if (!"image".equals(type) && !"emoji".equals(type)
                && !"voice".equals(type)) {
            send(Protocol.error(Protocol.ERR_UNSUPPORTED,
                    "不支持的消息类型", reqId));
            return;
        }
        if (content.length() > 1000) {
            send(Protocol.error(Protocol.ERR_INVALID_INPUT,
                    "消息文字不能超过 1000 个字符", reqId));
            return;
        }
        if ("voice".equals(type)) {
            content = normalizeVoiceDuration(content);
            if (content == null) {
                send(Protocol.error(Protocol.ERR_INVALID_INPUT,
                        "语音时长必须为 0–30000 毫秒", reqId));
                return;
            }
        }
        if (("image".equals(type) && !config.featureImage)
                || ("voice".equals(type) && !config.featureVoice)
                || ("emoji".equals(type) && !config.featureEmoji)) {
            send(Protocol.error(Protocol.ERR_FEATURE_DISABLED,
                    "房间未启用该功能", reqId));
            return;
        }

        AttachmentService.DecodedAttachment decoded;
        try {
            decoded = attachmentService.decode(
                    type,
                    optString(data, "data", ""),
                    optString(data, "mime", ""),
                    optString(data, "fileName", "")
            );
        } catch (IllegalArgumentException e) {
            send(Protocol.error(Protocol.ERR_INVALID_INPUT,
                    e.getMessage(), reqId));
            return;
        }

        Attachment saved = null;
        boolean stored = false;
        try {
            saved = attachmentService.save(
                    decoded.bytes, decoded.mime, decoded.fileName);
            Message message = messageService.createAndStore(
                    member, clientTag, content, type, saved);
            stored = true;
            acceptMessage(clientMsgId, message, reqId,
                    previewFor(type, saved.fileName), message.seq);
            attachmentService.requestCleanup();
        } catch (Exception e) {
            if (saved != null && !stored) {
                attachmentService.delete(saved.fileId);
            }
            send(Protocol.error(Protocol.ERR_DB,
                    "附件消息保存失败，请稍后重试", reqId));
            server.getEvents().onLog("附件消息落库失败：" + e.getMessage());
        }
    }

    private void acceptMessage(String clientMsgId,
                               Message message,
                               String reqId,
                               String logPreview,
                               long seq) {
        Ack ack = new Ack(clientMsgId, message);
        recentAcks.put(clientMsgId, ack);
        sendAck(ack, reqId);
        server.broadcastMessage(message, this);
        server.getEvents().onLog("[#" + seq + "] "
                + member.nickname + ": " + logPreview);
    }

    /**
     * 语音 content 约定为录音时长毫秒；旧客户端未传时按 0 处理。
     */
    private static String normalizeVoiceDuration(String content) {
        String value = content == null ? "" : content.trim();
        if (value.isEmpty()) {
            return "0";
        }
        try {
            long duration = Long.parseLong(value);
            if (duration < 0 || duration > 30000) {
                return null;
            }
            return String.valueOf(duration);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String previewFor(String type, String fileName) {
        String label;
        if ("image".equals(type)) {
            label = "[图片]";
        } else if ("emoji".equals(type)) {
            label = "[表情]";
        } else {
            label = "[语音]";
        }
        return fileName == null || fileName.isEmpty()
                ? label
                : label + " " + fileName;
    }

    private void handlePoke(JsonObject data, String reqId) {
        String targetId = optString(data, "targetId", "").trim();
        if (targetId.isEmpty()) {
            send(Protocol.error(Protocol.ERR_INVALID_INPUT,
                    "缺少 targetId", reqId));
            return;
        }
        Member target = roomService.find(targetId);
        if (target == null) {
            send(Protocol.error(Protocol.ERR_POKE_TARGET,
                    "目标用户不在线", reqId));
            return;
        }
        String signature = target.signature == null
                ? "" : target.signature.trim();
        String notice = member.nickname + " 拍了拍 " + target.nickname
                + (signature.isEmpty() ? "" : "，" + signature);
        server.broadcastSystemMessage(notice);
    }

    private void handleAdminAuthBegin(JsonObject data, String reqId) {
        if (!config.adminEnabled) {
            send(Protocol.error(Protocol.ERR_ADMIN_DISABLED,
                    "服务端未启用管理员模式", reqId));
            return;
        }
        if (config.adminSalt.isEmpty() || config.adminVerifier.isEmpty()) {
            send(Protocol.error(Protocol.ERR_ADMIN_DISABLED,
                    "服务端尚未设置管理员密钥", reqId));
            return;
        }

        long now = System.currentTimeMillis();
        if (adminAuthFailures >= ADMIN_MAX_FAILURES) {
            send(Protocol.error(Protocol.ERR_ADMIN_RATE_LIMIT,
                    "管理员密钥尝试次数过多，请重新连接", reqId));
            return;
        }
        if (now < adminAuthNextAttemptAt) {
            long waitMs = adminAuthNextAttemptAt - now;
            send(Protocol.error(Protocol.ERR_ADMIN_RATE_LIMIT,
                    "尝试过于频繁，请 " + ((waitMs + 999) / 1000)
                            + " 秒后重试", reqId));
            return;
        }

        adminNonce = AdminAuth.generateNonce();
        adminChallengeExpiresAt = now + ADMIN_CHALLENGE_TTL_MS;

        JsonObject challenge = new JsonObject();
        challenge.addProperty("salt", config.adminSalt);
        challenge.addProperty("iterations", config.adminIterations);
        challenge.addProperty("nonce", adminNonce);
        challenge.addProperty("expiresInMs", ADMIN_CHALLENGE_TTL_MS);
        challenge.addProperty("algorithm", "PBKDF2WithHmacSHA256");
        challenge.addProperty("proofAlgorithm", "HmacSHA256");
        send(Protocol.frame(Protocol.ADMIN_AUTH_CHALLENGE, reqId, challenge));
    }

    private void handleAdminAuthResponse(JsonObject data, String reqId) {
        if (!config.adminEnabled
                || config.adminSalt.isEmpty()
                || config.adminVerifier.isEmpty()) {
            send(Protocol.error(Protocol.ERR_ADMIN_DISABLED,
                    "服务端未启用管理员模式", reqId));
            return;
        }
        if (adminAuthFailures >= ADMIN_MAX_FAILURES) {
            send(Protocol.error(Protocol.ERR_ADMIN_RATE_LIMIT,
                    "管理员密钥尝试次数过多，请重新连接", reqId));
            return;
        }
        if (adminNonce.isEmpty()) {
            send(Protocol.error(Protocol.ERR_ADMIN_AUTH,
                    "管理员挑战已失效，请重新请求", reqId));
            return;
        }

        long now = System.currentTimeMillis();
        if (now > adminChallengeExpiresAt) {
            adminNonce = "";
            send(Protocol.error(Protocol.ERR_ADMIN_AUTH,
                    "管理员挑战已过期，请重新请求", reqId));
            return;
        }

        String nonce = adminNonce;
        adminNonce = "";
        String proof = optString(data, "proof", "");
        boolean valid = AdminAuth.verifyProof(
                config.adminVerifier, nonce, proof);
        if (!valid) {
            adminAuthFailures++;
            long delayMs = Math.min(
                    8000L, 1000L << Math.min(adminAuthFailures - 1, 3));
            adminAuthNextAttemptAt = now + delayMs;
            String nickname = member == null ? "" : member.nickname;
            server.getEvents().onLog("管理员密钥验证失败：" + nickname
                    + " / " + ip + "（第 " + adminAuthFailures + " 次）");
            if (adminAuthFailures >= ADMIN_MAX_FAILURES) {
                send(Protocol.error(Protocol.ERR_ADMIN_RATE_LIMIT,
                        "管理员密钥尝试次数过多，请重新连接", reqId));
            } else {
                send(Protocol.error(Protocol.ERR_ADMIN_AUTH,
                        "管理员密钥错误", reqId));
            }
            return;
        }

        if (member == null) {
            send(Protocol.error(Protocol.ERR_NOT_JOINED,
                    "请先加入房间", reqId));
            return;
        }

        adminAuthFailures = 0;
        adminAuthNextAttemptAt = 0L;
        member.admin = true;

        JsonObject ok = new JsonObject();
        ok.addProperty("admin", true);
        send(Protocol.frame(Protocol.ADMIN_AUTH_OK, reqId, ok));
        server.broadcastMemberList();
        server.notifyMembersChanged();
        server.getEvents().onLog(member.nickname
                + " 已绑定管理员身份，IP：" + ip);
    }

    private void handleRoomAdminUpdate(JsonObject data, String reqId) {
        if (!config.adminEnabled) {
            send(Protocol.error(Protocol.ERR_ADMIN_DISABLED,
                    "服务端未启用管理员模式", reqId));
            return;
        }
        if (member == null || !member.admin) {
            send(Protocol.error(Protocol.ERR_ADMIN_FORBIDDEN,
                    "仅管理员可以修改房间信息", reqId));
            return;
        }
        ChatServer.RoomUpdateResult result = server.updateRoomInfo(
                member,
                optString(data, "name", ""),
                optString(data, "announcement", ""),
                optString(data, "avatar", "")
        );
        if (!result.success) {
            send(Protocol.error(result.errorCode, result.message, reqId));
        }
    }

    private void handleRecallRequest(JsonObject data, String reqId) {
        String msgId = optString(data, "msgId", "").trim();
        if (msgId.isEmpty()) {
            send(Protocol.error(Protocol.ERR_INVALID_INPUT,
                    "缺少 msgId", reqId));
            return;
        }
        MessageService.RecallResult result =
                server.recallByMsgId(msgId, clientTag);
        if (!result.success) {
            send(Protocol.error(result.errorCode,
                    result.errorMessage, reqId));
        }
    }

    private void handleFileRequest(JsonObject data, String reqId) {
        String fileId = optString(data, "fileId", "").trim();
        if (!AttachmentService.isValidFileId(fileId)) {
            send(Protocol.error(Protocol.ERR_INVALID_INPUT,
                    "fileId 无效", reqId));
            return;
        }

        try {
            AttachmentService.FileData file = attachmentService.load(fileId);
            JsonObject response = new JsonObject();
            response.addProperty("fileId", fileId);
            if (file == null) {
                response.addProperty("expired", true);
                response.addProperty("data", "");
            } else {
                Attachment attachment = file.attachment;
                response.addProperty("fileName",
                        attachment.fileName == null ? "" : attachment.fileName);
                response.addProperty("mime",
                        attachment.mime == null ? "" : attachment.mime);
                response.addProperty("size", attachment.size);
                response.addProperty("expired", file.expired);
                response.addProperty("data", file.expired
                        ? ""
                        : Base64.getEncoder().encodeToString(file.bytes));
            }
            send(Protocol.frame(Protocol.FILE_RES, reqId, response));
        } catch (IllegalArgumentException e) {
            send(Protocol.error(Protocol.ERR_INVALID_INPUT,
                    e.getMessage(), reqId));
        } catch (Exception e) {
            send(Protocol.error(Protocol.ERR_DB,
                    "附件读取失败，请稍后重试", reqId));
            server.getEvents().onLog("附件读取失败：" + e.getMessage());
        }
    }

    private void handleHistory(JsonObject data, String reqId) {
        try {
            int limit = data.has("limit") && data.get("limit").isJsonPrimitive()
                    ? data.get("limit").getAsInt()
                    : config.historyRecent;
            limit = Math.max(1, Math.min(limit, config.historyMaxFetch));

            List<Message> messages;
            if (data.has("afterSeq") && data.get("afterSeq").isJsonPrimitive()) {
                long afterSeq = data.get("afterSeq").getAsLong();
                messages = messageService.after(afterSeq, limit);
            } else {
                long beforeSeq = data.has("beforeSeq")
                        && data.get("beforeSeq").isJsonPrimitive()
                        ? data.get("beforeSeq").getAsLong()
                        : Long.MAX_VALUE;
                messages = messageService.before(beforeSeq, limit);
            }

            JsonArray array = new JsonArray();
            for (Message message : messages) {
                array.add(ChatServer.messageJsonForClient(
                        message,
                        true,
                        ChatServer.isSelf(message, clientTag),
                        clientVersionCode
                ));
            }

            JsonObject response = new JsonObject();
            response.add("messages", array);
            response.addProperty("hasMore", messages.size() == limit);
            send(Protocol.frame(Protocol.HISTORY_RES, reqId, response));
        } catch (Exception e) {
            send(Protocol.error(Protocol.ERR_DB,
                    "读取历史失败，请稍后重试", reqId));
            server.getEvents().onLog("读取历史失败：" + e.getMessage());
        }
    }

    private void sendAck(Ack ack, String reqId) {
        JsonObject data = new JsonObject();
        data.addProperty("clientMsgId", ack.clientMsgId);
        data.addProperty("msgId", ack.msgId);
        data.addProperty("seq", ack.seq);
        data.addProperty("ts", ack.ts);
        data.addProperty("type", ack.type);
        if (ack.fileId != null && !ack.fileId.isEmpty()) {
            data.addProperty("fileId", ack.fileId);
            data.addProperty("fileName",
                    ack.fileName == null ? "" : ack.fileName);
            data.addProperty("fileMime",
                    ack.fileMime == null ? "" : ack.fileMime);
            data.addProperty("fileSize", ack.fileSize);
            data.addProperty("fileExpired", ack.fileExpired);
        }
        send(Protocol.frame(Protocol.MESSAGE_ACK, reqId, data));
    }

    private void handlePing(JsonObject data, String reqId) {
        long t = data.has("t") && data.get("t").isJsonPrimitive()
                ? data.get("t").getAsLong()
                : System.currentTimeMillis();
        JsonObject pong = new JsonObject();
        pong.addProperty("t", t);
        send(Protocol.frame(Protocol.PONG, reqId, pong));
    }

    private static final class Ack {
        final String clientMsgId;
        final String msgId;
        final long seq;
        final long ts;
        final String type;
        final String fileId;
        final String fileName;
        final String fileMime;
        final long fileSize;
        final boolean fileExpired;

        Ack(String clientMsgId, Message message) {
            this.clientMsgId = clientMsgId;
            this.msgId = message.msgUuid;
            this.seq = message.seq;
            this.ts = message.createdAt;
            this.type = message.type;
            this.fileId = message.fileId;
            this.fileName = message.fileName;
            this.fileMime = message.fileMime;
            this.fileSize = message.fileSize;
            this.fileExpired = message.fileExpired;
        }
    }

    public void send(JsonObject frame) {
        sendLine(JsonUtil.stringify(frame));
    }

    public void sendLine(String line) {
        if (closed.get()) {
            return;
        }
        boolean failed = false;
        synchronized (writeLock) {
            if (writer == null || closed.get()) {
                return;
            }
            try {
                writer.write(line);
                writer.write('\n');
                writer.flush();
            } catch (IOException e) {
                failed = true;
            }
        }
        // 不在持有 writeLock 时 close：close 会广播给其他 handler，
        // 两个 handler 同时失败时可能互相等待对方的写锁。
        if (failed) {
            close();
        }
    }

    public boolean isJoined() {
        return joined && !closed.get();
    }

    public String getClientTag() {
        return clientTag;
    }

    public int getClientVersionCode() {
        return clientVersionCode;
    }

    public boolean supportsVoice() {
        return clientVersionCode >= VersionPolicy.VOICE_MIN_CLIENT_VERSION_CODE;
    }

    public Member getMember() {
        return member;
    }

    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }

        boolean wasJoined = joined;
        joined = false;
        try {
            socket.close();
        } catch (IOException ignored) {
            // 忽略
        }

        if (wasJoined && member != null) {
            try {
                connectionLogStore.closeConnection(
                        member.id, System.currentTimeMillis());
            } catch (SQLException e) {
                server.getEvents().onLog("更新连接日志失败：" + e.getMessage());
            }
            server.unregister(this);
            if (server.isMemberActive(member.id)) {
                server.getEvents().onLog(member.nickname
                        + " 旧连接断开，但同一成员已有新连接，忽略延迟离线");
            } else {
                server.schedulePendingDisconnect(member, clientTag);
                server.getEvents().onLog(member.nickname
                        + " 连接断开，" + config.reconnectGraceMs
                        + " 毫秒内保留在线状态，临时 ID " + member.id);
            }
        } else {
            server.unregister(this);
        }
    }

    private static String optString(JsonObject object, String key, String defaultValue) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) {
            return defaultValue;
        }
        try {
            return object.get(key).getAsString();
        } catch (Exception e) {
            return defaultValue;
        }
    }
}
