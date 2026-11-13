package server;

import ai.AiReplySink;
import ai.AiResponder;
import ai.NoopAiResponder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import model.Member;
import model.Message;
import protocol.Protocol;
import store.ConnectionLogStore;
import util.JsonUtil;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
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
    private volatile AiResponder aiResponder = new NoopAiResponder();
    private volatile String roomAvatarId = "";
    private volatile int roomAvatarVersion;

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
