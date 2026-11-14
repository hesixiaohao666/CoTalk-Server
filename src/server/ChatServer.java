package server;

import ai.AiReplySink;
import ai.AiResponder;
import ai.NoopAiResponder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import model.Member;
import model.Message;
import model.Room;
import protocol.Protocol;
import store.ConnectionLogStore;
import store.RoomStore;
import util.JsonUtil;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * TCP 服务端：连接管理、成员广播、消息广播与 AI 钩子。
 */
public class ChatServer implements AiReplySink {

    private static final class PendingDisconnect {
        final Member member;
        final String clientTag;
        ScheduledFuture<?> task;

        PendingDisconnect(Member member, String clientTag) {
            this.member = member;
            this.clientTag = clientTag;
        }
    }

    private final ServerConfig config;
    private final RoomService roomService;
    private final ConnectionLogStore connectionLogStore;
    private final MessageService messageService;
    private final AvatarService avatarService;
    private final AttachmentService attachmentService;
    private final ServerEvents events;
    private final Object roomUpdateLock = new Object();
    private volatile AiResponder aiResponder = new NoopAiResponder();
    private volatile String roomAvatarId = "";
    private volatile int roomAvatarVersion;
    private volatile RoomStore roomStore;
    private volatile Path configPath = ServerConfigStore.DEFAULT_PATH;

