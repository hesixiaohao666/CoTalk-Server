package server;

import ai.AiPrompts;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

/**
 * 服务端可视化配置：服务器、AI 与版本信息。
 */
public class ServerSettingsDialog extends JDialog {

    private static final char PASSWORD_ECHO = '•';

    private final ServerConfig original;
    private final Path configPath;
    private final ai.pro.AiProAdminService aiProAdminService;
    private ServerConfig savedConfig;

    private final JTextField portField = new JTextField(10);
    private final JTextField maxClientsField = new JTextField(10);
    private final JTextField joinTimeoutField = new JTextField(10);
    private final JTextField heartbeatTimeoutField = new JTextField(10);
    private final JTextField reconnectGraceField = new JTextField(10);
    private final JTextField roomNameField = new JTextField(24);
    private final JTextField announcementField = new JTextField(24);
    private final JTextField roomAvatarField = new JTextField(24);
    private final JButton browseRoomAvatarButton = new JButton("浏览…");
    private final JTextField dbPathField = new JTextField(24);
    private final JPasswordField authKeyField = new JPasswordField(24);
    private final JCheckBox showAuthKey = new JCheckBox("显示");
    private final JButton generateKeyButton = new JButton("生成随机");
    private final JButton copyKeyButton = new JButton("复制");

    private final JCheckBox featureImageCheck = new JCheckBox("启用图片发送");
    private final JCheckBox featureVoiceCheck = new JCheckBox("启用语音发送");
    private final JCheckBox featureEmojiCheck = new JCheckBox("启用表情包发送");
    private final JCheckBox pushEnabledCheck = new JCheckBox("启用后台推送");

    private final JCheckBox adminEnabledCheck =
            new JCheckBox("启用管理员模式（实验性功能）");
    private final JPasswordField adminKeyField = new JPasswordField(24);
    private final JCheckBox showAdminKey = new JCheckBox("显示");
    private final JButton generateAdminKeyButton = new JButton("生成随机");
    private final JButton copyAdminKeyButton = new JButton("复制");
    private final JLabel adminKeyStatusLabel = new JLabel();

