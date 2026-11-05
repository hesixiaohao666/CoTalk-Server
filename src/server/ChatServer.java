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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * TCP 服务端：连接管理、成员广播、消息广播与 AI 钩子。
 */
public class ChatServer implements AiReplySink {

    private final ServerConfig config;
    private final RoomService roomService;
    private final ConnectionLogStore connectionLogStore;
    private final MessageService messageService;
    private final AvatarService avatarService;
    private final AttachmentService attachmentService;
    private final ServerEvents events;
    private volatile AiResponder aiResponder = new NoopAiResponder();

    private final Set<ClientHandler> handlers = ConcurrentHashMap.newKeySet();
    private final ExecutorService clientPool = Executors.newCachedThreadPool();

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
        clientPool.shutdownNow();
        if (aiResponder != null) {
            aiResponder.shutdown();
        }

        events.onMembersChanged(Collections.emptyList());
        events.onStatusChanged("已停止");
        events.onLog("服务已停止");
    }

    public boolean isRunning() {
        return running;
    }

    public ServerConfig getConfig() {
        return config;
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
            broadcastMessage(message, null);
        } catch (Exception e) {
            events.onLog("写入系统消息失败：" + e.getMessage());
            broadcastSystem(content);
        }
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
        events.onLog(aiMember.nickname + ": " + content);
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
        String line = JsonUtil.stringify(
                Protocol.frame(Protocol.MESSAGE, null, data));

        for (ClientHandler handler : new ArrayList<>(handlers)) {
            if (handler != exclude && handler.isJoined()) {
                handler.sendLine(line);
            }
        }
    }

    public static JsonObject memberJson(Member member) {
        JsonObject json = new JsonObject();
        json.addProperty("id", member.id);
        json.addProperty("nickname", member.nickname);
        json.addProperty("avatarVersion", member.avatarVersion);
        json.addProperty("ai", member.ai);
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