    private final Set<ClientHandler> handlers = ConcurrentHashMap.newKeySet();
    private final ExecutorService clientPool = Executors.newCachedThreadPool();
    private final Map<String, PendingDisconnect> pendingDisconnects =
            new ConcurrentHashMap<>();
    private final ScheduledExecutorService pendingScheduler =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(
                        runnable, "cotalk-reconnect-grace");
                thread.setDaemon(true);
                return thread;
            });

    private volatile boolean running;
    private ServerSocket serverSocket;
    private Thread acceptThread;

    public ChatServer(ServerConfig config,
                      RoomService roomService,
                      ConnectionLogStore connectionLogStore,
                      MessageService messageService,
                      AvatarService avatarService,
                      AttachmentService attachmentService,
                      ServerEvents events) {
        this.config = config;
        this.roomService = roomService;
        this.connectionLogStore = connectionLogStore;
        this.messageService = messageService;
        this.avatarService = avatarService;
        this.attachmentService = attachmentService;
        this.events = events;
    }

    public void setAiResponder(AiResponder responder) {
        if (responder != null) {
            this.aiResponder = responder;
        }
    }

    public boolean isAiActive() {
        AiResponder responder = aiResponder;
        return responder != null && responder.isActive();
    }

    public void setRoomAvatarInfo(String avatarId, int avatarVersion) {
        this.roomAvatarId = avatarId == null ? "" : avatarId;
        this.roomAvatarVersion = Math.max(0, avatarVersion);
    }

    public void setRoomStore(RoomStore roomStore) {
        this.roomStore = roomStore;
    }

    public void setConfigPath(Path configPath) {
        this.configPath = configPath == null
                ? ServerConfigStore.DEFAULT_PATH : configPath;
    }

    public static final class RoomUpdateResult {
        public final boolean success;
        public final int errorCode;
        public final String message;
        public final String name;
        public final String announcement;
        public final String avatarId;
        public final int avatarVersion;

        private RoomUpdateResult(boolean success,
                                 int errorCode,
                                 String message,
                                 String name,
                                 String announcement,
                                 String avatarId,
                                 int avatarVersion) {
            this.success = success;
            this.errorCode = errorCode;
            this.message = message;
            this.name = name;
            this.announcement = announcement;
            this.avatarId = avatarId;
            this.avatarVersion = avatarVersion;
        }

        static RoomUpdateResult failure(int code, String message) {
            return new RoomUpdateResult(
                    false, code, message, "", "", "", 0);
        }
    }

    /**
     * 管理员修改房间名称/公告/头像，持久化后向所有在线客户端广播 room_update。
     */
    public RoomUpdateResult updateRoomInfo(Member actor,
                                           String name,
                                           String announcement,
                                           String avatarData) {
        if (!config.adminEnabled) {
            return RoomUpdateResult.failure(
                    Protocol.ERR_ADMIN_DISABLED, "服务端未启用管理员模式");
        }
        if (actor == null || !actor.admin) {
            return RoomUpdateResult.failure(
                    Protocol.ERR_ADMIN_FORBIDDEN, "仅管理员可以修改房间信息");
        }
        String newName = name == null ? "" : name.trim();
        if (newName.isEmpty() || newName.length() > 32) {
            return RoomUpdateResult.failure(
                    Protocol.ERR_INVALID_INPUT, "房间名称需为 1–32 个字符");
        }
        String newAnnouncement = announcement == null
                ? "" : announcement.trim();
        if (newAnnouncement.length() > 200) {
            return RoomUpdateResult.failure(
                    Protocol.ERR_INVALID_INPUT, "房间公告不能超过 200 个字符");
        }
        if (roomStore == null) {
            return RoomUpdateResult.failure(
                    Protocol.ERR_ROOM_UPDATE, "房间存储尚未初始化");
        }

        AvatarService.AvatarData avatar = null;
        if (avatarData != null && !avatarData.trim().isEmpty()) {
            try {
                avatar = avatarService.decode(avatarData);
            } catch (IllegalArgumentException e) {
                return RoomUpdateResult.failure(
                        Protocol.ERR_ROOM_UPDATE, e.getMessage());
            }
        }

        synchronized (roomUpdateLock) {
            int newAvatarVersion = roomAvatarVersion;
            if (avatar != null) {
                try {
                    newAvatarVersion = avatarService.save(
                            RoomAvatarService.ROOM_AVATAR_ID, avatar);
                } catch (Exception e) {
                    events.onLog("保存房间头像失败：" + e.getMessage());
                    return RoomUpdateResult.failure(
                            Protocol.ERR_ROOM_UPDATE, "房间头像保存失败");
                }
            }

            try {
                roomStore.update(newName, newAnnouncement);
            } catch (Exception e) {
                events.onLog("写入房间信息失败：" + e.getMessage());
                return RoomUpdateResult.failure(
                        Protocol.ERR_ROOM_UPDATE, "房间信息保存失败");
            }

            String oldName = config.roomName;
            String oldAnnouncement = config.roomAnnouncement;
            config.roomName = newName;
            config.roomAnnouncement = newAnnouncement;
            try {
                ServerConfigStore.save(config, configPath);
            } catch (Exception e) {
                config.roomName = oldName;
                config.roomAnnouncement = oldAnnouncement;
                try {
                    roomStore.update(oldName, oldAnnouncement);
                } catch (Exception ignored) {
                    // 回滚失败时保留数据库最新值
                }
                events.onLog("写入房间配置失败：" + e.getMessage());
                return RoomUpdateResult.failure(
                        Protocol.ERR_ROOM_UPDATE, "房间配置保存失败");
            }

            Room room = roomService.getRoom();
            room.name = newName;
            room.announcement = newAnnouncement;
            if (avatar != null) {
                roomAvatarId = RoomAvatarService.ROOM_AVATAR_ID;
                roomAvatarVersion = newAvatarVersion;
            }

            broadcastRoomUpdate();
            events.onLog((actor.nickname == null ? "管理员" : actor.nickname)
                    + " 更新了房间信息");
            return new RoomUpdateResult(
                    true, 0, "",
                    newName, newAnnouncement,
                    roomAvatarId, roomAvatarVersion);
        }
    }

    /**
     * 管理员密钥刷新或管理员模式关闭时，解除所有在线客户端的管理员身份。
     */
    public int revokeAllAdmins(String reason) {
        int revoked = 0;
        for (Member member : roomService.snapshot()) {
            if (member.admin) {
                member.admin = false;
                revoked++;
            }
        }
        if (revoked > 0) {
            broadcastMemberList();
            notifyMembersChanged();
            broadcastAdminRevoked(reason);
            events.onLog("已解除 " + revoked
                    + " 个客户端的管理员绑定"
                    + (reason == null || reason.isEmpty()
                    ? "" : "：" + reason));
        }
        return revoked;
    }

    public void broadcastAdminRevoked(String reason) {
        if (!running) {
            return;
        }
        JsonObject data = new JsonObject();
        data.addProperty("reason",
                reason == null ? "" : reason);
        data.addProperty("ts", System.currentTimeMillis());
        String line = JsonUtil.stringify(
                Protocol.frame(Protocol.ADMIN_REVOKED, null, data));
        for (ClientHandler handler : new ArrayList<>(handlers)) {
            if (handler.isJoined()) {
                handler.sendLine(line);
            }
        }
    }

    public void broadcastRoomUpdate() {
        if (!running) {
            return;
        }
        Room room = roomService.getRoom();
        JsonObject data = new JsonObject();
        data.addProperty("name",
                room.name == null ? "" : room.name);
        data.addProperty("announcement",
                room.announcement == null ? "" : room.announcement);
        data.addProperty("avatarId", roomAvatarId);
        data.addProperty("avatarVersion", roomAvatarVersion);
        String line = JsonUtil.stringify(
                Protocol.frame(Protocol.ROOM_UPDATE, null, data));
        for (ClientHandler handler : new ArrayList<>(handlers)) {
            if (handler.isJoined()) {
                handler.sendLine(line);
            }
        }
    }

    public String getRoomAvatarId() {
        return roomAvatarId;
    }

    public int getRoomAvatarVersion() {
        return roomAvatarVersion;
    }

    public void start() throws IOException {
        if (running) {
            return;
        }
        serverSocket = new ServerSocket();
        serverSocket.setReuseAddress(true);
        serverSocket.bind(new InetSocketAddress(config.port));
        running = true;

        acceptThread = new Thread(this::acceptLoop, "cotalk-accept");
        acceptThread.start();

        events.onStatusChanged("运行中");
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket socket = serverSocket.accept();
                if (handlers.size() >= config.maxClients) {
                    events.onLog("拒绝连接：在线连接数已达上限 "
                            + config.maxClients);
                    socket.close();
                    continue;
                }
                socket.setTcpNoDelay(true);
                events.onServerAddress(socket.getLocalAddress().getHostAddress());
                ClientHandler handler = new ClientHandler(
                        socket, this, roomService, connectionLogStore,
                        messageService, avatarService, attachmentService, config);
                handlers.add(handler);
                clientPool.execute(handler);
            } catch (IOException e) {
                if (running) {
                    events.onLog("接受连接失败：" + e.getMessage());
                }
            }
        }
    }

    public void stop() {
        if (!running && serverSocket == null) {
            return;
        }
        running = false;
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (IOException ignored) {
            // 关闭时忽略
        }
        serverSocket = null;

        for (ClientHandler handler : new ArrayList<>(handlers)) {
            handler.close();
        }
        handlers.clear();
        pendingScheduler.shutdownNow();
        pendingDisconnects.clear();
        clientPool.shutdownNow();
        if (aiResponder != null) {
            aiResponder.shutdown();
        }

        events.onMembersChanged(Collections.emptyList());
        events.onStatusChanged("已停止");
        events.onLog("服务已停止");
    }

    /**
     * 断开后在配置的宽限时间内保留成员；同 clientTag 重连可复用原成员。
     */
    public void schedulePendingDisconnect(Member member, String clientTag) {
        if (member == null) {
            return;
        }
        String key = pendingKey(clientTag, member);
        PendingDisconnect pending = new PendingDisconnect(member, clientTag);
        synchronized (pendingDisconnects) {
            PendingDisconnect previous = pendingDisconnects.put(key, pending);
            if (previous != null && previous.task != null) {
                previous.task.cancel(false);
            }
            try {
                pending.task = pendingScheduler.schedule(
                        () -> expirePending(key, pending),
                        config.reconnectGraceMs, TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                pendingDisconnects.remove(key, pending);
            }
        }
    }

    public boolean isMemberActive(String memberId) {
        if (memberId == null) {
            return false;
        }
        for (ClientHandler handler : handlers) {
            Member member = handler.getMember();
            if (member != null && memberId.equals(member.id)
                    && handler.isJoined()) {
                return true;
            }
        }
        return false;
    }

    public Member claimPendingMember(String clientTag) {
        if (clientTag == null || clientTag.isEmpty()) {
            return null;
        }
        PendingDisconnect pending =
                pendingDisconnects.remove("tag:" + clientTag);
        if (pending == null) {
            return null;
        }
        if (pending.task != null) {
            pending.task.cancel(false);
        }
        return roomService.isOnline(pending.member.id)
                ? pending.member : null;
    }

    private void expirePending(String key, PendingDisconnect pending) {
        if (!pendingDisconnects.remove(key, pending)) {
            return;
        }
        if (!roomService.isOnline(pending.member.id)) {
            return;
        }
        roomService.leave(pending.member.id);
        broadcastMemberList();
        broadcastSystemMessage(pending.member.nickname + " 离开了群聊");
        notifyMembersChanged();
        events.onLog(pending.member.nickname + " 离开，临时 ID "
                + pending.member.id);
    }

    private static String pendingKey(String clientTag, Member member) {
        return clientTag == null || clientTag.isEmpty()
                ? "member:" + member.id
                : "tag:" + clientTag;
    }

    public boolean isRunning() {
        return running;
    }

    public ServerConfig getConfig() {
        return config;
    }

    public long getReconnectGraceMs() {
        return config.reconnectGraceMs;
    }

    public RoomService getRoomService() {
        return roomService;
    }

    public MessageService getMessageService() {
        return messageService;
    }

    public ServerEvents getEvents() {
        return events;
    }

    public void unregister(ClientHandler handler) {
        handlers.remove(handler);
    }

    public void notifyMembersChanged() {
        events.onMembersChanged(roomService.snapshot());
    }

    public void broadcastMemberList() {
        if (!running) {
            return;
        }
        JsonArray members = new JsonArray();
        for (Member member : roomService.snapshot()) {
            members.add(memberJson(member));
        }
        JsonObject data = new JsonObject();
        data.add("members", members);
        String line = JsonUtil.stringify(
                Protocol.frame(Protocol.MEMBER_LIST, null, data));

        for (ClientHandler handler : new ArrayList<>(handlers)) {
            if (handler.isJoined()) {
                handler.sendLine(line);
            }
        }
    }

    public void broadcastSystem(String content) {
        if (!running) {
            return;
        }
        JsonObject data = new JsonObject();
        data.addProperty("content", content);
        data.addProperty("ts", System.currentTimeMillis());
        String line = JsonUtil.stringify(
                Protocol.frame(Protocol.SYSTEM, null, data));

        for (ClientHandler handler : new ArrayList<>(handlers)) {
            if (handler.isJoined()) {
                handler.sendLine(line);
            }
        }
    }

    /**
     * 持久化的系统消息（加入/离开），走 message(type=system)。
     */
    public void broadcastSystemMessage(String content) {
        if (!running) {
            return;
        }
        try {
            Message message = messageService.appendSystem(content);
            events.onLog("[#" + message.seq + "] 系统: " + content);
            broadcastMessage(message, null);
        } catch (Exception e) {
            events.onLog("写入系统消息失败：" + e.getMessage());
            broadcastSystem(content);
        }
    }

    public MessageService.RecallResult recallByMsgId(String msgId,
                                                     String clientTag) {
        MessageService.RecallResult result =
                messageService.recallByMsgId(msgId, clientTag);
        if (result.success) {
            broadcastRecall(result);
        }
        return result;
    }

    public MessageService.RecallResult forceRecallBySeq(long seq) {
        MessageService.RecallResult result =
                messageService.forceRecallBySeq(seq);
        if (result.success) {
            broadcastRecall(result);
        }
        return result;
    }

    private void broadcastRecall(MessageService.RecallResult result) {
        if (!running || result.message == null) {
            return;
        }
        JsonObject data = new JsonObject();
        data.addProperty("msgId", result.message.msgUuid);
        data.addProperty("seq", result.message.seq);
        data.addProperty("notice", result.notice);
        data.addProperty("ts", result.message.createdAt);
        String line = JsonUtil.stringify(
                Protocol.frame(Protocol.MESSAGE_RECALL, null, data));
        for (ClientHandler handler : new ArrayList<>(handlers)) {
            if (handler.isJoined()) {
                handler.sendLine(line);
            }
        }
        events.onLog("[#" + result.message.seq + "] 撤回："
                + result.notice);
    }

    public void onChatMessage(Member sender, Message message) {
        AiResponder responder = aiResponder;
        if (responder == null || !responder.isActive()) {
            return;
        }
        try {
            responder.onMessage(
                    sender,
                    message,
                    messageService.recent(config.aiMaxContext)
            );
        } catch (Exception e) {
            events.onLog("AI 消息钩子异常：" + e.getMessage());
        }
    }

    public void onEmojiMessage(Member sender, Message message) {
        AiResponder responder = aiResponder;
        if (responder == null || !responder.isActive()) {
            return;
        }
        try {
            responder.onEmojiMessage(sender, message);
        } catch (Exception e) {
            events.onLog("AI 表情收集钩子异常：" + e.getMessage());
        }
    }

    @Override
    public void appendAiMessage(String content) throws Exception {
        if (!running) {
            return;
        }
        Member aiMember = roomService.getAiMember();
        if (aiMember == null) {
            throw new IllegalStateException("AI 成员尚未初始化");
        }
        Message message = messageService.appendAiMessage(aiMember, content);
        broadcastMessage(message, null);
        events.onLog("[#" + message.seq + "] "
                + aiMember.nickname + ": " + content);
    }

    @Override
    public void appendAiEmojiMessage(model.Attachment attachment,
                                     String content) throws Exception {
        if (!running) {
            return;
        }
        Member aiMember = roomService.getAiMember();
        if (aiMember == null) {
            throw new IllegalStateException("AI 成员尚未初始化");
        }
        Message message = messageService.appendAiEmojiMessage(
                aiMember, content, attachment);
        broadcastMessage(message, null);
        events.onLog("[#" + message.seq + "] "
                + aiMember.nickname + "：[表情]");
    }

    @Override
    public void reportFailure(String message) {
        if (!running) {
            return;
        }
        broadcastSystemMessage(message);
    }

    @Override
    public void reportError(String detail) {
        events.onLog(detail);
    }

    public void onMemberJoined(Member member) {
        if (aiResponder == null) {
            return;
        }
        try {
            aiResponder.onMemberJoined(member);
        } catch (Exception e) {
            events.onLog("AI 成员钩子异常：" + e.getMessage());
        }
    }

    public void broadcastMessage(Message message, ClientHandler exclude) {
        if (!running) {
            return;
        }
        JsonObject data = messageJson(message, false, false);
        String normalLine = JsonUtil.stringify(
                Protocol.frame(Protocol.MESSAGE, null, data));

        String legacyLine = normalLine;
        if ("voice".equals(message.type)) {
            JsonObject legacy = messageJsonForClient(
                    message, false, false,
                    VersionPolicy.VOICE_MIN_CLIENT_VERSION_CODE - 1);
            legacyLine = JsonUtil.stringify(
                    Protocol.frame(Protocol.MESSAGE, null, legacy));
        }

        for (ClientHandler handler : new ArrayList<>(handlers)) {
            if (handler != exclude && handler.isJoined()) {
                handler.sendLine(handler.supportsVoice()
                        ? normalLine : legacyLine);
            }
        }
    }

    /**
     * 面向旧客户端时把语音降级为文字，避免旧端下载无法播放的音频。
     */
    public static JsonObject messageJsonForClient(Message message,
                                                  boolean includeSelf,
                                                  boolean self,
                                                  int clientVersionCode) {
        JsonObject json = messageJson(message, includeSelf, self);
        if ("voice".equals(message.type)
                && clientVersionCode < VersionPolicy.VOICE_MIN_CLIENT_VERSION_CODE) {
            json.addProperty("type", "text");
            json.addProperty("content", "[语音消息]");
            json.remove("fileId");
            json.remove("fileName");
            json.remove("fileMime");
            json.remove("fileSize");
            json.remove("fileExpired");
        }
        return json;
    }

    public static JsonObject memberJson(Member member) {
        JsonObject json = new JsonObject();
        json.addProperty("id", member.id);
        json.addProperty("nickname", member.nickname);
        json.addProperty("signature",
                member.signature == null ? "" : member.signature);
        json.addProperty("avatarVersion", member.avatarVersion);
        json.addProperty("ai", member.ai);
        json.addProperty("admin", member.admin);
        return json;
    }

    public static JsonObject messageJson(Message message,
                                         boolean includeSelf,
                                         boolean self) {
        JsonObject json = new JsonObject();
        json.addProperty("msgId", message.msgUuid);
        json.addProperty("seq", message.seq);
        json.addProperty("senderId", message.senderId);
        json.addProperty("senderName", message.senderName);
        json.addProperty("senderAvatarVersion", message.senderAvatarVersion);
        json.addProperty("content", message.content);
        json.addProperty("type", message.type);
        json.addProperty("senderAdmin", message.senderAdmin);
        if (message.fileId != null && !message.fileId.isEmpty()) {
            json.addProperty("fileId", message.fileId);
            json.addProperty("fileName",
                    message.fileName == null ? "" : message.fileName);
            json.addProperty("fileMime",
                    message.fileMime == null ? "" : message.fileMime);
            json.addProperty("fileSize", message.fileSize);
            json.addProperty("fileExpired", message.fileExpired);
        }
        json.addProperty("ts", message.createdAt);
        if (includeSelf) {
            json.addProperty("self", self);
        }
        return json;
    }

    public static boolean isSelf(Message message, String clientTag) {
        return message.senderTag != null
                && !message.senderTag.isEmpty()
                && message.senderTag.equals(clientTag);
    }
}
