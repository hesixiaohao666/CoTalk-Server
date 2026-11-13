package server;

import ai.AiImagePart;
import ai.AiImageResolver;
import ai.AiResponder;
import ai.AiResponderFactory;
import ai.pro.CoreMemoryStore;
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
    private final Path configPath;

    private Database database;
    private Room room;
    private RoomService roomService;
    private ChatServer chatServer;
    private AiResponder aiResponder;
    private AttachmentService attachmentService;

    public ServerRuntime(ServerConfig config, ServerEvents events) {
        this(config, events, ServerConfigStore.DEFAULT_PATH);
    }

    public ServerRuntime(ServerConfig config,
                         ServerEvents events,
                         Path configPath) {
        this.config = config;
        this.events = events;
        this.configPath = configPath == null
                ? ServerConfigStore.DEFAULT_PATH : configPath;
    }

    public synchronized void start() throws Exception {
        if (isRunning()) {
            return;
        }

        database = new Database(config.dbPath);
        database.open();
        try {
            RoomStore roomStore = new RoomStore(database);
            room = roomStore.loadOrCreate(
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
            chatServer.setRoomStore(roomStore);
            chatServer.setConfigPath(configPath);

            Member aiMember = AiMemberService.prepare(
                    config, avatarService, events);
            if (aiMember != null) {
                roomService.setAiMember(aiMember);
            }
            int roomAvatarVersion = RoomAvatarService.prepare(
                    config, avatarService, events);
            chatServer.setRoomAvatarInfo(
                    RoomAvatarService.ROOM_AVATAR_ID, roomAvatarVersion);
            AiImageResolver imageResolver = fileId -> {
                AttachmentService.FileData file =
                        attachmentService.load(fileId);
                if (file == null || file.expired
                        || file.bytes == null) {
                    return null;
                }
                return new AiImagePart(
                        file.attachment.mime,
                        Base64.getEncoder().encodeToString(file.bytes));
            };
            AiResponder responder;
            if (config.aiProEnabled) {
                try {
                    CoreMemoryStore coreMemoryStore =
                            new CoreMemoryStore(database);
                    coreMemoryStore.initialize(
                            config.aiName,
                            config.aiPromptCustom,
                            config.aiPro.ownerNames());
                    responder = AiResponderFactory.createPro(
                            config, chatServer, imageResolver,
                            coreMemoryStore, database, messageService,
                            attachmentService);
                    events.onLog("AI群友Pro 已启用（实验性功能）");
                } catch (Exception e) {
                    events.onLog("AI Pro 初始化失败，回退经典 AI："
                            + e.getMessage());
                    responder = AiResponderFactory.create(
                            config, chatServer, imageResolver);
                }
            } else {
                responder = AiResponderFactory.create(
                        config, chatServer, imageResolver);
            }
            aiResponder = responder;
            chatServer.setAiResponder(responder);
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
        aiResponder = null;
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

    /**
     * 保存设置后即时应用管理员认证配置；密钥/开关变化时解除所有管理员绑定。
     * 其他配置仍需重启服务。
     */
    public boolean applyAdminConfig(ServerConfig saved) {
        if (saved == null) {
            return false;
        }
        boolean changed = config.adminEnabled != saved.adminEnabled
                || config.adminIterations != saved.adminIterations
                || differs(config.adminSalt, saved.adminSalt)
                || differs(config.adminVerifier, saved.adminVerifier);
        if (!changed) {
            return false;
        }
        config.adminEnabled = saved.adminEnabled;
        config.adminSalt = saved.adminSalt;
        config.adminIterations = saved.adminIterations;
        config.adminVerifier = saved.adminVerifier;
        events.onLog("管理员认证配置已即时更新");
        if (chatServer != null) {
            chatServer.revokeAllAdmins("管理员密钥已刷新");
        }
        return true;
    }

    private static boolean differs(String left, String right) {
        return left == null ? right != null : !left.equals(right);
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

    public String getAiProEmotionStatus() {
        if (aiResponder instanceof ai.pro.AiProResponder) {
            return ((ai.pro.AiProResponder) aiResponder)
                    .getEmotionStatus();
        }
        return "未启用";
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
