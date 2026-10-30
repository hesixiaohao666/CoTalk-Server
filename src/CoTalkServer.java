import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;

public class CoTalkServer extends JFrame {
    private JTextField inputField;
    private JTextArea displayArea;
    private JButton sendButton;

    private ServerSocket serverSocket;
    private Socket socket;
    private BufferedReader reader;
    private PrintWriter writer;

    public CoTalkServer() {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception e) {
            e.printStackTrace();
        }

        setTitle("聊天服务器");
        setSize(960, 540);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLocationRelativeTo(null);
        setLayout(new BorderLayout());

        Font globalFont = new Font("微软雅黑", Font.PLAIN, 16);
        Font buttonFont = new Font("微软雅黑", Font.BOLD, 16);

        JPanel topPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 15, 15));
        topPanel.setBackground(new Color(245, 247, 250));
        topPanel.setOpaque(true);
        topPanel.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(220, 220, 220)));

        inputField = new JTextField(30);
        inputField.setFont(globalFont);
        inputField.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(200, 200, 200), 1, true),
                new EmptyBorder(8, 10, 8, 10)
        ));

        sendButton = new JButton("发送");
        sendButton.setFont(buttonFont);
        sendButton.setBackground(new Color(0, 122, 255)); // 现代蓝
        sendButton.setForeground(Color.WHITE);
        sendButton.setFocusPainted(false);
        sendButton.setCursor(new Cursor(Cursor.HAND_CURSOR));
        sendButton.setPreferredSize(new Dimension(100, 40));
        sendButton.setUI(new javax.swing.plaf.basic.BasicButtonUI());
        sendButton.setBorderPainted(false);
        sendButton.setContentAreaFilled(true);
        sendButton.setOpaque(true);

        topPanel.add(inputField);
        topPanel.add(sendButton);

        displayArea = new JTextArea();
        displayArea.setEditable(false);
        displayArea.setFont(globalFont);
        displayArea.setBackground(new Color(252, 252, 252));
        displayArea.setForeground(new Color(51, 51, 51));
        displayArea.setMargin(new Insets(15, 15, 15, 15));
        displayArea.setLineWrap(true);

        JScrollPane scrollPane = new JScrollPane(displayArea);
        scrollPane.setBorder(BorderFactory.createEmptyBorder());
        scrollPane.getVerticalScrollBar().setUnitIncrement(16);

        add(topPanel, BorderLayout.NORTH);
        add(scrollPane, BorderLayout.CENTER);

        sendButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                sendMessage();
            }
        });

        startServer();
    }

    private void startServer() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    serverSocket = new ServerSocket(4700);
                    appendMessage("服务器已启动，等待客户端连接...");
                    socket = serverSocket.accept();
                    appendMessage("服务器接收到客户端请求，已建立连接");
                    reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                    writer = new PrintWriter(new OutputStreamWriter(socket.getOutputStream()), true);
                    receiveMessage();
                } catch (IOException e) {
                    appendMessage("服务器启动或连接失败：" + e.getMessage());
                }
            }
        }).start();
    }

    private void receiveMessage() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String message;
                    while ((message = reader.readLine()) != null) {
                        appendMessage("对方: " + message);
                    }
                } catch (IOException e) {
                    appendMessage("客户端断开连接");
                }
            }
        }).start();
    }

    private void sendMessage() {
        String message = inputField.getText().trim();
        if (message.isEmpty()) {
            return;
        }
        if (writer != null) {
            writer.println(message);
            appendMessage("我：" + message);
            inputField.setText("");
        } else {
            appendMessage("尚未建立连接，无法发送消息");
        }
    }

    private void appendMessage(String message) {
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                displayArea.append(message + "\n");
                displayArea.setCaretPosition(displayArea.getDocument().getLength());
            }
        });
    }


    public static void main(String[] args) {
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                CoTalkServer server = new CoTalkServer();
                server.setVisible(true);
            }
        });
    }
}
