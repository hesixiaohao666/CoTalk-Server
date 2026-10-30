import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;

/**
 * CoTalk 服务端 Step 0 空壳。
 *
 * <p>只搭界面骨架：房间、在线成员、日志、启动/停止、广播输入。
 * 网络与 SQLite 将在 Step 3–4 接入；AI 在 Step 10 预留。</p>
 */
public class CoTalkServer extends JFrame {

    private final JLabel statusLabel = new JLabel("未启动");
    private final JLabel roomLabel = new JLabel("CoTalk 大厅");
    private final JLabel storageLabel = new JLabel("未连接");
    private final JLabel aiLabel = new JLabel("未启用（预留）");
    private final DefaultListModel<String> memberModel = new DefaultListModel<>();
    private final JTextArea logArea = new JTextArea();
    private final JTextField broadcastField = new JTextField(30);
    private final JButton startButton = new JButton("启动服务");
    private final JButton stopButton = new JButton("停止服务");
    private final JButton broadcastButton = new JButton("广播");

    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss");

    public CoTalkServer() {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception e) {
            e.printStackTrace();
        }

        setTitle("CoTalk 服务器");
        setSize(980, 620);
        setMinimumSize(new Dimension(820, 520));
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLocationRelativeTo(null);
        setLayout(new BorderLayout());

        Font baseFont = uiFont(Font.PLAIN, 15);
        Font boldFont = uiFont(Font.BOLD, 15);
        Font titleFont = uiFont(Font.BOLD, 22);

        add(buildHeader(titleFont, boldFont), BorderLayout.NORTH);
        add(buildCenter(baseFont, boldFont), BorderLayout.CENTER);
        add(buildFooter(baseFont, boldFont), BorderLayout.SOUTH);

