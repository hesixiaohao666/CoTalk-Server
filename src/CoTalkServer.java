import ai.AiResponder;
import ai.AiResponderFactory;
import model.Member;
import model.Room;
import server.AvatarService;
import server.ChatServer;
import server.MessageService;
import server.RoomService;
import server.ServerConfig;
import server.ServerEvents;
import store.AvatarStore;
import store.ConnectionLogStore;
import store.Database;
import store.MessageStore;
import store.RoomStore;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;

/**
 * CoTalk 服务端：Swing 控制台 + Step 3 网络/数据库接入。
 */
public class CoTalkServer extends JFrame {

    private final JLabel statusLabel = new JLabel("未启动");
    private final JLabel roomLabel = new JLabel("CoTalk 大厅");
    private final JLabel serverIpLabel = new JLabel("检测中…");
    private final JLabel memberCountLabel = new JLabel("0 人");
    private final JLabel storageLabel = new JLabel("未打开");
    private final JLabel aiLabel = new JLabel("未启用（预留）");
    private final DefaultListModel<String> memberModel = new DefaultListModel<>();
    private final JTextArea logArea = new JTextArea();
    private final JTextField broadcastField = new JTextField(30);
    private final JButton startButton = new JButton("启动服务");
    private final JButton stopButton = new JButton("停止服务");
    private final JButton broadcastButton = new JButton("广播");

    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss");

    private ChatServer chatServer;
    private Database database;
    private ServerConfig config;

    private final ServerEvents events = new ServerEvents() {
        @Override
        public void onLog(String message) {
            appendLog(message);
        }

        @Override
        public void onMembersChanged(List<Member> members) {
            SwingUtilities.invokeLater(() -> updateMembers(members));
        }

        @Override
        public void onStatusChanged(String status) {
            SwingUtilities.invokeLater(() -> {
                statusLabel.setText(status);
                if ("运行中".equals(status)) {
                    startButton.setEnabled(false);
                    stopButton.setEnabled(true);
                } else if ("已停止".equals(status) || "启动失败".equals(status)) {
                    startButton.setEnabled(true);
                    stopButton.setEnabled(false);
                }
            });
        }

        @Override
        public void onServerAddress(String address) {
            SwingUtilities.invokeLater(() -> serverIpLabel.setText(address));
        }
    };

    public CoTalkServer() {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception e) {
            e.printStackTrace();
        }

        setTitle("CoTalk 服务器");
        setSize(980, 620);
        setMinimumSize(new Dimension(820, 520));
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        setLocationRelativeTo(null);
        setLayout(new BorderLayout());

        Font baseFont = uiFont(Font.PLAIN, 15);
        Font boldFont = uiFont(Font.BOLD, 15);
        Font titleFont = uiFont(Font.BOLD, 22);

        add(buildHeader(titleFont, boldFont), BorderLayout.NORTH);
        add(buildCenter(baseFont, boldFont), BorderLayout.CENTER);
        add(buildFooter(baseFont, boldFont), BorderLayout.SOUTH);