    private final JCheckBox aiProEnabledCheck =
            new JCheckBox("AI群友Pro（实验性功能）");
    private final List<JComponent> proOnlyAiRows = new ArrayList<>();
    private final List<JComponent> classicOnlyAiRows = new ArrayList<>();
    private final JTextField aiProContextMessagesField = new JTextField(10);
    private final JTextField aiProPromptMaxCharsField = new JTextField(10);
    private final JTextField aiProMemoryMaxCharsField = new JTextField(10);
    private final JTextField aiProOwnerNamesField = new JTextField(24);
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
        this(owner, config, configPath, null);
    }

    public ServerSettingsDialog(Window owner,
                                ServerConfig config,
                                Path configPath,
                                ai.pro.AiProAdminService aiProAdminService) {
        super(owner, "CoTalk 服务器设置", ModalityType.APPLICATION_MODAL);
        this.original = config;
        this.configPath = configPath;
        this.aiProAdminService = aiProAdminService;
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setContentPane(buildContent());
        fillFields();
        bindActions();
        setSize(780, 680);
        setMinimumSize(new Dimension(700, 580));
        positionTopCenter();
        applyFont(getContentPane(), uiFont(Font.PLAIN, 14));
        styleFields();
        styleButton(generateKeyButton, false);
        styleButton(copyKeyButton, false);
        styleButton(generateAdminKeyButton, false);
        styleButton(copyAdminKeyButton, false);
        styleButton(browseAvatarButton, false);
        styleButton(browseRoomAvatarButton, false);
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
        tabs.addTab("通用", buildGeneralPanel());
        tabs.addTab("管理员", buildAdminPanel());
        tabs.addTab("AI功能", buildAiPanel());
        if (aiProAdminService != null) {
            tabs.addTab("AI Pro管理",
                    new ai.pro.AiProSettingsPanel(this, aiProAdminService));
        }
        tabs.addTab("关于", buildAboutPanel());
        content.add(tabs, BorderLayout.CENTER);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 4));
        JButton saveButton = new JButton("保存");
        JButton cancelButton = new JButton("取消");
        saveButton.addActionListener(e -> save());
        cancelButton.addActionListener(e -> dispose());
        styleButton(saveButton, true);
        styleButton(cancelButton, false);
        buttons.add(saveButton);
        buttons.add(cancelButton);
        content.add(buttons, BorderLayout.SOUTH);
        getRootPane().setDefaultButton(saveButton);
        return content;
    }

    private JPanel buildGeneralPanel() {
        JPanel panel = formPanel();
        int row = 0;
        addRow(panel, row++, "服务器端口", portField, "1–65535");
        addRow(panel, row++, "最大连接数", maxClientsField, "大于 0");
        addRow(panel, row++, "加入超时", joinTimeoutField, "毫秒，大于 0");
        addRow(panel, row++, "心跳超时", heartbeatTimeoutField, "毫秒，大于 0");
        addRow(panel, row++, "重连宽限", reconnectGraceField,
                "毫秒，5000–60000");

        JPanel keyRow = new JPanel(new BorderLayout(6, 0));
        keyRow.setOpaque(false);
        keyRow.add(authKeyField, BorderLayout.CENTER);
        JPanel keyActions = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        keyActions.setOpaque(false);
        keyActions.add(showAuthKey);
        keyActions.add(generateKeyButton);
        keyActions.add(copyKeyButton);
        keyRow.add(keyActions, BorderLayout.EAST);
        addRow(panel, row++, "连接密钥", keyRow, "客户端连接前需要输入");

        addRow(panel, row++, "房间名称", roomNameField, null);
        addRow(panel, row++, "房间公告", announcementField, null);

        JPanel roomAvatarRow = new JPanel(new BorderLayout(6, 0));
        roomAvatarRow.setOpaque(false);
        roomAvatarRow.add(roomAvatarField, BorderLayout.CENTER);
        roomAvatarRow.add(browseRoomAvatarButton, BorderLayout.EAST);
        addRow(panel, row++, "房间头像", roomAvatarRow,
                "PNG/JPG 路径，留空使用默认样式");

        addRow(panel, row++, "图片发送", featureImageCheck,
                "关闭后拒绝客户端图片");
        addRow(panel, row++, "语音发送", featureVoiceCheck,
                "关闭后拒绝客户端语音");
        addRow(panel, row++, "表情包发送", featureEmojiCheck,
                "关闭后拒绝客户端表情包");
        addRow(panel, row++, "后台推送", pushEnabledCheck,
                "关闭后客户端不显示本地消息通知");

        addRow(panel, row++, "数据库路径", dbPathField, null);

        JTextField pathField = new JTextField(configPath.toAbsolutePath().toString());
        pathField.setEditable(false);
        styleField(pathField);
        addRow(panel, row++, "配置文件", pathField, "只读");
        addNote(panel, row,
                "端口、连接密钥、超时、重连宽限、后台推送、房间头像、"
                        + "功能开关和 AI 配置修改后需要重启服务才生效。");

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

        addRow(form, row++, "AI群友Pro", aiProEnabledCheck,
                "实验性功能；关闭时回退经典 AI");

        addRow(form, row++, "AI 开关", aiEnabledCheck,
                "关闭或未填写 API Key 时不会出现 AI 成员");
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

        JPanel avatarRow = new JPanel(new BorderLayout(6, 0));
        avatarRow.setOpaque(false);
        avatarRow.add(aiAvatarField, BorderLayout.CENTER);
        avatarRow.add(browseAvatarButton, BorderLayout.EAST);
        addRow(form, row++, "AI 头像", avatarRow, "PNG/JPG 路径");

        addRow(form, row++, "@ 触发词", aiMentionsField, "逗号分隔");
        addRow(form, row++, "参与关键词", aiKeywordsField, "如：大肥鱼,鱼,AI");
        addRow(form, row++, "关键词概率", aiKeywordChanceField, "0–100");
        addRow(form, row++, "随机参与概率", aiRandomChanceField, "0–100");

        addRow(form, row++, "Pro 上下文条数", aiProContextMessagesField,
                "默认 10", proOnlyAiRows);
        addRow(form, row++, "Pro 提示词上限", aiProPromptMaxCharsField,
                "字符数，默认 6000", proOnlyAiRows);
        addRow(form, row++, "Pro 记忆预算", aiProMemoryMaxCharsField,
                "字符数，默认 1200", proOnlyAiRows);
        addRow(form, row++, "主人昵称", aiProOwnerNamesField,
                "逗号分隔，可空", proOnlyAiRows);

        aiForcedPromptArea.setLineWrap(true);
        aiForcedPromptArea.setWrapStyleWord(true);
        aiForcedPromptArea.setEditable(false);
        aiForcedPromptArea.setBackground(new Color(0xF2F2F2));
        JScrollPane forcedPromptScroll = new JScrollPane(aiForcedPromptArea);
        forcedPromptScroll.setPreferredSize(new Dimension(420, 90));
        addRow(form, row++, "强制提示词", forcedPromptScroll,
                "由程序固定，不可修改", classicOnlyAiRows);

        aiPromptArea.setLineWrap(true);
        aiPromptArea.setWrapStyleWord(true);
        JScrollPane promptScroll = new JScrollPane(aiPromptArea);
        promptScroll.setPreferredSize(new Dimension(420, 90));
        addRow(form, row++, "自定义提示词", promptScroll,
                "用于经典 AI 的人设和表达风格", classicOnlyAiRows);
        addRow(form, row++, "上下文条数", aiMaxContextField,
                "经典 AI 使用", classicOnlyAiRows);

        addRow(form, row++, "AI 识图", aiVisionCheck,
                "关闭后只保留图片/表情的文字描述");
        addRow(form, row++, "读取系统消息", aiSystemMessagesCheck,
                "加入/退出/撤回/拍一拍/广播等");
        addRow(form, row++, "回复冷却", aiCooldownField, "毫秒");
        addRow(form, row++, "请求超时", aiTimeoutField, "毫秒");
        addRow(form, row++, "maxTokens", aiMaxTokensField, null);
        addRow(form, row++, "单条最大字符", aiMessageMaxCharsField, "默认 30");
        addRow(form, row++, "单次最大条数", aiMaxMessagesField, "默认 5，最多 10");
        addRow(form, row++, "多条间隔", aiReplyDelayField, "毫秒，默认 600");
        addNote(form, row,
                "被 @ 时必须回复；关键词和随机参与按概率触发。"
                        + "修改 AI 配置后需要重启服务。");

        JScrollPane scroll = new JScrollPane(form);
        scroll.setBorder(null);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        JPanel panel = new JPanel(new BorderLayout());
        panel.add(scroll, BorderLayout.CENTER);
        return panel;
    }

    private JPanel buildAdminPanel() {
        JPanel panel = formPanel();
        int row = 0;
        addRow(panel, row++, "管理员模式", adminEnabledCheck,
                "关闭后拒绝所有管理员绑定");
        addRow(panel, row++, "管理员密钥状态", adminKeyStatusLabel, null);

        JPanel keyRow = new JPanel(new BorderLayout(6, 0));
        keyRow.setOpaque(false);
        keyRow.add(adminKeyField, BorderLayout.CENTER);
        JPanel keyActions = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        keyActions.setOpaque(false);
        keyActions.add(showAdminKey);
        keyActions.add(generateAdminKeyButton);
        keyActions.add(copyAdminKeyButton);
        keyRow.add(keyActions, BorderLayout.EAST);
        addRow(panel, row++, "设置新密钥", keyRow,
                "留空保留现有密钥");

        addNote(panel, row++,
                "管理员模式为实验性功能。");
        addNote(panel, row++,
                "管理员密钥仅保存 PBKDF2 派生 verifier，配置文件不会回显明文。");
        addNote(panel, row++,
                "生成新密钥后请立即复制保存；保存配置后将不再显示明文。");
        addNote(panel, row++,
                "启用管理员模式前必须已设置密钥。保存后管理员开关与密钥即时生效，"
                        + "并自动解除所有在线客户端的管理员绑定；其他配置仍需重启。");

        JScrollPane scroll = new JScrollPane(panel);
        scroll.setBorder(null);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.add(scroll, BorderLayout.CENTER);
        return wrapper;
    }

    private JPanel buildAboutPanel() {
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

        addRow(panel, row++, "技术栈", readOnlyArea(
                "服务端：Java / Swing / Headless\n"
                        + "客户端：Android Java / XML\n"
                        + "数据存储：SQLite\n"
                        + "网络通信：TCP Socket (NDJSON)"), null);
        addRow(panel, row++, "更新日志", readOnlyArea(
                "版本：0.3.5(beta)\n"
                        + "更新时间：2026-11-13\n"
                        + "- 新增后台推送能力与重连宽限配置\n"
                        + "- 新增管理员模式、密钥与房间管理接口\n"
                        + "- 新增 AI 群友 Pro：多层记忆、情绪、待办与表情\n"
                        + "- 新增 AI Pro 管理面板与 Headless 命令\n"
                        + "- 此次更新仅兼容 v0.3.5(beta) 版本的客户端"), null);
        addRow(panel, row++, "开发信息", readOnlyArea(
                "作者：何似小浩\n"
                        + "开源仓库：\n"
                        + "CoTalk 客户端：https://github.com/hesixiaohao666/CoTalk\n"
                        + "CoTalk 服务端：https://github.com/hesixiaohao666/CoTalk-Server"), null);
        addNote(panel, row, "以上内容为只读信息。");

        JScrollPane scroll = new JScrollPane(panel);
        scroll.setBorder(null);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.add(scroll, BorderLayout.CENTER);
        return wrapper;
    }

    private JScrollPane readOnlyArea(String text) {
        JTextArea area = new JTextArea(text, 4, 32);
        area.setEditable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setOpaque(false);
        area.setBorder(new EmptyBorder(0, 0, 0, 0));
        area.setForeground(new Color(0x555555));
        JScrollPane scroll = new JScrollPane(area);
        scroll.setPreferredSize(new Dimension(420, 92));
        scroll.setBorder(BorderFactory.createLineBorder(
                new Color(0xD0D0D0)));
        return scroll;
    }

    private JPanel formPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(new EmptyBorder(16, 16, 16, 16));
        return panel;
    }

    private void addRow(JPanel panel, int row, String label,
                        JComponent field, String hint) {
        addRow(panel, row, label, field, hint, null);
    }

    private void addRow(JPanel panel, int row, String label,
                        JComponent field, String hint,
                        List<JComponent> group) {
        JLabel labelView = new JLabel(label);
        GridBagConstraints labelConstraints = new GridBagConstraints();
        labelConstraints.gridx = 0;
        labelConstraints.gridy = row;
        labelConstraints.anchor = GridBagConstraints.WEST;
        labelConstraints.insets = new Insets(0, 0, 10, 12);
        panel.add(labelView, labelConstraints);
        if (group != null) {
            group.add(labelView);
            group.add(field);
        }

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
            if (group != null) {
                group.add(hintLabel);
            }
        }
    }

    private void addNote(JPanel panel, int row, String text) {
        addNote(panel, row, text, null);
    }

    private JLabel addNote(JPanel panel, int row, String text,
                           List<JComponent> group) {
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
        if (group != null) {
            group.add(note);
        }
        return note;
    }

    private JTextField readOnlyField(String text) {
        JTextField field = new JTextField(text, 20);
        field.setEditable(false);
        field.setFocusable(false);
        return field;
    }

    private void styleFields() {
        JTextField[] fields = {
                portField, maxClientsField, joinTimeoutField,
                heartbeatTimeoutField, reconnectGraceField,
                roomNameField, announcementField,
                roomAvatarField, dbPathField, authKeyField,
                adminKeyField,
                aiProContextMessagesField, aiProPromptMaxCharsField,
                aiProMemoryMaxCharsField, aiProOwnerNamesField,
                apiKeyField, aiModelField, aiBaseUrlField, aiNameField,
                aiMentionsField, aiKeywordsField, aiKeywordChanceField,
                aiRandomChanceField, aiAvatarField, aiCooldownField,
                aiMaxContextField, aiTimeoutField, aiMaxTokensField,
                aiMessageMaxCharsField, aiMaxMessagesField, aiReplyDelayField
        };
        for (JTextField field : fields) {
            styleField(field);
        }
    }

    private static void styleField(JTextField field) {
        field.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(0xB8B8B8), 1, true),
                new EmptyBorder(4, 8, 4, 8)));
        field.setBackground(Color.WHITE);
    }

    private static void styleButton(JButton button, boolean primary) {
        button.setFont(uiFont(Font.PLAIN, 14));
        button.setFocusPainted(false);
        button.setCursor(new Cursor(Cursor.HAND_CURSOR));
        button.setUI(new javax.swing.plaf.basic.BasicButtonUI());
        button.setOpaque(true);
        button.setBackground(primary ? new Color(0x66CCFF) : Color.WHITE);
        button.setForeground(Color.BLACK);
        button.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(
                        primary ? new Color(0x33B5E5) : new Color(0x66CCFF),
                        1, true),
                new EmptyBorder(5, 12, 5, 12)));
    }

    private void fillFields() {
        portField.setText(String.valueOf(original.port));
        maxClientsField.setText(String.valueOf(original.maxClients));
        joinTimeoutField.setText(String.valueOf(original.joinTimeoutMs));
        heartbeatTimeoutField.setText(String.valueOf(original.heartbeatTimeoutMs));
        reconnectGraceField.setText(String.valueOf(original.reconnectGraceMs));
        roomNameField.setText(original.roomName);
        announcementField.setText(original.roomAnnouncement);
        roomAvatarField.setText(original.roomAvatar);
        dbPathField.setText(original.dbPath);
        authKeyField.setText(original.authKey);

        featureImageCheck.setSelected(original.featureImage);
        featureVoiceCheck.setSelected(original.featureVoice);
        featureEmojiCheck.setSelected(original.featureEmoji);
        pushEnabledCheck.setSelected(original.pushEnabled);
        adminEnabledCheck.setSelected(original.adminEnabled);
        adminKeyField.setText("");
        updateAdminKeyStatus(false);

        aiProEnabledCheck.setSelected(original.aiProEnabled);
        updateAiModeVisibility();
        aiProContextMessagesField.setText(String.valueOf(original.aiPro.getInt(
                "ai.pro.prompt.contextMessages")));
        aiProPromptMaxCharsField.setText(String.valueOf(original.aiPro.getInt(
                "ai.pro.prompt.maxChars")));
        aiProMemoryMaxCharsField.setText(String.valueOf(original.aiPro.getInt(
                "ai.pro.prompt.maxMemoryChars")));
        aiProOwnerNamesField.setText(original.aiPro.ownerNames());
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
        aiProEnabledCheck.addActionListener(e -> updateAiModeVisibility());
        showAuthKey.addActionListener(e -> setVisible(authKeyField, showAuthKey));
        showApiKey.addActionListener(e -> setVisible(apiKeyField, showApiKey));
        generateKeyButton.addActionListener(e -> {
            authKeyField.setText(ServerConfigStore.generateAuthKey());
            showAuthKey.setSelected(true);
            setVisible(authKeyField, showAuthKey);
        });
        copyKeyButton.addActionListener(e -> copyKey(
                authKeyField, copyKeyButton, "连接密钥"));
        browseAvatarButton.addActionListener(e -> browseAvatar());
        browseRoomAvatarButton.addActionListener(e -> browseRoomAvatar());
        showAdminKey.addActionListener(e -> {
            adminKeyField.setEchoChar(showAdminKey.isSelected()
                    ? (char) 0 : PASSWORD_ECHO);
        });
        generateAdminKeyButton.addActionListener(e -> {
            adminKeyField.setText(AdminAuth.generateKey());
            showAdminKey.setSelected(true);
            adminKeyField.setEchoChar((char) 0);
            updateAdminKeyStatus(true);
        });
        copyAdminKeyButton.addActionListener(e -> copyKey(
                adminKeyField, copyAdminKeyButton, "管理员密钥"));
    }

    private void updateAiModeVisibility() {
        boolean pro = aiProEnabledCheck.isSelected();
        for (JComponent component : proOnlyAiRows) {
            component.setVisible(pro);
        }
        for (JComponent component : classicOnlyAiRows) {
            component.setVisible(!pro);
        }
        revalidate();
        repaint();
    }

    private void copyKey(JPasswordField field,
                         JButton button,
                         String keyName) {
        String value = password(field);
        if (value.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                    keyName + "为空，无法复制",
                    "复制" + keyName,
                    JOptionPane.WARNING_MESSAGE);
            return;
        }
        try {
            Toolkit.getDefaultToolkit().getSystemClipboard()
                    .setContents(new StringSelection(value), null);
            String originalText = "复制";
            button.setText("已复制");
            javax.swing.Timer timer = new javax.swing.Timer(1500,
                    e -> button.setText(originalText));
            timer.setRepeats(false);
            timer.start();
        } catch (RuntimeException e) {
            JOptionPane.showMessageDialog(this,
                    "复制失败：" + e.getMessage(),
                    "复制" + keyName,
                    JOptionPane.ERROR_MESSAGE);
        }
    }

    private void updateAdminKeyStatus(boolean pendingNewKey) {
        if (pendingNewKey) {
            adminKeyStatusLabel.setText("待保存：将更新管理员密钥");
            adminKeyStatusLabel.setForeground(new Color(0xB26A00));
            return;
        }
        boolean configured = original.adminVerifier != null
                && !original.adminVerifier.isEmpty();
        adminKeyStatusLabel.setText(configured ? "已设置" : "未设置");
        adminKeyStatusLabel.setForeground(new Color(0x2E7D32));
        if (!configured) {
            adminKeyStatusLabel.setForeground(new Color(0x777777));
        }
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
                "图片文件", "png", "jpg", "jpeg"));
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            return chooser.getSelectedFile();
        }
        return null;
    }

    private void save() {
        try {
            ServerConfig updated = buildConfig();
            ServerConfigStore.save(updated, configPath);
            adminKeyField.setText("");
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

        String adminKey = password(adminKeyField);
        String adminSalt = original.adminSalt;
        String adminVerifier = original.adminVerifier;
        int adminIterations = original.adminIterations > 0
                ? original.adminIterations : AdminAuth.DEFAULT_ITERATIONS;
        if (!adminKey.isEmpty()) {
            adminSalt = AdminAuth.generateSalt();
            try {
                adminVerifier = AdminAuth.createVerifier(
                        adminKey, adminSalt, adminIterations);
            } catch (GeneralSecurityException e) {
                throw new IllegalArgumentException(
                        "管理员密钥处理失败：" + e.getMessage());
            }
        }
        boolean adminEnabled = adminEnabledCheck.isSelected();
        if (adminEnabled && (adminSalt.isEmpty() || adminVerifier.isEmpty())) {
            adminKeyField.requestFocusInWindow();
            throw new IllegalArgumentException(
                    "启用管理员模式前必须设置管理员密钥");
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
        properties.setProperty("server.reconnectGraceMs",
                String.valueOf(readInt(reconnectGraceField, "重连宽限")));
        properties.setProperty("room.name", roomName);
        properties.setProperty("room.announcement", text(announcementField));
        properties.setProperty("room.avatar", text(roomAvatarField));
        properties.setProperty("db.path", dbPath);
        properties.setProperty("auth.key", authKey);

        properties.setProperty("features.image",
                String.valueOf(featureImageCheck.isSelected()));
        properties.setProperty("features.voice",
                String.valueOf(featureVoiceCheck.isSelected()));
        properties.setProperty("features.emoji",
                String.valueOf(featureEmojiCheck.isSelected()));
        properties.setProperty("push.enabled",
                String.valueOf(pushEnabledCheck.isSelected()));

        properties.setProperty("admin.enabled", String.valueOf(adminEnabled));
        properties.setProperty("admin.salt", adminSalt);
        properties.setProperty("admin.iterations",
                String.valueOf(adminIterations));
        properties.setProperty("admin.verifier", adminVerifier);

        properties.setProperty("ai.pro.enabled",
                String.valueOf(aiProEnabledCheck.isSelected()));
        properties.setProperty("ai.pro.prompt.contextMessages",
                String.valueOf(readInt(aiProContextMessagesField,
                        "Pro 上下文条数")));
        properties.setProperty("ai.pro.prompt.maxChars",
                String.valueOf(readInt(aiProPromptMaxCharsField,
                        "Pro 提示词上限")));
        properties.setProperty("ai.pro.prompt.maxMemoryChars",
                String.valueOf(readInt(aiProMemoryMaxCharsField,
                        "Pro 记忆预算")));
        properties.setProperty("ai.pro.owner.names",
                text(aiProOwnerNamesField));

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
