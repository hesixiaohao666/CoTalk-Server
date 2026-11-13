package ai.pro;

import ai.AiReplySink;
import model.Message;

import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * AI 待办创建、状态流转与到期提醒。
 */
public class AiTodoService {

    private final AiTodoStore store;
    private final AiProConfig config;
    private final AiReplySink sink;
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "cotalk-ai-todo");
                thread.setDaemon(true);
                return thread;
            });

    public AiTodoService(AiTodoStore store,
                         AiProConfig config,
                         AiReplySink sink) {
        this.store = store;
        this.config = config;
        this.sink = sink;
    }

    public void start() {
        if (!enabled()) {
            return;
        }
        scheduler.scheduleWithFixedDelay(this::checkReminders,
                60, 60, TimeUnit.SECONDS);
    }

    public void shutdown() {
        scheduler.shutdownNow();
    }

    /**
     * 立即检查到期待办（供管理界面/测试使用）。
     */
    public void checkRemindersNow() {
        checkReminders();
    }

    public void process(List<MemoryExtractor.ExtractedTodo> todos,
                        List<MemoryExtractor.ExtractedTodoAction> actions,
                        List<Message> messages) {
        if (!enabled()) {
            return;
        }
        long now = System.currentTimeMillis();
        String text = join(messages);
        if (containsTrigger(text)) {
            for (MemoryExtractor.ExtractedTodo extracted : todos) {
                try {
                    createTodo(extracted, messages, now);
                } catch (Exception e) {
                    sink.reportError("AI Pro 创建待办失败：" + e.getMessage());
                }
            }
        }
        for (MemoryExtractor.ExtractedTodoAction action : actions) {
            try {
                applyAction(action, now);
            } catch (Exception e) {
                sink.reportError("AI Pro 更新待办失败：" + e.getMessage());
            }
        }
    }

    private void createTodo(MemoryExtractor.ExtractedTodo extracted,
                            List<Message> messages,
                            long now) throws Exception {
        String content = MemoryFilter.filter(extracted.content,
                config, 1);
        if (content == null) {
            return;
        }
        if (extracted.remindAt <= now) {
            return;
        }
        ZoneId zone;
        try {
            zone = ZoneId.of(extracted.timezone == null
                    || extracted.timezone.trim().isEmpty()
                    ? config.getString("ai.pro.todo.timezone")
                    : extracted.timezone.trim());
        } catch (Exception e) {
            return;
        }
        Message sender = senderFor(messages, extracted.user);
        String userId = sender == null ? "" : sender.senderId;
        String userTag = sender == null ? "" : sender.senderTag;
        String userName = sender == null
                ? (extracted.user.isEmpty() ? "群成员" : extracted.user)
                : sender.senderName;
        if (!userId.isEmpty() && store.pendingCount(userId)
                >= config.getInt("ai.pro.todo.maxPendingPerUser")) {
            return;
        }
        AiTodo todo = new AiTodo();
        todo.todoId = "todo-" + UUID.randomUUID().toString()
                .replace("-", "").substring(0, 8);
        todo.userId = userId;
        todo.userTag = userTag;
        todo.userName = userName;
        todo.content = content;
        todo.remindAt = extracted.remindAt;
        todo.timezone = zone.getId();
        todo.status = "pending";
        todo.remindCount = 0;
        todo.retryCount = 0;
        todo.sourceSeq = sender == null ? 0 : sender.seq;
        todo.createdAt = now;
        todo.updatedAt = now;
        store.insert(todo);
        store.insertEvent(todo.todoId, "create", "system",
                "创建待办", now);
    }

    private void applyAction(MemoryExtractor.ExtractedTodoAction action,
                             long now) throws Exception {
        AiTodo todo = store.findByTodoId(action.todoId);
        if (todo == null || "done".equals(todo.status)
                || "cancelled".equals(todo.status)) {
            return;
        }
        if ("done".equals(action.action)) {
            store.updateStatus(todo.todoId, "done", todo.remindAt, now);
        } else if ("cancel".equals(action.action)) {
            store.updateStatus(todo.todoId, "cancelled",
                    todo.remindAt, now);
        } else if ("defer".equals(action.action)
                && action.deferTo > now) {
            store.updateStatus(todo.todoId, "pending",
                    action.deferTo, now);
        } else {
            return;
        }
        store.insertEvent(todo.todoId, action.action, "system",
                "action", now);
    }

    private void checkReminders() {
        if (!enabled()) {
            return;
        }
        try {
            long now = System.currentTimeMillis();
            List<AiTodo> due = store.due(now,
                    config.getInt("ai.pro.todo.maxRemindersPerRound"));
            for (AiTodo todo : due) {
                int maxReminders = config.getInt(
                        "ai.pro.todo.maxReminders");
                if (todo.remindCount >= maxReminders) {
                    store.updateStatus(todo.todoId, "done",
                            todo.remindAt, now);
                    continue;
                }
                String reminder = reminderText(todo);
                try {
                    sink.appendAiMessage(reminder);
                    int count = todo.remindCount + 1;
                    String status = count >= maxReminders
                            ? "done" : "pending";
                    store.markReminded(todo.todoId, count,
                            todo.retryCount, now, status, now);
                    store.insertEvent(todo.todoId, "remind",
                            "system", status, now);
                } catch (Exception e) {
                    int retry = todo.retryCount + 1;
                    if (retry > config.getInt("ai.pro.todo.maxRetries")) {
                        store.updateStatus(todo.todoId, "cancelled",
                                todo.remindAt, now);
                    } else {
                        store.markReminded(todo.todoId,
                                todo.remindCount, retry, now,
                                todo.status, now);
                    }
                    sink.reportError("AI Pro 待办提醒失败：" + e.getMessage());
                }
            }
        } catch (Exception e) {
            sink.reportError("AI Pro 待办检查失败：" + e.getMessage());
        }
    }

    private String reminderText(AiTodo todo) {
        String name = todo.userName == null || todo.userName.isEmpty()
                ? "群成员" : todo.userName;
        if (config.getBoolean("ai.pro.todo.includeContentInGroup")) {
            return "提醒 " + name + "：" + todo.content;
        }
        return "提醒 " + name + "，你有一项待办到期了。";
    }

    private Message senderFor(List<Message> messages, String user) {
        if (messages == null || messages.isEmpty()) {
            return null;
        }
        if (user != null && !user.isEmpty()) {
            for (Message message : messages) {
                if (message != null && user.equals(message.senderName)) {
                    return message;
                }
            }
        }
        return messages.get(messages.size() - 1);
    }

    private boolean containsTrigger(String text) {
        if (text == null) {
            return false;
        }
        String[] triggers = config.getString(
                "ai.pro.todo.triggers").split("[,，\\s]+");
        for (String trigger : triggers) {
            if (!trigger.trim().isEmpty() && text.contains(trigger.trim())) {
                return true;
            }
        }
        return false;
    }

    private static String join(List<Message> messages) {
        StringBuilder builder = new StringBuilder();
        if (messages != null) {
            for (Message message : messages) {
                if (message != null && message.content != null) {
                    builder.append(message.content).append('\n');
                }
            }
        }
        return builder.toString();
    }

    private boolean enabled() {
        return config.getBoolean("ai.pro.todo.enabled");
    }
}