        bindActions();
        appendLog("Step 0：界面骨架已就绪。");
        appendLog("网络与 SQLite 将在 Step 3–4 接入；AI 在 Step 10 预留。");
    }

    private JPanel buildHeader(Font titleFont, Font boldFont) {
        JPanel header = new JPanel(new BorderLayout());
        header.setBackground(new Color(0x66CCFF));
        header.setBorder(new EmptyBorder(12, 18, 12, 18));

        JLabel title = new JLabel("CoTalk 服务器");
        title.setFont(titleFont);
        title.setForeground(Color.BLACK);

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        right.setOpaque(false);

        statusLabel.setFont(boldFont);
        statusLabel.setForeground(Color.BLACK);

        styleButton(startButton, boldFont);
        styleButton(stopButton, boldFont);
        startButton.setPreferredSize(new Dimension(110, 36));
        stopButton.setPreferredSize(new Dimension(110, 36));

        JLabel portLabel = new JLabel("端口 4700");
        portLabel.setFont(boldFont);
        portLabel.setForeground(Color.BLACK);

        right.add(statusLabel);
        right.add(portLabel);
        right.add(startButton);
        right.add(stopButton);

        header.add(title, BorderLayout.WEST);
        header.add(right, BorderLayout.EAST);
        return header;
    }

    private JSplitPane buildCenter(Font baseFont, Font boldFont) {
        JPanel left = new JPanel(new BorderLayout(0, 10));
        left.setBorder(new EmptyBorder(16, 16, 16, 8));
        left.setBackground(new Color(0xF5F5F5));

        JPanel info = new JPanel(new GridLayout(4, 2, 8, 8));
        info.setOpaque(false);
        info.setBorder(BorderFactory.createTitledBorder("房间信息"));
        info.add(label("房间", boldFont));
        info.add(roomLabel);
        info.add(label("状态", boldFont));
        info.add(statusLabelMirror());
        info.add(label("存储", boldFont));
        info.add(storageLabel);
        info.add(label("AI", boldFont));
        info.add(aiLabel);

        JList<String> memberList = new JList<>(memberModel);
        memberList.setFont(baseFont);
        memberList.setFixedCellHeight(36);
        memberList.setBackground(Color.WHITE);
        memberModel.addElement("（暂无在线成员）");
        JScrollPane memberScroll = new JScrollPane(memberList);
        memberScroll.setBorder(BorderFactory.createTitledBorder("在线成员"));

        left.add(info, BorderLayout.NORTH);
        left.add(memberScroll, BorderLayout.CENTER);

        logArea.setEditable(false);
        // 日志里包含中文；Consolas 不含中文字形，会导致“□□”/乱码。
        logArea.setFont(uiFont(Font.PLAIN, 14));
        logArea.setBackground(Color.WHITE);
        logArea.setForeground(new Color(0x333333));
        logArea.setMargin(new Insets(10, 10, 10, 10));
        logArea.setLineWrap(true);
        logArea.setWrapStyleWord(true);
        JScrollPane logScroll = new JScrollPane(logArea);
        logScroll.setBorder(BorderFactory.createTitledBorder("服务器日志"));

        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, logScroll);
        splitPane.setDividerLocation(360);
        splitPane.setResizeWeight(0.0);
        splitPane.setBorder(new EmptyBorder(10, 10, 0, 10));
        return splitPane;
    }

    private JPanel buildFooter(Font baseFont, Font boldFont) {
        JPanel footer = new JPanel(new BorderLayout(10, 0));
        footer.setBorder(new EmptyBorder(10, 16, 14, 16));
        footer.setBackground(new Color(0xF5F5F5));

        JLabel label = new JLabel("广播消息");
        label.setFont(boldFont);
        broadcastField.setFont(baseFont);
        broadcastField.setPreferredSize(new Dimension(200, 38));
        styleButton(broadcastButton, boldFont);
        broadcastButton.setPreferredSize(new Dimension(90, 38));

        footer.add(label, BorderLayout.WEST);
        footer.add(broadcastField, BorderLayout.CENTER);
        footer.add(broadcastButton, BorderLayout.EAST);
        return footer;
    }

    private JLabel label(String text, Font font) {
        JLabel label = new JLabel(text);
        label.setFont(font);
        return label;
    }

    /**
     * 选一个当前系统确实存在、且支持中文的字体。
     * 避免日志用 Consolas 时中文变成方块/问号。
     */
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

    private JLabel statusLabelMirror() {
        JLabel label = new JLabel(statusLabel.getText());
        label.setFont(statusLabel.getFont());
        statusLabel.addPropertyChangeListener("text", evt -> label.setText(statusLabel.getText()));
        return label;
    }

    private void styleButton(JButton button, Font font) {
        button.setFont(font);
        button.setBackground(new Color(0x66CCFF));
        button.setForeground(Color.BLACK);
        button.setFocusPainted(false);
        button.setCursor(new Cursor(Cursor.HAND_CURSOR));
        button.setUI(new javax.swing.plaf.basic.BasicButtonUI());
        button.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(0x33B5E5), 1, true),
                new EmptyBorder(6, 12, 6, 12)
        ));
        button.setOpaque(true);
    }

    private void bindActions() {
        startButton.addActionListener(e -> {
            statusLabel.setText("未连接（Step 3 实现）");
            storageLabel.setText("未打开");
            appendLog("点击了“启动服务”：Step 0 仅展示界面，网络/数据库尚未接入。");
        });

        stopButton.addActionListener(e -> {
            statusLabel.setText("已停止");
            appendLog("点击了“停止服务”：当前没有运行中的网络服务。");
        });

        broadcastButton.addActionListener(e -> {
            String text = broadcastField.getText().trim();
            if (!text.isEmpty()) {
                appendLog("广播（占位）：" + text);
                broadcastField.setText("");
            }
        });
    }

    private void appendLog(String message) {
        SwingUtilities.invokeLater(() -> {
            logArea.append("[" + timeFormat.format(new Date()) + "] " + message + "\n");
            logArea.setCaretPosition(logArea.getDocument().getLength());
        });
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            CoTalkServer server = new CoTalkServer();
            server.setVisible(true);
        });
    }
}
