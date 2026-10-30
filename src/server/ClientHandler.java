package server;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import model.Member;
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
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 单客户端连接：按行读取 NDJSON，处理 join/leave/ping。
 * 消息、历史、资料、头像在后续 Step 扩展。
 */
public class ClientHandler implements Runnable {

    private final Socket socket;
    private final ChatServer server;
    private final RoomService roomService;
    private final ConnectionLogStore connectionLogStore;
    private final ServerConfig config;
    private final String ip;
    private final Object writeLock = new Object();
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private BufferedReader reader;
    private BufferedWriter writer;
    private volatile boolean joined;
    private Member member;
    private String clientTag = "";

    public ClientHandler(Socket socket,
                         ChatServer server,
                         RoomService roomService,
                         ConnectionLogStore connectionLogStore,
                         ServerConfig config) {
        this.socket = socket;
        this.server = server;
        this.roomService = roomService;
        this.connectionLogStore = connectionLogStore;
        this.config = config;
        this.ip = socket.getInetAddress() == null
                ? "unknown"
                : socket.getInetAddress().getHostAddress();
    }

    @Override
    public void run() {
        try {
            socket.setSoTimeout(config.joinTimeoutMs);
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
            if (!joined) {
                server.getEvents().onLog("连接超时未 join：" + ip);
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
            send(Protocol.error(Protocol.ERR_VERSION,
                    "协议版本不匹配", reqId));
            return false;
        }

        JsonObject data = frame.has("data") && frame.get("data").isJsonObject()
                ? frame.getAsJsonObject("data")
                : new JsonObject();

        if (!joined) {
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
            case Protocol.HISTORY_REQ:
            case Protocol.PROFILE_UPDATE:
            case Protocol.AVATAR_REQ:
                send(Protocol.error(Protocol.ERR_UNSUPPORTED,
                        "该功能将在后续 Step 实现", reqId));
                return true;
            default:
                send(Protocol.error(Protocol.ERR_UNSUPPORTED,
                        "不支持的请求类型", reqId));
                return true;
        }
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
        long maxBase64Length = (long) config.avatarMaxBytes * 4L / 3L + 16L;
        if (!avatar.isEmpty() && avatar.length() > maxBase64Length) {
            send(Protocol.error(Protocol.ERR_AVATAR_TOO_LARGE,
                    "头像过大", reqId));
            return;
        }

        String tag = optString(data, "clientTag", "");
        if (tag.length() > 128) {
            tag = tag.substring(0, 128);
        }
        clientTag = tag;

        member = roomService.join(nickname, ip);
        joined = true;

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
        server.broadcastSystem(nickname + " 加入了群聊");
        server.notifyMembersChanged();

        server.getEvents().onLog(nickname + " 加入，临时 ID " + member.id
                + "，IP " + ip);
    }

    private void sendJoinOk(String reqId) {
        JsonObject data = new JsonObject();
        data.add("self", ChatServer.memberJson(member));

        Room room = roomService.getRoom();
        JsonObject roomJson = new JsonObject();
        roomJson.addProperty("name", room.name);
        roomJson.addProperty("announcement",
                room.announcement == null ? "" : room.announcement);
        roomJson.addProperty("lastSeq", 0);
        data.add("room", roomJson);

        JsonArray members = new JsonArray();
        for (Member onlineMember : roomService.snapshot()) {
            members.add(ChatServer.memberJson(onlineMember));
        }
        data.add("members", members);
        data.add("recent", new JsonArray());

        JsonObject serverInfo = new JsonObject();
        serverInfo.addProperty("version", Protocol.VERSION);
        serverInfo.addProperty("aiEnabled", config.aiEnabled);
        serverInfo.addProperty("aiName", config.aiName);
        data.add("server", serverInfo);

        send(Protocol.frame(Protocol.JOIN_OK, reqId, data));
    }

    private void handlePing(JsonObject data, String reqId) {
        long t = data.has("t") && data.get("t").isJsonPrimitive()
                ? data.get("t").getAsLong()
                : System.currentTimeMillis();
        JsonObject pong = new JsonObject();
        pong.addProperty("t", t);
        send(Protocol.frame(Protocol.PONG, reqId, pong));
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
            server.broadcastSystem(member.nickname + " 离开了群聊");
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
