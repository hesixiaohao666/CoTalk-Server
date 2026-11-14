package ai.pro;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 服务端 AI Pro 管理面板：统计、记忆、待办、表情、核心提示词与高级选项。
 */
public class AiProSettingsPanel extends JPanel {

    private final Window owner;
    private final AiProAdminService admin;
    private final AiProAdvancedPanel advancedPanel;
    private final JTextArea stats = new JTextArea(4, 40);
    private final DefaultTableModel memoryModel;
    private final JTable memoryTable;
    private final JTextField memorySearch = new JTextField(16);
    private final JComboBox<String> memoryLayer =
            new JComboBox<>(new String[]{"全部", "深层", "浅层", "缓存"});
    private final JComboBox<String> memoryStatus =
            new JComboBox<>(new String[]{"全部", "active", "archived", "deleted"});
    private List<Memory> memories;
    private final DefaultTableModel todoModel;
    private final JTable todoTable;
    private List<AiTodo> todos;
    private final DefaultTableModel emojiModel;
    private final JTable emojiTable;
    private List<AiEmoji> emojis;
    private final JTextArea corePrompt = new JTextArea(6, 40);
    private final JTextArea redlines = new JTextArea(8, 40);

    public AiProSettingsPanel(Window owner, AiProAdminService admin) {
        this(owner, admin, null);
    }

