package server;

import model.Attachment;
import model.Member;
import model.Message;
import protocol.Protocol;
import store.MessageStore;

import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

/**
 * 消息序号、落库、历史查询。
 */
public class MessageService {

    public static final long RECALL_WINDOW_MS = 2 * 60 * 1000L;

    public static final class RecallResult {
        public final boolean success;
        public final int errorCode;
        public final String errorMessage;
        public final Message message;
        public final String notice;
        public final String fileId;

        private RecallResult(boolean success,
                             int errorCode,
                             String errorMessage,
                             Message message,
                             String notice,
                             String fileId) {
            this.success = success;
            this.errorCode = errorCode;
            this.errorMessage = errorMessage;
            this.message = message;
            this.notice = notice;
            this.fileId = fileId;
        }

        static RecallResult success(Message message,
                                    String notice,
                                    String fileId) {
            return new RecallResult(true, 0, "", message, notice, fileId);
        }

        public static RecallResult failure(int errorCode, String errorMessage) {
            return new RecallResult(false, errorCode, errorMessage,
                    null, null, null);
        }
    }

    private final MessageStore messageStore;
    private long nextSeq;

    public MessageService(MessageStore messageStore) throws SQLException {
        this.messageStore = messageStore;
        this.nextSeq = messageStore.maxSeq() + 1;
    }

    public synchronized Message createAndStore(Member sender,
                                                String clientTag,
                                                String content) throws SQLException {
        return createAndStore(sender, clientTag, content, "text", null);
    }

    public synchronized Message createAndStore(Member sender,
                                                String clientTag,
                                                String content,
                                                String type,
                                                Attachment attachment)
            throws SQLException {
        Message message = new Message();
        message.msgUuid = newMsgUuid();
        message.seq = nextSeq;
        message.senderId = sender.id;
        message.senderName = sender.nickname;
        message.senderTag = clientTag;
        message.senderAvatarVersion = sender.avatarVersion;
        message.senderAdmin = sender.admin;
        message.content = content == null ? "" : content;
        message.type = type == null || type.isEmpty() ? "text" : type;
        message.createdAt = System.currentTimeMillis();
        if (attachment != null) {
            attachment.messageUuid = message.msgUuid;
            message.fileId = attachment.fileId;
            message.fileName = attachment.fileName;
            message.fileMime = attachment.mime;
            message.fileSize = attachment.size;
            message.fileExpired = attachment.expired;
        }

        messageStore.insert(message, attachment);
        nextSeq++;
        return message;
    }

    public synchronized Message appendSystem(String content) throws SQLException {
        Message message = new Message();
        message.msgUuid = newMsgUuid();
        message.seq = nextSeq;
        message.senderId = "system";
        message.senderName = "系统";
        message.senderTag = "";
        message.senderAvatarVersion = 0;
        message.content = content;
        message.type = "system";
        message.createdAt = System.currentTimeMillis();

        messageStore.insert(message);
        nextSeq++;
        return message;
    }

    public synchronized Message appendAiMessage(Member aiMember,
                                                String content) throws SQLException {
        Message message = new Message();
        message.msgUuid = newMsgUuid();
        message.seq = nextSeq;
        message.senderId = aiMember.id;
        message.senderName = aiMember.nickname;
        message.senderTag = "";
        message.senderAvatarVersion = aiMember.avatarVersion;
        message.content = content;
        message.type = "ai";
        message.createdAt = System.currentTimeMillis();

        messageStore.insert(message);
        nextSeq++;
        return message;
    }

    public synchronized long lastSeq() {
        return Math.max(0L, nextSeq - 1);
    }

    public synchronized RecallResult recallByMsgId(String msgId,
                                                   String requesterTag) {
        try {
            Message message = messageStore.findByMsgUuid(msgId);
            return recall(message, requesterTag, false);
        } catch (SQLException e) {
            return RecallResult.failure(Protocol.ERR_DB,
                    "读取消息失败：" + e.getMessage());
        }
    }

    public synchronized RecallResult forceRecallBySeq(long seq) {
        try {
            Message message = messageStore.findBySeq(seq);
            return recall(message, null, true);
        } catch (SQLException e) {
            return RecallResult.failure(Protocol.ERR_DB,
                    "读取消息失败：" + e.getMessage());
        }
    }

    private RecallResult recall(Message message,
                                String requesterTag,
                                boolean force) throws SQLException {
        if (message == null) {
            return RecallResult.failure(Protocol.ERR_RECALL_NOT_FOUND,
                    "消息不存在");
        }
        if (message.recalled || "system".equals(message.type)) {
            return RecallResult.failure(Protocol.ERR_RECALL_STATE,
                    "消息不可撤回");
        }
        if (!force) {
            if (requesterTag == null || requesterTag.isEmpty()
                    || !requesterTag.equals(message.senderTag)) {
                return RecallResult.failure(Protocol.ERR_RECALL_FORBIDDEN,
                        "只能撤回自己发送的消息");
            }
            long elapsed = System.currentTimeMillis() - message.createdAt;
            if (elapsed > RECALL_WINDOW_MS) {
                return RecallResult.failure(Protocol.ERR_RECALL_EXPIRED,
                        "消息已超过 2 分钟，无法撤回");
            }
        }
        String sender = message.senderName == null
                || message.senderName.trim().isEmpty()
                ? "用户"
                : message.senderName.trim();
        String notice = force
                ? "管理员撤回了 " + sender + " 一条消息"
                : sender + " 撤回了一条消息";
        if (!messageStore.markRecalled(message.msgUuid, notice)) {
            return RecallResult.failure(Protocol.ERR_RECALL_STATE,
                    "消息不可撤回");
        }
        message.recalled = true;
        message.recallNotice = notice;
        return RecallResult.success(message, notice, message.fileId);
    }

    public List<Message> recent(int limit) throws SQLException {
        return messageStore.loadRecent(limit, syncCutoff());
    }

    public List<Message> before(long beforeSeq, int limit) throws SQLException {
        return messageStore.before(beforeSeq, limit, syncCutoff());
    }

    public List<Message> after(long afterSeq, int limit) throws SQLException {
        return messageStore.after(afterSeq, limit, syncCutoff());
    }

    private long syncCutoff() {
        return StoragePolicy.syncCutoff(System.currentTimeMillis());
    }

    private String newMsgUuid() {
        return "m-" + UUID.randomUUID().toString()
                .replace("-", "")
                .substring(0, 12);
    }
}