        stopButton.setEnabled(false);
        bindActions();
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                stopServer();
                dispose();
                System.exit(0);
            }
        });

        appendLog("CoTalk 服务端已就绪。");
        appendLog("点击“启动服务”后监听端口 4700。");
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

        JPanel info = new JPanel(new GridLayout(6, 2, 8, 8));
        info.setOpaque(false);
        info.setBorder(BorderFactory.createTitledBorder("房间信息"));
        info.add(label("房间", boldFont));
        info.add(roomLabel);
        info.add(label("服务器 IP", boldFont));
        serverIpLabel.setText(findServerIp());
        serverIpLabel.setFont(baseFont);
        info.add(serverIpLabel);
        info.add(label("状态", boldFont));
        info.add(statusLabelMirror());
        info.add(label("在线", boldFont));
        memberCountLabel.setFont(baseFont);
        info.add(memberCountLabel);
        info.add(label("存储", boldFont));
        storageLabel.setFont(baseFont);
        info.add(storageLabel);
        info.add(label("AI", boldFont));
        aiLabel.setFont(baseFont);
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
        startButton.addActionListener(e -> startServer());
        stopButton.addActionListener(e -> stopServer());
        broadcastButton.addActionListener(e -> {
            String text = broadcastField.getText().trim();
            if (text.isEmpty()) {
                return;
            }
            if (chatServer != null && chatServer.isRunning()) {
                // 先写入聊天记录（message type=system），再发一条临时 system 供客户端 Toast。
                chatServer.broadcastSystemMessage(text);
                chatServer.broadcastSystem(text);
                appendLog("广播：" + text);
                broadcastField.setText("");
            } else {
                appendLog("服务未启动，无法广播。");
            }
        });
    }

    private void startServer() {
        if (chatServer != null && chatServer.isRunning()) {
            return;
        }

        startButton.setEnabled(false);
        statusLabel.setText("启动中…");
        try {
            config = ServerConfig.load();
            database = new Database(config.dbPath);
            database.open();

            Room room = new RoomStore(database).loadOrCreate(
                    config.roomName, config.roomAnnouncement);
            ConnectionLogStore logStore = new ConnectionLogStore(database);
            MessageStore messageStore = new MessageStore(database);
            MessageService messageService = new MessageService(messageStore);
            AvatarService avatarService = new AvatarService(
                    new AvatarStore(database), config.avatarMaxBytes);
            AiResponder aiResponder = AiResponderFactory.create(config);
            RoomService roomService = new RoomService(room);
            chatServer = new ChatServer(
                    config, roomService, logStore, messageService,
                    avatarService, aiResponder, events);
            chatServer.start();

            roomLabel.setText(room.name);
            storageLabel.setText("SQLite 已连接");
            aiLabel.setText(config.aiEnabled ? "已启用（预留）" : "未启用（预留）");
            stopButton.setEnabled(true);

            appendLog("服务已启动，端口 " + config.port + "，房间：" + room.name);
            appendLog("数据库：" + config.dbPath);
        } catch (Exception e) {
            if (chatServer != null) {
                chatServer.stop();
                chatServer = null;
            }
            closeDatabase();
            statusLabel.setText("启动失败");
            startButton.setEnabled(true);
            stopButton.setEnabled(false);
            appendLog("启动失败：" + e.getMessage());
        }
    }

    private void stopServer() {
        if (chatServer != null) {
            chatServer.stop();
            chatServer = null;
        }
        closeDatabase();

        statusLabel.setText("已停止");
        storageLabel.setText("未打开");
        roomLabel.setText("CoTalk 大厅");
        aiLabel.setText("未启用（预留）");
        memberCountLabel.setText("0 人");
        memberModel.clear();
        memberModel.addElement("（暂无在线成员）");
        startButton.setEnabled(true);
        stopButton.setEnabled(false);
    }

    private void closeDatabase() {
        if (database != null) {
            database.close();
            database = null;
        }
    }

    private void updateMembers(List<Member> members) {
        memberModel.clear();
        if (members.isEmpty()) {
            memberModel.addElement("（暂无在线成员）");
        } else {
            for (Member member : members) {
                memberModel.addElement(member.nickname + (member.ai ? " [AI]" : ""));
            }
        }
        memberCountLabel.setText(members.size() + " 人");
    }

    private void appendLog(String message) {
        SwingUtilities.invokeLater(() -> {
            logArea.append("[" + timeFormat.format(new Date()) + "] " + message + "\n");
            logArea.setCaretPosition(logArea.getDocument().getLength());
        });
    }

    /**
     * 找一个本机局域网 IPv4，用于房间信息展示；连接后会用本次连接的服务端本地地址更新。
     */
    private static String findServerIp() {
        String bestIp = null;
        int bestScore = Integer.MIN_VALUE;
        String fallbackIp = null;

        try {
            for (NetworkInterface networkInterface
                    : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!networkInterface.isUp()
                        || networkInterface.isLoopback()
                        || networkInterface.isPointToPoint()) {
                    continue;
                }
                String interfaceName = String.valueOf(
                        networkInterface.getDisplayName() + " "
                                + networkInterface.getName()).toLowerCase();
                if (isVirtualInterface(interfaceName)) {
                    continue;
                }

                for (InterfaceAddress interfaceAddress
                        : networkInterface.getInterfaceAddresses()) {
                    InetAddress address = interfaceAddress.getAddress();
                    if (!(address instanceof Inet4Address)
                            || address.isLoopbackAddress()
                            || address.isLinkLocalAddress()) {
                        continue;
                    }

                    int score = address.isSiteLocalAddress() ? 20 : 0;
                    if (containsAny(interfaceName,
                            "wi-fi", "wifi", "wlan", "wireless", "无线")) {
                        score += 100;
                    } else if (containsAny(interfaceName,
                            "以太网", "ethernet", "eth", "en")) {
                        score += 80;
                    }

                    if (fallbackIp == null) {
                        fallbackIp = address.getHostAddress();
                    }
                    if (score > bestScore) {
                        bestScore = score;
                        bestIp = address.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {
            // 使用兜底地址
        }

        if (bestIp != null) {
            return bestIp;
        }
        return fallbackIp == null ? "127.0.0.1" : fallbackIp;
    }

    private static boolean isVirtualInterface(String name) {
        return containsAny(name,
                "vmware", "vmnet", "virtualbox", "vbox", "hyper-v",
                "vethernet", "wsl", "docker", "tailscale", "zerotier",
                "bluetooth", "tap", "tun", "vpn", "npcap");
    }

    private static boolean containsAny(String value, String... keywords) {
        for (String keyword : keywords) {
            if (value.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 选一个当前系统确实存在、且支持中文的字体。
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

    public static void main(String[] args) {
        if (args.length > 0 && "--headless".equals(args[0])) {
            runHeadless();
            return;
        }
        SwingUtilities.invokeLater(() -> {
            CoTalkServer server = new CoTalkServer();
            server.setVisible(true);
        });
    }

    private static void runHeadless() {
        Database database = null;
        ChatServer server = null;
        try {
            ServerConfig config = ServerConfig.load();
            database = new Database(config.dbPath);
            database.open();

            Room room = new RoomStore(database).loadOrCreate(
                    config.roomName, config.roomAnnouncement);
            ConnectionLogStore logStore = new ConnectionLogStore(database);
            MessageStore messageStore = new MessageStore(database);
            MessageService messageService = new MessageService(messageStore);
            AvatarService avatarService = new AvatarService(
                    new AvatarStore(database), config.avatarMaxBytes);
            AiResponder aiResponder = AiResponderFactory.create(config);
            RoomService roomService = new RoomService(room);

            ServerEvents events = new ServerEvents() {
                @Override
                public void onLog(String message) {
                    System.out.println("[CoTalk] " + message);
                }

                @Override
                public void onMembersChanged(List<Member> members) {
                    System.out.println("[CoTalk] 在线 " + members.size() + " 人");
                }

                @Override
                public void onStatusChanged(String status) {
                    System.out.println("[CoTalk] 状态：" + status);
                }

                @Override
                public void onServerAddress(String address) {
                    System.out.println("[CoTalk] 服务器地址：" + address);
                }
            };

            server = new ChatServer(
                    config, roomService, logStore, messageService,
                    avatarService, aiResponder, events);
            ChatServer finalServer = server;
            Database finalDatabase = database;
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                finalServer.stop();
                finalDatabase.close();
            }));

            server.start();
            System.out.println("CoTalk headless started: port=" + config.port
                    + ", room=" + room.name + ", db=" + config.dbPath);
            new CountDownLatch(1).await();
        } catch (Exception e) {
            if (server != null) {
                server.stop();
            }
            if (database != null) {
                database.close();
            }
            e.printStackTrace();
        }
    }
}
