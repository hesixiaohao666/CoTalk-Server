package server;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import model.Member;
import protocol.Protocol;
import store.ConnectionLogStore;
import util.JsonUtil;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * TCP 服务端：accept、在线连接管理、成员广播。
 * Step 3 只处理 join/leave/ping；消息在 Step 4 接入。
 */
public class ChatServer {

    private final ServerConfig config;
    private final RoomService roomService;
    private final ConnectionLogStore connectionLogStore;
    private final ServerEvents events;

    private final Set<ClientHandler> handlers = ConcurrentHashMap.newKeySet();
    private final ExecutorService clientPool = Executors.newCachedThreadPool();

    private volatile boolean running;
    private ServerSocket serverSocket;
    private Thread acceptThread;

    public ChatServer(ServerConfig config,
                      RoomService roomService,
                      ConnectionLogStore connectionLogStore,
                      ServerEvents events) {
        this.config = config;
        this.roomService = roomService;
        this.connectionLogStore = connectionLogStore;
        this.events = events;
    }

    public void start() throws IOException {
        if (running) {
            return;
        }
        serverSocket = new ServerSocket();
        serverSocket.setReuseAddress(true);
        serverSocket.bind(new InetSocketAddress(config.port));
        running = true;

        acceptThread = new Thread(this::acceptLoop, "cotalk-accept");
        acceptThread.start();

        events.onStatusChanged("运行中");
        events.onLog("服务已启动，端口 " + config.port);
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket socket = serverSocket.accept();
                if (handlers.size() >= config.maxClients) {
                    events.onLog("拒绝连接：在线连接数已达上限 "
                            + config.maxClients);
                    socket.close();
                    continue;
                }
                socket.setTcpNoDelay(true);
                ClientHandler handler = new ClientHandler(
                        socket, this, roomService, connectionLogStore, config);
                handlers.add(handler);
                events.onLog("客户端连接："
                        + socket.getInetAddress().getHostAddress());
                clientPool.execute(handler);
            } catch (IOException e) {
                if (running) {
                    events.onLog("接受连接失败：" + e.getMessage());
                }
            }
        }
    }

    public void stop() {
        if (!running && serverSocket == null) {
            return;
        }
        running = false;
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (IOException ignored) {
            // 关闭时忽略
        }
        serverSocket = null;

        for (ClientHandler handler : new ArrayList<>(handlers)) {
            handler.close();
        }
        handlers.clear();
        clientPool.shutdownNow();

        events.onMembersChanged(Collections.emptyList());
        events.onStatusChanged("已停止");
        events.onLog("服务已停止");
    }

    public boolean isRunning() {
        return running;
    }

    public ServerConfig getConfig() {
        return config;
    }

    public RoomService getRoomService() {
        return roomService;
    }

    public ServerEvents getEvents() {
        return events;
    }

    public void unregister(ClientHandler handler) {
        handlers.remove(handler);
    }

    public void notifyMembersChanged() {
        events.onMembersChanged(roomService.snapshot());
    }

    public void broadcastMemberList() {
        if (!running) {
            return;
        }
        JsonArray members = new JsonArray();
        for (Member member : roomService.snapshot()) {
            members.add(memberJson(member));
        }
        JsonObject data = new JsonObject();
        data.add("members", members);
        String line = JsonUtil.stringify(
                Protocol.frame(Protocol.MEMBER_LIST, null, data));

        for (ClientHandler handler : new ArrayList<>(handlers)) {
            if (handler.isJoined()) {
                handler.sendLine(line);
            }
        }
    }

    public void broadcastSystem(String content) {
        if (!running) {
            return;
        }
        JsonObject data = new JsonObject();
        data.addProperty("content", content);
        data.addProperty("ts", System.currentTimeMillis());
        String line = JsonUtil.stringify(
                Protocol.frame(Protocol.SYSTEM, null, data));

        for (ClientHandler handler : new ArrayList<>(handlers)) {
            if (handler.isJoined()) {
                handler.sendLine(line);
            }
        }
    }

    public static JsonObject memberJson(Member member) {
        JsonObject json = new JsonObject();
        json.addProperty("id", member.id);
        json.addProperty("nickname", member.nickname);
        json.addProperty("avatarVersion", member.avatarVersion);
        json.addProperty("ai", member.ai);
        return json;
    }
}
