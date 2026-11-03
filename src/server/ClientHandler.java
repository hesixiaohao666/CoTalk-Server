package server;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
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
    private long rateWindowStart;
    private int rateCount;

    public ClientHandler(Socket socket,
                         ChatServer server,
                         RoomService roomService,
                         ConnectionLogStore connectionLogStore,
                         MessageService messageService,
                         AvatarService avatarService,
                         ServerConfig config) {
        this.socket = socket;
        this.server = server;
        this.roomService = roomService;
        this.connectionLogStore = connectionLogStore;
        this.messageService = messageService;
        this.avatarService = avatarService;
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

        member = roomService.join(nickname, ip);
        if (avatarData != null) {
            try {
                member.avatarVersion = avatarService.save(member.id, avatarData);
            } catch (Exception e) {
                roomService.leave(member.id);
                member = null;
                send(Protocol.error(Protocol.ERR_INTERNAL,
                        "头像保存失败", reqId));
                return;
            }
        }
        joined = true;
        state = State.JOINED;

        try {
            socket.setSoTimeout(config.heartbeatTimeoutMs);
        } catch (IOException ignored) {
            // 保持原超时
        }

        try {
            connectionLogStore.insert(member.id, ip, member.connectedAt);
        } catch (SQLException e) {
            server.getEvents().onLog("写入连接日志失败：" + e.getMessage());
        }

        sendJoinOk(reqId);
        server.broadcastMemberList();
        server.broadcastSystemMessage(nickname + " 加入了群聊");
        server.notifyMembersChanged();

        server.getEvents().onLog(nickname + " 加入，临时 ID " + member.id
                + "，客户端 IP " + ip);
        server.onMemberJoined(member);
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
        data.add("room", roomJson);

        JsonArray members = new JsonArray();
        for (Member onlineMember : roomService.snapshot()) {
            members.add(ChatServer.memberJson(onlineMember));
        }
        data.add("members", members);

        JsonArray recent = new JsonArray();
        try {
            for (Message message : messageService.recent(config.historyRecent)) {
                recent.add(ChatServer.messageJson(
                        message,
                        true,
                        ChatServer.isSelf(message, clientTag)
                ));
            }
        } catch (Exception e) {
            server.getEvents().onLog("读取最近消息失败：" + e.getMessage());
        }
        data.add("recent", recent);

        JsonObject serverInfo = new JsonObject();
        serverInfo.addProperty("version", Protocol.VERSION);
        serverInfo.addProperty("aiEnabled", server.isAiActive());
        serverInfo.addProperty("aiName", config.aiName);
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

        String content = optString(data, "content", "").trim();
        if (content.isEmpty() || content.length() > 1000) {
            send(Protocol.error(Protocol.ERR_INVALID_INPUT,
                    "消息需为 1–1000 个字符", reqId));
            return;
        }

        try {
            Message message = messageService.createAndStore(
                    member, clientTag, content);
            Ack ack = new Ack(
                    clientMsgId, message.msgUuid, message.seq, message.createdAt);
            recentAcks.put(clientMsgId, ack);

            sendAck(ack, reqId);
            server.broadcastMessage(message, this);

            String preview = content.length() > 40
                    ? content.substring(0, 40) + "…"
                    : content;
            server.getEvents().onLog(member.nickname + ": " + preview);
            server.onChatMessage(member, message);
        } catch (Exception e) {
            send(Protocol.error(Protocol.ERR_DB,
                    "消息保存失败，请稍后重试", reqId));
            server.getEvents().onLog("消息落库失败：" + e.getMessage());
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
                array.add(ChatServer.messageJson(
                        message,
                        true,
                        ChatServer.isSelf(message, clientTag)
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

        Ack(String clientMsgId, String msgId, long seq, long ts) {
            this.clientMsgId = clientMsgId;
            this.msgId = msgId;
            this.seq = seq;
            this.ts = ts;
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
            roomService.leave(member.id);
            try {
                connectionLogStore.closeConnection(
                        member.id, System.currentTimeMillis());
            } catch (SQLException e) {
                server.getEvents().onLog("更新连接日志失败：" + e.getMessage());
            }
            server.unregister(this);
            server.broadcastMemberList();
            server.broadcastSystemMessage(member.nickname + " 离开了群聊");
            server.notifyMembersChanged();
            server.getEvents().onLog(member.nickname + " 离开，临时 ID " + member.id);
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
