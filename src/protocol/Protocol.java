package protocol;

import com.google.gson.JsonObject;

/**
 * 协议常量与帧构造。
 */
public final class Protocol {

    public static final int VERSION = 3100;

    public static final String HELLO = "hello";
    public static final String AUTH_CHALLENGE = "auth_challenge";
    public static final String AUTH_RESPONSE = "auth_response";
    public static final String HELLO_OK = "hello_ok";

    public static final String JOIN = "join";
    public static final String CHAT = "chat";
    public static final String HISTORY_REQ = "history_req";
    public static final String PROFILE_UPDATE = "profile_update";
    public static final String AVATAR_REQ = "avatar_req";
    public static final String FILE_REQ = "file_req";
    public static final String RECALL_REQ = "recall_req";
    public static final String POKE_REQ = "poke_req";
    public static final String PING = "ping";
    public static final String LEAVE = "leave";

    public static final String JOIN_OK = "join_ok";
    public static final String MESSAGE_ACK = "message_ack";
    public static final String MESSAGE = "message";
    public static final String HISTORY_RES = "history_res";
    public static final String MEMBER_LIST = "member_list";
    public static final String AVATAR_RES = "avatar_res";
    public static final String FILE_RES = "file_res";
    public static final String MESSAGE_RECALL = "message_recall";
    public static final String SYSTEM = "system";
    public static final String ERROR = "error";
    public static final String PONG = "pong";

    public static final int ERR_INVALID_FRAME = 2001;
    public static final int ERR_NOT_JOINED = 2002;
    public static final int ERR_INVALID_INPUT = 2003;
    public static final int ERR_RATE_LIMITED = 2004;
    public static final int ERR_AVATAR_TOO_LARGE = 2005;
    public static final int ERR_DB = 2006;
    public static final int ERR_INTERNAL = 2007;
    public static final int ERR_VERSION = 2008;
    public static final int ERR_UNSUPPORTED = 2009;
    public static final int ERR_FULL = 2010;
    public static final int ERR_RECALL_NOT_FOUND = 2011;
    public static final int ERR_RECALL_FORBIDDEN = 2012;
    public static final int ERR_RECALL_EXPIRED = 2013;
    public static final int ERR_RECALL_STATE = 2014;
    public static final int ERR_POKE_TARGET = 2015;
    public static final int ERR_FEATURE_DISABLED = 2016;

    public static final int ERR_AUTH_FAILED = 3001;
    public static final int ERR_CLIENT_VERSION = 3002;
    public static final int ERR_PROTOCOL = 3003;
    public static final int ERR_HELLO_TIMEOUT = 3004;
    public static final int ERR_AUTH_TIMEOUT = 3005;

    private Protocol() {
    }

    public static JsonObject frame(String type, String reqId, JsonObject data) {
        JsonObject frame = new JsonObject();
        frame.addProperty("v", VERSION);
        frame.addProperty("type", type);
        if (reqId != null && !reqId.isEmpty()) {
            frame.addProperty("reqId", reqId);
        }
        frame.add("data", data == null ? new JsonObject() : data);
        return frame;
    }

    public static JsonObject error(int code, String message, String reqId) {
        JsonObject data = new JsonObject();
        data.addProperty("code", code);
        data.addProperty("message", message);
        return frame(ERROR, reqId, data);
    }
}