    public AiProSettingsPanel(Window owner,
                              AiProAdminService admin,
                              AiProAdvancedPanel advancedPanel) {
        super(new BorderLayout(8, 8));
        this.owner = owner;
        this.admin = admin;
        this.advancedPanel = advancedPanel;
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        stats.setEditable(false);
        stats.setLineWrap(true);
        stats.setWrapStyleWord(true);
        add(new JScrollPane(stats), BorderLayout.NORTH);

        memoryModel = new DefaultTableModel(
                new Object[]{"ID", "层级", "重要度", "状态", "内容", "更新时间"},
                0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        memoryTable = new JTable(memoryModel);
        memoryTable.setRowHeight(24);

        todoModel = new DefaultTableModel(
                new Object[]{"ID", "用户", "内容", "提醒时间", "状态", "次数"},
                0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        todoTable = new JTable(todoModel);
        todoTable.setRowHeight(24);

        emojiModel = new DefaultTableModel(
                new Object[]{"ID", "名称", "标签", "审核", "启用"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        emojiTable = new JTable(emojiModel);
        emojiTable.setRowHeight(24);

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("记忆", buildMemoryTab());
        tabs.addTab("待办", buildTodoTab());
        tabs.addTab("表情", buildEmojiTab());
        tabs.addTab("核心提示词", buildCoreTab());
        if (advancedPanel != null) {
            tabs.addTab("高级选项", advancedPanel);
        }
        add(tabs, BorderLayout.CENTER);
        refreshAll();
    }

    private JPanel buildMemoryTab() {
        JPanel panel = new JPanel(new BorderLayout(6, 6));
        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        top.add(new JLabel("搜索"));
        top.add(memorySearch);
        top.add(memoryLayer);
        top.add(memoryStatus);
        JButton refresh = new JButton("刷新");
        JButton add = new JButton("新增");
        JButton edit = new JButton("编辑");
        JButton toggle = new JButton("归档/恢复");
        JButton delete = new JButton("删除");
        JButton clear = new JButton("清空当前层");
        JButton export = new JButton("导出 JSON");
        refresh.addActionListener(e -> refreshMemories());
        add.addActionListener(e -> addMemory());
        edit.addActionListener(e -> editMemory());
        toggle.addActionListener(e -> toggleMemory());
        delete.addActionListener(e -> deleteMemory());
        clear.addActionListener(e -> clearMemoryLayer());
        export.addActionListener(e -> exportMemories());
        top.add(refresh);
        top.add(add);
        top.add(edit);
        top.add(toggle);
        top.add(delete);
        top.add(clear);
        top.add(export);
        panel.add(top, BorderLayout.NORTH);
        panel.add(new JScrollPane(memoryTable), BorderLayout.CENTER);
        return panel;
    }

    private JPanel buildTodoTab() {
        JPanel panel = new JPanel(new BorderLayout(6, 6));
        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        JButton refresh = new JButton("刷新");
        JButton done = new JButton("完成");
        JButton cancel = new JButton("取消");
        refresh.addActionListener(e -> refreshTodos());
        done.addActionListener(e -> todoAction("done"));
        cancel.addActionListener(e -> todoAction("cancel"));
        top.add(refresh);
        top.add(done);
        top.add(cancel);
        panel.add(top, BorderLayout.NORTH);
        panel.add(new JScrollPane(todoTable), BorderLayout.CENTER);
        return panel;
    }

    private JPanel buildEmojiTab() {
        JPanel panel = new JPanel(new BorderLayout(6, 6));
        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        JButton refresh = new JButton("刷新");
        JButton importButton = new JButton("导入");
        JButton approve = new JButton("通过");
        JButton reject = new JButton("拒绝");
        JButton toggle = new JButton("启用/禁用");
        JButton tags = new JButton("标签");
        JButton delete = new JButton("删除");
        refresh.addActionListener(e -> refreshEmojis());
        importButton.addActionListener(e -> importEmoji());
        approve.addActionListener(e -> reviewEmoji(true));
        reject.addActionListener(e -> reviewEmoji(false));
        toggle.addActionListener(e -> toggleEmoji());
        tags.addActionListener(e -> editEmojiTags());
        delete.addActionListener(e -> deleteEmoji());
        top.add(refresh);
        top.add(importButton);
        top.add(approve);
        top.add(reject);
        top.add(toggle);
        top.add(tags);
        top.add(delete);
        panel.add(top, BorderLayout.NORTH);
        panel.add(new JScrollPane(emojiTable), BorderLayout.CENTER);
        return panel;
    }

    private JPanel buildCoreTab() {
        JPanel panel = new JPanel(new BorderLayout(6, 6));
        JPanel content = new JPanel(new GridLayout(2, 1, 6, 6));
        corePrompt.setLineWrap(true);
        corePrompt.setWrapStyleWord(true);
        redlines.setEditable(false);
        redlines.setLineWrap(true);
        redlines.setWrapStyleWord(true);
        JPanel promptPanel = new JPanel(new BorderLayout(4, 4));
        promptPanel.add(new JLabel("核心提示词"), BorderLayout.NORTH);
        promptPanel.add(new JScrollPane(corePrompt), BorderLayout.CENTER);
        JPanel redlinePanel = new JPanel(new BorderLayout(4, 4));
        redlinePanel.add(new JLabel("底层红线（只读）"), BorderLayout.NORTH);
        redlinePanel.add(new JScrollPane(redlines), BorderLayout.CENTER);
        content.add(promptPanel);
        content.add(redlinePanel);
        panel.add(content, BorderLayout.CENTER);
        JButton save = new JButton("保存核心提示词");
        save.addActionListener(e -> saveCorePrompt());
        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        bottom.add(save);
        panel.add(bottom, BorderLayout.SOUTH);
        return panel;
    }

    public void refreshAll() {
        stats.setText(admin.statsText());
        refreshMemories();
        refreshTodos();
        refreshEmojis();
        refreshCore();
    }

    private void refreshMemories() {
        try {
            MemoryLayer layer = "全部".equals(memoryLayer.getSelectedItem())
                    ? null : switchLayer((String) memoryLayer.getSelectedItem());
            String status = "全部".equals(memoryStatus.getSelectedItem())
                    ? "" : (String) memoryStatus.getSelectedItem();
            memories = admin.listMemories(memorySearch.getText(),
                    layer, status, 200, 0);
            memoryModel.setRowCount(0);
            SimpleDateFormat format = new SimpleDateFormat(
                    "MM-dd HH:mm", Locale.ROOT);
            for (Memory memory : memories) {
                memoryModel.addRow(new Object[]{
                        memory.memoryId,
                        layerLabel(memory.layer),
                        String.format(Locale.ROOT, "%.2f", memory.importance),
                        memory.status,
                        summarize(memory.content),
                        format.format(new Date(memory.updatedAt))
                });
            }
        } catch (Exception e) {
            error("读取记忆失败", e);
        }
    }

    private void addMemory() {
        String content = JOptionPane.showInputDialog(owner,
                "记忆内容", "新增记忆", JOptionPane.PLAIN_MESSAGE);
        if (content == null || content.trim().isEmpty()) {
            return;
        }
        try {
            admin.addMemory(content, MemoryLayer.SHALLOW, "fact",
                    "[]", "[]", true, "admin-gui");
            refreshAll();
        } catch (Exception e) {
            error("新增记忆失败", e);
        }
    }

    private void editMemory() {
        Memory memory = selectedMemory();
        if (memory == null) {
            return;
        }
        JTextArea area = new JTextArea(memory.content, 4, 30);
        area.setLineWrap(true);
        JTextField keywords = new JTextField(memory.keywords, 20);
        JTextField tags = new JTextField(memory.tags, 20);
        JCheckBox explicit = new JCheckBox("显式重要",
                memory.explicitImportant);
        JComboBox<String> status = new JComboBox<>(
                new String[]{"active", "archived", "deleted"});
        status.setSelectedItem(memory.status);
        JPanel form = new JPanel();
        form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));
        form.add(new JLabel("内容"));
        form.add(new JScrollPane(area));
        form.add(new JLabel("关键词 JSON"));
        form.add(keywords);
        form.add(new JLabel("标签 JSON"));
        form.add(tags);
        form.add(explicit);
        form.add(status);
        if (JOptionPane.showConfirmDialog(owner, form,
                "编辑记忆", JOptionPane.OK_CANCEL_OPTION)
                != JOptionPane.OK_OPTION) {
            return;
        }
        try {
            admin.editMemory(memory.memoryId, area.getText(),
                    keywords.getText(), tags.getText(),
                    explicit.isSelected(),
                    (String) status.getSelectedItem(), "admin-gui");
            refreshAll();
        } catch (Exception e) {
            error("编辑记忆失败", e);
        }
    }

    private void toggleMemory() {
        Memory memory = selectedMemory();
        if (memory == null) {
            return;
        }
        try {
            String target = "archived".equals(memory.status)
                    ? "active" : "archived";
            admin.setStatus(memory.memoryId, target, "admin-gui");
            refreshAll();
        } catch (Exception e) {
            error("更新记忆状态失败", e);
        }
    }

    private void deleteMemory() {
        Memory memory = selectedMemory();
        if (memory == null) {
            return;
        }
        if (JOptionPane.showConfirmDialog(owner,
                "删除记忆 " + memory.memoryId + "？",
                "确认删除", JOptionPane.YES_NO_OPTION)
                != JOptionPane.YES_OPTION) {
            return;
        }
        try {
            admin.deleteMemory(memory.memoryId, false, "admin-gui");
            refreshAll();
        } catch (Exception e) {
            error("删除记忆失败", e);
        }
    }

    private void clearMemoryLayer() {
        String selected = (String) memoryLayer.getSelectedItem();
        MemoryLayer layer = "全部".equals(selected)
                ? null : switchLayer(selected);
        if (JOptionPane.showConfirmDialog(owner,
                layer == null ? "清空全部记忆？" : "清空" + selected + "层？",
                "确认清空", JOptionPane.YES_NO_OPTION)
                != JOptionPane.YES_OPTION) {
            return;
        }
        try {
            if (layer == null) {
                admin.clearAll("admin-gui");
            } else {
                admin.clearLayer(layer, "admin-gui");
            }
            refreshAll();
        } catch (Exception e) {
            error("清空记忆失败", e);
        }
    }

    private void exportMemories() {
        JFileChooser chooser = new JFileChooser();
        chooser.setSelectedFile(new java.io.File("aipro-memories.json"));
        if (chooser.showSaveDialog(owner) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        try {
            Path path = admin.exportJson(
                    chooser.getSelectedFile().toPath(), "admin-gui");
            JOptionPane.showMessageDialog(owner,
                    "已导出到：\n" + path + "\n\n文件包含敏感记忆，请妥善保管。",
                    "导出完成", JOptionPane.INFORMATION_MESSAGE);
        } catch (Exception e) {
            error("导出失败", e);
        }
    }

    private Memory selectedMemory() {
        int row = memoryTable.getSelectedRow();
        if (row < 0 || memories == null || row >= memories.size()) {
            return null;
        }
        return memories.get(row);
    }

    private void refreshTodos() {
        try {
            todos = admin.listTodos(200);
            todoModel.setRowCount(0);
            SimpleDateFormat format = new SimpleDateFormat(
                    "MM-dd HH:mm", Locale.ROOT);
            for (AiTodo todo : todos) {
                todoModel.addRow(new Object[]{
                        todo.todoId,
                        todo.userName,
                        summarize(todo.content),
                        format.format(new Date(todo.remindAt)),
                        todo.status,
                        todo.remindCount
                });
            }
        } catch (Exception e) {
            error("读取待办失败", e);
        }
    }

    private void todoAction(String action) {
        int row = todoTable.getSelectedRow();
        if (row < 0 || todos == null || row >= todos.size()) {
            return;
        }
        try {
            admin.todoAction(todos.get(row).todoId, action, 0L, "admin-gui");
            refreshAll();
        } catch (Exception e) {
            error("更新待办失败", e);
        }
    }

    private void refreshEmojis() {
        try {
            emojis = admin.listEmojis(500);
            emojiModel.setRowCount(0);
            for (AiEmoji emoji : emojis) {
                emojiModel.addRow(new Object[]{
                        emoji.emojiId,
                        emoji.name,
                        emoji.tags,
                        reviewLabel(emoji.reviewStatus),
                        emoji.enabled ? "是" : "否"
                });
            }
        } catch (Exception e) {
            error("读取表情失败", e);
        }
    }

    private void importEmoji() {
        JFileChooser chooser = new JFileChooser();
        if (chooser.showOpenDialog(owner) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        try {
            admin.importEmoji(chooser.getSelectedFile().toPath(),
                    chooser.getSelectedFile().getName(), "[]", "admin-gui");
            refreshAll();
        } catch (Exception e) {
            error("导入表情失败", e);
        }
    }

    private void reviewEmoji(boolean approved) {
        int row = emojiTable.getSelectedRow();
        if (row < 0 || emojis == null || row >= emojis.size()) {
            return;
        }
        AiEmoji emoji = emojis.get(row);
        try {
            admin.reviewEmoji(emoji.emojiId, approved, "admin-gui");
            refreshAll();
        } catch (Exception e) {
            error("审核表情失败", e);
        }
    }

    private void toggleEmoji() {
        int row = emojiTable.getSelectedRow();
        if (row < 0 || emojis == null || row >= emojis.size()) {
            return;
        }
        AiEmoji emoji = emojis.get(row);
        try {
            admin.setEmojiEnabled(emoji.emojiId, !emoji.enabled, "admin-gui");
            refreshAll();
        } catch (Exception e) {
            error("更新表情失败", e);
        }
    }

    private void editEmojiTags() {
        int row = emojiTable.getSelectedRow();
        if (row < 0 || emojis == null || row >= emojis.size()) {
            return;
        }
        AiEmoji emoji = emojis.get(row);
        String tags = JOptionPane.showInputDialog(owner, "标签 JSON",
                emoji.tags);
        if (tags == null) {
            return;
        }
        try {
            admin.updateEmojiTags(emoji.emojiId, tags, "admin-gui");
            refreshAll();
        } catch (Exception e) {
            error("更新标签失败", e);
        }
    }

    private void deleteEmoji() {
        int row = emojiTable.getSelectedRow();
        if (row < 0 || emojis == null || row >= emojis.size()) {
            return;
        }
        AiEmoji emoji = emojis.get(row);
        if (JOptionPane.showConfirmDialog(owner,
                "删除表情 " + emoji.emojiId + "？", "确认删除",
                JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) {
            return;
        }
        try {
            admin.deleteEmoji(emoji.emojiId, "admin-gui");
            refreshAll();
        } catch (Exception e) {
            error("删除表情失败", e);
        }
    }

    private void refreshCore() {
        try {
            CoreMemory core = admin.coreMemory();
            corePrompt.setText(core.custom);
            StringBuilder builder = new StringBuilder();
            for (CoreMemory.Entry entry : core.entries) {
                if ("redline".equals(entry.kind)) {
                    builder.append("[").append(entry.slot).append("] ")
                            .append(entry.content).append('\n');
                }
            }
            redlines.setText(builder.toString());
        } catch (Exception e) {
            error("读取核心记忆失败", e);
        }
    }

    private void saveCorePrompt() {
        try {
            admin.updateCustomPrompt(corePrompt.getText(), "admin-gui");
            JOptionPane.showMessageDialog(owner,
                    "核心提示词已保存，将在下一次回复生效。",
                    "保存成功", JOptionPane.INFORMATION_MESSAGE);
            refreshAll();
        } catch (Exception e) {
            error("保存核心提示词失败", e);
        }
    }

    private void error(String title, Exception e) {
        JOptionPane.showMessageDialog(owner,
                title + "：" + e.getMessage(),
                title, JOptionPane.ERROR_MESSAGE);
    }

    private static MemoryLayer switchLayer(String label) {
        if ("深层".equals(label)) {
            return MemoryLayer.DEEP;
        }
        if ("浅层".equals(label)) {
            return MemoryLayer.SHALLOW;
        }
        return MemoryLayer.CACHE;
    }

    private static String layerLabel(MemoryLayer layer) {
        if (layer == MemoryLayer.DEEP) {
            return "深层";
        }
        if (layer == MemoryLayer.SHALLOW) {
            return "浅层";
        }
        return "缓存";
    }

    private static String reviewLabel(String status) {
        if ("pending".equalsIgnoreCase(status)) {
            return "待审核";
        }
        if ("rejected".equalsIgnoreCase(status)) {
            return "已拒绝";
        }
        return "已通过";
    }

    private static String summarize(String value) {
        if (value == null) {
            return "";
        }
        String text = value.replace('\n', ' ').trim();
        return text.length() <= 50 ? text : text.substring(0, 50) + "…";
    }
}
