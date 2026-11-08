package server;

import ai.AiPrompts;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;

/**
 * 服务端可视化配置：服务器、AI 与版本信息。
 */
public class ServerSettingsDialog extends JDialog {

    private static final char PASSWORD_ECHO = '•';

    private final ServerConfig original;
    private final Path configPath;
    private ServerConfig savedConfig;

    private final JTextField portField = new JTextField(10);
    private final JTextField maxClientsField = new JTextField(10);
    private final JTextField joinTimeoutField = new JTextField(10);
    private final JTextField heartbeatTimeoutField = new JTextField(10);
    private final JTextField roomNameField = new JTextField(24);
    private final JTextField announcementField = new JTextField(24);
    private final JTextField roomAvatarField = new JTextField(24);
    private final JButton browseRoomAvatarButton = new JButton("浏览…");
    private final JTextField dbPathField = new JTextField(24);
    private final JPasswordField authKeyField = new JPasswordField(24);
    private final JCheckBox showAuthKey = new JCheckBox("显示");
    private final JButton generateKeyButton = new JButton("生成随机");

    private final JCheckBox aiEnabledCheck = new JCheckBox("启用 AI 群友");
    private final JCheckBox aiVisionCheck = new JCheckBox("允许 AI 识图");
    private final JCheckBox aiSystemMessagesCheck = new JCheckBox("允许 AI 读取系统消息");
    private final JPasswordField apiKeyField = new JPasswordField(24);
    private final JCheckBox showApiKey = new JCheckBox("显示");
    private final JTextField aiModelField = new JTextField(20);
    private final JTextField aiBaseUrlField = new JTextField(28);
    private final JTextField aiNameField = new JTextField(16);
    private final JTextField aiMentionsField = new JTextField(24);
    private final JTextField aiKeywordsField = new JTextField(24);
    private final JTextField aiKeywordChanceField = new JTextField(8);
    private final JTextField aiRandomChanceField = new JTextField(8);
    private final JTextField aiAvatarField = new JTextField(24);
    private final JButton browseAvatarButton = new JButton("浏览…");
    private final JTextArea aiForcedPromptArea = new JTextArea(5, 36);
    private final JTextArea aiPromptArea = new JTextArea(5, 36);
    private final JTextField aiCooldownField = new JTextField(10);
    private final JTextField aiMaxContextField = new JTextField(10);
    private final JTextField aiTimeoutField = new JTextField(10);
    private final JTextField aiMaxTokensField = new JTextField(10);
    private final JTextField aiMessageMaxCharsField = new JTextField(10);
    private final JTextField aiMaxMessagesField = new JTextField(10);
    private final JTextField aiReplyDelayField = new JTextField(10);

    public ServerSettingsDialog(Window owner,
                                ServerConfig config,
                                Path configPath) {
        super(owner, "CoTalk 服务器设置", ModalityType.APPLICATION_MODAL);
        this.original = config;
        this.configPath = configPath;
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setContentPane(buildContent());
        fillFields();
        bindActions();
        setSize(780, 680);
        setMinimumSize(new Dimension(700, 580));
        positionTopCenter();
        applyFont(getContentPane(), uiFont(Font.PLAIN, 14));
    }

    private void positionTopCenter() {
        Rectangle bounds = GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getMaximumWindowBounds();
        int x = bounds.x + Math.max(0, (bounds.width - getWidth()) / 2);
        int y = bounds.y + Math.max(24, (int) (bounds.height * 0.06));
        setLocation(x, y);
    }

    public ServerConfig showDialog() {
        setVisible(true);
        return savedConfig;
    }

