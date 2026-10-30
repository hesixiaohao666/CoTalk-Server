package server;

import model.Member;
import model.Message;
import store.MessageStore;

import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

/**
 * 消息序号、落库、历史查询。
 */
public class MessageService {

    private final MessageStore messageStore;
    private long nextSeq;

    public MessageService(MessageStore messageStore) throws SQLException {
        this.messageStore = messageStore;
        this.nextSeq = messageStore.maxSeq() + 1;
    }

    public synchronized Message createAndStore(Member sender,
                                                String clientTag,
                                                String content) throws SQLException {
        Message message = new Message();
        message.msgUuid = newMsgUuid();
        message.seq = nextSeq;
        message.senderId = sender.id;
        message.senderName = sender.nickname;
        message.senderTag = clientTag;
        message.senderAvatarVersion = sender.avatarVersion;
        message.content = content;
        message.type = "text";
        message.createdAt = System.currentTimeMillis();

        messageStore.insert(message);
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

    public synchronized long lastSeq() {
        return Math.max(0L, nextSeq - 1);
    }

    public List<Message> recent(int limit) throws SQLException {
        return messageStore.loadRecent(limit);
    }

    public List<Message> before(long beforeSeq, int limit) throws SQLException {
        return messageStore.before(beforeSeq, limit);
    }

    public List<Message> after(long afterSeq, int limit) throws SQLException {
        return messageStore.after(afterSeq, limit);
    }

    private String newMsgUuid() {
        return "m-" + UUID.randomUUID().toString()
                .replace("-", "")
                .substring(0, 12);
    }
}
