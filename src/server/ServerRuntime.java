package server;

import ai.AiImagePart;
import ai.AiResponderFactory;
import model.Member;
import model.Room;
import protocol.Protocol;
import store.AttachmentStore;
import store.AvatarStore;
import store.ConnectionLogStore;
import store.Database;
import store.MessageStore;
import store.RoomStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.Collections;
import java.util.List;

/**
 * 不依赖 Swing 的服务端运行时，GUI 与终端模式共用。
 */
public final class ServerRuntime {

    private final ServerConfig config;
    private final ServerEvents events;

    private Database database;
    private Room room;
    private RoomService roomService;
    private ChatServer chatServer;
    private AttachmentService attachmentService;

    public ServerRuntime(ServerConfig config, ServerEvents events) {
        this.config = config;
        this.events = events;
    }

    public synchronized void start() throws Exception {
        if (isRunning()) {
            return;
        }

        database = new Database(config.dbPath);
        database.open();
        try {
            room = new RoomStore(database).loadOrCreate(
                    config.roomName, config.roomAnnouncement);
            ConnectionLogStore logStore = new ConnectionLogStore(database);
            MessageStore messageStore = new MessageStore(database);
            MessageService messageService = new MessageService(messageStore);
            AvatarService avatarService = new AvatarService(
                    new AvatarStore(database), config.avatarMaxBytes);
            Path attachmentDir = Paths.get(StoragePolicy.ATTACHMENT_DIR);
            Files.createDirectories(attachmentDir);
            attachmentService = new AttachmentService(
                    new AttachmentStore(database), attachmentDir, events::onLog);
            try {
                attachmentService.cleanupExpired(System.currentTimeMillis());
            } catch (Exception e) {
                events.onLog("启动附件清理失败：" + e.getMessage());
            }
            roomService = new RoomService(room);
            chatServer = new ChatServer(
                    config, roomService, logStore, messageService,
                    avatarService, attachmentService, events);

            Member aiMember = AiMemberService.prepare(
                    config, avatarService, events);
            if (aiMember != null) {
                roomService.setAiMember(aiMember);
            }
            int roomAvatarVersion = RoomAvatarService.prepare(
                    config, avatarService, events);
            chatServer.setRoomAvatarInfo(
                    RoomAvatarService.ROOM_AVATAR_ID, roomAvatarVersion);
            chatServer.setAiResponder(
                    AiResponderFactory.create(config, chatServer, fileId -> {
                        AttachmentService.FileData file =
                                attachmentService.load(fileId);
                        if (file == null || file.expired
                                || file.bytes == null) {
                            return null;
                        }
                        return new AiImagePart(
                                file.attachment.mime,
                                Base64.getEncoder()
                                        .encodeToString(file.bytes));
                    }));
            chatServer.start();
            attachmentService.startMaintenance();
        } catch (Exception e) {
            if (attachmentService != null) {
                attachmentService.shutdownMaintenance();
                attachmentService = null;
            }
            if (chatServer != null) {
                chatServer.stop();
                chatServer = null;
            }
            if (database != null) {
                database.close();
                database = null;
            }
            throw e;
        }
    }

    public synchronized void stop() {
        if (chatServer != null) {
            chatServer.stop();
            chatServer = null;
        }
        if (attachmentService != null) {
            attachmentService.shutdownMaintenance();
            attachmentService = null;
        }
        if (database != null) {
            database.close();
            database = null;
        }
    }

    public boolean isRunning() {
        return chatServer != null && chatServer.isRunning();
    }

    public ServerConfig getConfig() {
        return config;
    }

    public Room getRoom() {
        return room;
    }

    public RoomService getRoomService() {
        return roomService;
    }

    public List<Member> getMembers() {
        return roomService == null
                ? Collections.emptyList()
                : roomService.snapshot();
    }

    public void broadcast(String text) {
        if (chatServer != null && chatServer.isRunning()) {
            chatServer.broadcastSystemMessage(text);
            chatServer.broadcastSystem(text);
        }
    }

    public MessageService.RecallResult forceRecall(long seq) {
        if (chatServer == null || !chatServer.isRunning()) {
            return MessageService.RecallResult.failure(
                    Protocol.ERR_INTERNAL, "服务未启动");
        }
        return chatServer.forceRecallBySeq(seq);
    }

    public String getAiStatus() {
        if (!config.aiEnabled) {
            return "未启用";
        }
        if (!AiResponderFactory.isConfigured(config)) {
            return "已开启但未配置 API Key";
        }
        if (!isRunning()) {
            return "已启用（服务未运行）";
        }
        return "已启用，模型 " + config.aiModel + "，成员 " + config.aiName;
    }
}