    private JPanel buildContent() {
        JPanel content = new JPanel(new BorderLayout(0, 8));
        content.setBorder(new EmptyBorder(8, 8, 8, 8));

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("服务器", buildServerPanel());
        tabs.addTab("AI", buildAiPanel());
        tabs.addTab("版本", buildVersionPanel());
        content.add(tabs, BorderLayout.CENTER);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 4));
        JButton saveButton = new JButton("保存");
        JButton cancelButton = new JButton("取消");
        saveButton.addActionListener(e -> save());
        cancelButton.addActionListener(e -> dispose());
        buttons.add(saveButton);
        buttons.add(cancelButton);
        content.add(buttons, BorderLayout.SOUTH);
        getRootPane().setDefaultButton(saveButton);
        return content;
    }

    private JPanel buildServerPanel() {
        JPanel panel = formPanel();
        int row = 0;
        addRow(panel, row++, "服务器端口", portField, "1–65535");
        addRow(panel, row++, "最大连接数", maxClientsField, "大于 0");
        addRow(panel, row++, "加入超时 (ms)", joinTimeoutField, "大于 0");
        addRow(panel, row++, "心跳超时 (ms)", heartbeatTimeoutField, "大于 0");
        addRow(panel, row++, "房间名称", roomNameField, null);
        addRow(panel, row++, "房间公告", announcementField, null);

        JPanel roomAvatarRow = new JPanel(new BorderLayout(6, 0));
        roomAvatarRow.setOpaque(false);
        roomAvatarRow.add(roomAvatarField, BorderLayout.CENTER);
        roomAvatarRow.add(browseRoomAvatarButton, BorderLayout.EAST);
        addRow(panel, row++, "房间头像", roomAvatarRow,
                "PNG/JPG 路径，留空使用默认样式");

        addRow(panel, row++, "数据库路径", dbPathField, null);

        JPanel keyRow = new JPanel(new BorderLayout(6, 0));
        keyRow.setOpaque(false);
        keyRow.add(authKeyField, BorderLayout.CENTER);
        JPanel keyActions = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        keyActions.setOpaque(false);
        keyActions.add(showAuthKey);
        keyActions.add(generateKeyButton);
        keyRow.add(keyActions, BorderLayout.EAST);
        addRow(panel, row++, "连接密钥", keyRow, "客户端连接前需要输入");

        JTextField pathField = new JTextField(configPath.toAbsolutePath().toString());
        pathField.setEditable(false);
        addRow(panel, row++, "配置文件", pathField, "只读");
        addNote(panel, row,
                "端口、连接密钥、超时、房间头像和 AI 配置修改后需要重启服务才生效。");

        JScrollPane scroll = new JScrollPane(panel);
        scroll.setBorder(null);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.add(scroll, BorderLayout.CENTER);
        return wrapper;
    }

    private JPanel buildAiPanel() {
        JPanel form = formPanel();
        int row = 0;

        addRow(form, row++, "AI 开关", aiEnabledCheck,
                "关闭或未填写 API Key 时不会出现 AI 成员");
        addRow(form, row++, "AI 识图", aiVisionCheck,
                "关闭后只保留图片/表情的文字描述");
        addRow(form, row++, "读取系统消息", aiSystemMessagesCheck,
                "加入/退出/撤回/拍一拍/广播等");

        JPanel apiRow = new JPanel(new BorderLayout(6, 0));
        apiRow.setOpaque(false);
        apiRow.add(apiKeyField, BorderLayout.CENTER);
        JPanel apiActions = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        apiActions.setOpaque(false);
        apiActions.add(showApiKey);
        apiRow.add(apiActions, BorderLayout.EAST);
        addRow(form, row++, "API Key", apiRow, "DeepSeek 服务端密钥");

        addRow(form, row++, "模型", aiModelField, null);
        addRow(form, row++, "Base URL", aiBaseUrlField, null);
        addRow(form, row++, "AI 昵称", aiNameField, null);
        addRow(form, row++, "@ 触发词", aiMentionsField, "逗号分隔");
        addRow(form, row++, "参与关键词", aiKeywordsField, "如：大肥鱼,鱼,AI");
        addRow(form, row++, "关键词概率 (%)", aiKeywordChanceField, "0–100");
        addRow(form, row++, "随机参与概率 (%)", aiRandomChanceField, "0–100");

        JPanel avatarRow = new JPanel(new BorderLayout(6, 0));
        avatarRow.setOpaque(false);
        avatarRow.add(aiAvatarField, BorderLayout.CENTER);
        avatarRow.add(browseAvatarButton, BorderLayout.EAST);
        addRow(form, row++, "AI 头像", avatarRow, "PNG/JPG 路径");

        aiForcedPromptArea.setLineWrap(true);
        aiForcedPromptArea.setWrapStyleWord(true);
        aiForcedPromptArea.setEditable(false);
        aiForcedPromptArea.setBackground(new Color(0xF2F2F2));
        JScrollPane forcedPromptScroll = new JScrollPane(aiForcedPromptArea);
        forcedPromptScroll.setPreferredSize(new Dimension(420, 100));
        addRow(form, row++, "强制提示词（只读）", forcedPromptScroll,
                "由程序固定，不可修改");

        aiPromptArea.setLineWrap(true);
        aiPromptArea.setWrapStyleWord(true);
        JScrollPane promptScroll = new JScrollPane(aiPromptArea);
        promptScroll.setPreferredSize(new Dimension(420, 100));
        addRow(form, row++, "自定义提示词", promptScroll,
                "用于大肥鱼人设和表达风格");

        addRow(form, row++, "回复冷却 (ms)", aiCooldownField, null);
        addRow(form, row++, "上下文条数", aiMaxContextField, null);
        addRow(form, row++, "请求超时 (ms)", aiTimeoutField, null);
        addRow(form, row++, "maxTokens", aiMaxTokensField, null);
        addRow(form, row++, "单条最大字符", aiMessageMaxCharsField, "默认 30");
        addRow(form, row++, "单次最大条数", aiMaxMessagesField, "默认 5，最多 10");
        addRow(form, row++, "多条间隔 (ms)", aiReplyDelayField, "默认 600");
        addNote(form, row,
                "强制提示词固定包含交流方式、法律道德底线和输出格式；"
                        + "自定义提示词只补充人设。被 @ 时必须回复，关键词按概率参与，"
                        + "其他消息按随机概率参与。系统消息只作为上下文，不单独触发回复；"
                        + "频繁上下线只保留最后一条。修改 AI 配置后需要重启服务。");

        JScrollPane scroll = new JScrollPane(form);
        scroll.setBorder(null);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        JPanel panel = new JPanel(new BorderLayout());
        panel.add(scroll, BorderLayout.CENTER);
        return panel;
    }

    private JPanel buildVersionPanel() {
        JPanel panel = formPanel();
        int row = 0;
        addRow(panel, row++, "服务端版本",
                readOnlyField(ServerVersion.VERSION_NAME), null);
        addRow(panel, row++, "内部版本号",
                readOnlyField(String.valueOf(ServerVersion.VERSION_CODE)), null);
        addRow(panel, row++, "协议版本",
                readOnlyField(String.valueOf(VersionPolicy.PROTOCOL_VERSION)), null);
        addRow(panel, row++, "客户端版本区间",
                readOnlyField(VersionPolicy.MIN_CLIENT_VERSION_CODE
                        + "–" + VersionPolicy.MAX_CLIENT_VERSION_CODE), null);
        addNote(panel, row,
                "版本区间由服务端代码常量控制，此处只读；修改后需重新编译服务端。");
        return panel;
    }

    private JPanel formPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(new EmptyBorder(16, 16, 16, 16));
        return panel;
    }

    private void addRow(JPanel panel, int row, String label,
                        JComponent field, String hint) {
        GridBagConstraints labelConstraints = new GridBagConstraints();
        labelConstraints.gridx = 0;
        labelConstraints.gridy = row;
        labelConstraints.anchor = GridBagConstraints.WEST;
        labelConstraints.insets = new Insets(0, 0, 10, 12);
        panel.add(new JLabel(label), labelConstraints);

        GridBagConstraints fieldConstraints = new GridBagConstraints();
        fieldConstraints.gridx = 1;
        fieldConstraints.gridy = row;
        fieldConstraints.weightx = 1;
        fieldConstraints.fill = GridBagConstraints.HORIZONTAL;
        fieldConstraints.anchor = GridBagConstraints.WEST;
        fieldConstraints.insets = new Insets(0, 0, 10, 0);
        panel.add(field, fieldConstraints);

        if (hint != null) {
            GridBagConstraints hintConstraints = new GridBagConstraints();
            hintConstraints.gridx = 2;
            hintConstraints.gridy = row;
            hintConstraints.anchor = GridBagConstraints.WEST;
            hintConstraints.insets = new Insets(0, 10, 10, 0);
            JLabel hintLabel = new JLabel(hint);
            hintLabel.setForeground(new Color(0x777777));
            panel.add(hintLabel, hintConstraints);
        }
    }

    private void addNote(JPanel panel, int row, String text) {
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.gridx = 0;
        constraints.gridy = row;
        constraints.gridwidth = 3;
        constraints.anchor = GridBagConstraints.WEST;
        constraints.fill = GridBagConstraints.HORIZONTAL;
        constraints.insets = new Insets(8, 0, 0, 0);
        JLabel note = new JLabel(text);
        note.setForeground(new Color(0x777777));
        panel.add(note, constraints);
    }

    private JTextField readOnlyField(String text) {
        JTextField field = new JTextField(text, 20);
        field.setEditable(false);
        field.setFocusable(false);
        return field;
    }

    private void fillFields() {
        portField.setText(String.valueOf(original.port));
        maxClientsField.setText(String.valueOf(original.maxClients));
        joinTimeoutField.setText(String.valueOf(original.joinTimeoutMs));
        heartbeatTimeoutField.setText(String.valueOf(original.heartbeatTimeoutMs));
        roomNameField.setText(original.roomName);
        announcementField.setText(original.roomAnnouncement);
        roomAvatarField.setText(original.roomAvatar);
        dbPathField.setText(original.dbPath);
        authKeyField.setText(original.authKey);

        aiEnabledCheck.setSelected(original.aiEnabled);
        aiVisionCheck.setSelected(original.aiVision);
        aiSystemMessagesCheck.setSelected(original.aiSystemMessages);
        apiKeyField.setText(original.aiApiKey);
        aiModelField.setText(original.aiModel);
        aiBaseUrlField.setText(original.aiBaseUrl);
        aiNameField.setText(original.aiName);
        aiMentionsField.setText(original.aiMentions);
        aiKeywordsField.setText(original.aiKeywords);
        aiKeywordChanceField.setText(String.valueOf(original.aiKeywordChance));
        aiRandomChanceField.setText(String.valueOf(original.aiRandomChance));
        aiAvatarField.setText(original.aiAvatar);
        aiForcedPromptArea.setText(AiPrompts.FORCED_PROMPT);
        aiForcedPromptArea.setCaretPosition(0);
        aiPromptArea.setText(original.aiPromptCustom);
        aiPromptArea.setCaretPosition(0);
        aiCooldownField.setText(String.valueOf(original.aiCooldownMs));
        aiMaxContextField.setText(String.valueOf(original.aiMaxContext));
        aiTimeoutField.setText(String.valueOf(original.aiTimeoutMs));
        aiMaxTokensField.setText(String.valueOf(original.aiMaxTokens));
        aiMessageMaxCharsField.setText(String.valueOf(original.aiMessageMaxChars));
        aiMaxMessagesField.setText(String.valueOf(original.aiMaxMessages));
        aiReplyDelayField.setText(String.valueOf(original.aiReplyDelayMs));
    }

    private void bindActions() {
        showAuthKey.addActionListener(e -> setVisible(authKeyField, showAuthKey));
        showApiKey.addActionListener(e -> setVisible(apiKeyField, showApiKey));
        generateKeyButton.addActionListener(e -> {
            authKeyField.setText(ServerConfigStore.generateAuthKey());
            showAuthKey.setSelected(true);
            setVisible(authKeyField, showAuthKey);
        });
        browseAvatarButton.addActionListener(e -> browseAvatar());
        browseRoomAvatarButton.addActionListener(e -> browseRoomAvatar());
    }

    private void setVisible(JPasswordField field, JCheckBox toggle) {
        field.setEchoChar(toggle.isSelected() ? (char) 0 : PASSWORD_ECHO);
    }

    private void browseAvatar() {
        File file = chooseImageFile();
        if (file != null) {
            aiAvatarField.setText(file.getAbsolutePath());
        }
    }

    private void browseRoomAvatar() {
        File file = chooseImageFile();
        if (file != null) {
            roomAvatarField.setText(file.getAbsolutePath());
        }
    }

    private File chooseImageFile() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileFilter(new FileNameExtensionFilter(
                "图片 (*.png, *.jpg, *.jpeg)", "png", "jpg", "jpeg"));
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            return chooser.getSelectedFile();
        }
        return null;
    }

    private void save() {
        try {
            ServerConfig updated = buildConfig();
            ServerConfigStore.save(updated, configPath);
            savedConfig = updated;
            dispose();
        } catch (IllegalArgumentException e) {
            JOptionPane.showMessageDialog(this, e.getMessage(),
                    "配置无效", JOptionPane.WARNING_MESSAGE);
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this,
                    "保存配置失败：" + e.getMessage(),
                    "保存失败", JOptionPane.ERROR_MESSAGE);
        }
    }

    private ServerConfig buildConfig() {
        String roomName = text(roomNameField);
        if (roomName.isEmpty()) {
            roomNameField.requestFocusInWindow();
            throw new IllegalArgumentException("房间名称不能为空");
        }
        String dbPath = text(dbPathField);
        if (dbPath.isEmpty()) {
            dbPathField.requestFocusInWindow();
            throw new IllegalArgumentException("数据库路径不能为空");
        }
        String authKey = password(authKeyField);
        if (authKey.isEmpty()) {
            authKeyField.requestFocusInWindow();
            throw new IllegalArgumentException("连接密钥不能为空");
        }

        Properties properties = original.toProperties();
        properties.setProperty("server.port",
                String.valueOf(readInt(portField, "服务器端口")));
        properties.setProperty("server.maxClients",
                String.valueOf(readInt(maxClientsField, "最大连接数")));
        properties.setProperty("server.joinTimeoutMs",
                String.valueOf(readInt(joinTimeoutField, "加入超时")));
        properties.setProperty("server.heartbeatTimeoutMs",
                String.valueOf(readInt(heartbeatTimeoutField, "心跳超时")));
        properties.setProperty("room.name", roomName);
        properties.setProperty("room.announcement", text(announcementField));
        properties.setProperty("room.avatar", text(roomAvatarField));
        properties.setProperty("db.path", dbPath);
        properties.setProperty("auth.key", authKey);

        properties.setProperty("ai.enabled",
                String.valueOf(aiEnabledCheck.isSelected()));
        properties.setProperty("ai.vision",
                String.valueOf(aiVisionCheck.isSelected()));
        properties.setProperty("ai.systemMessages",
                String.valueOf(aiSystemMessagesCheck.isSelected()));
        properties.setProperty("ai.apiKey", password(apiKeyField));
        properties.setProperty("ai.model", text(aiModelField));
        properties.setProperty("ai.baseUrl", text(aiBaseUrlField));
        properties.setProperty("ai.name", text(aiNameField));
        properties.setProperty("ai.mentions", text(aiMentionsField));
        properties.setProperty("ai.keywords", text(aiKeywordsField));
        properties.setProperty("ai.keywordChance",
                String.valueOf(readInt(aiKeywordChanceField, "关键词概率")));
        properties.setProperty("ai.randomChance",
                String.valueOf(readInt(aiRandomChanceField, "随机参与概率")));
        properties.setProperty("ai.avatar", text(aiAvatarField));
        properties.setProperty("ai.prompt.custom", aiPromptArea.getText().trim());
        properties.setProperty("ai.cooldownMs",
                String.valueOf(readInt(aiCooldownField, "回复冷却")));
        properties.setProperty("ai.maxContext",
                String.valueOf(readInt(aiMaxContextField, "上下文条数")));
        properties.setProperty("ai.timeoutMs",
                String.valueOf(readInt(aiTimeoutField, "请求超时")));
        properties.setProperty("ai.maxTokens",
                String.valueOf(readInt(aiMaxTokensField, "maxTokens")));
        properties.setProperty("ai.messageMaxChars",
                String.valueOf(readInt(aiMessageMaxCharsField, "单条最大字符")));
        properties.setProperty("ai.maxMessages",
                String.valueOf(readInt(aiMaxMessagesField, "单次最大条数")));
        properties.setProperty("ai.replyDelayMs",
                String.valueOf(readInt(aiReplyDelayField, "多条间隔")));

        ServerConfig config = new ServerConfig(properties);
        ServerConfigStore.validate(config);
        return config;
    }

    private int readInt(JTextField field, String name) {
        try {
            return Integer.parseInt(text(field));
        } catch (NumberFormatException e) {
            field.requestFocusInWindow();
            throw new IllegalArgumentException(name + "必须是整数");
        }
    }

    private static String text(JTextField field) {
        String value = field.getText();
        return value == null ? "" : value.trim();
    }

    private static String password(JPasswordField field) {
        return new String(field.getPassword()).trim();
    }

    private static void applyFont(Component component, Font font) {
        component.setFont(font);
        if (component instanceof Container) {
            for (Component child : ((Container) component).getComponents()) {
                applyFont(child, font);
            }
        }
    }

    private static Font uiFont(int style, int size) {
        String[] candidates = {
                "Microsoft YaHei",
                "微软雅黑",
                "PingFang SC",
                "Noto Sans CJK SC",
                "SimSun",
                "SansSerif"
        };
        Set<String> available = new HashSet<>(Arrays.asList(
                GraphicsEnvironment.getLocalGraphicsEnvironment()
                        .getAvailableFontFamilyNames()));
        for (String name : candidates) {
            if (available.contains(name)) {
                return new Font(name, style, size);
            }
        }
        return new Font(Font.SANS_SERIF, style, size);
    }
}
