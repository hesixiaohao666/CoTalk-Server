package server;

import model.Member;
import model.Room;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 单房间在线成员与临时 UUID。
 */
public class RoomService {

    private final Room room;
    private final ConcurrentHashMap<String, Member> online = new ConcurrentHashMap<>();
    private volatile Member aiMember;

    public RoomService(Room room) {
        this.room = room;
    }

    /**
     * AI 不是真实连接，不进入 online map，只参与成员快照。
     */
    public void setAiMember(Member member) {
        this.aiMember = member;
    }

    public Member getAiMember() {
        return aiMember;
    }

    public Room getRoom() {
        return room;
    }

    public Member join(String nickname, String signature, String ip) {
        String id;
        do {
            id = "u-" + UUID.randomUUID().toString()
                    .replace("-", "")
                    .substring(0, 12);
        } while (online.containsKey(id));

        Member member = new Member(
                id,
                nickname,
                signature,
                0,
                false,
                ip,
                System.currentTimeMillis()
        );
        online.put(id, member);
        return member;
    }

    public Member find(String userId) {
        if (userId == null) {
            return null;
        }
        Member member = online.get(userId);
        if (member != null) {
            return member;
        }
        Member ai = aiMember;
        return ai != null && ai.id.equals(userId) ? ai : null;
    }

    public boolean isOnline(String userId) {
        return userId != null && online.containsKey(userId);
    }

    public void leave(String userId) {
        online.remove(userId);
    }

    public int size() {
        return online.size() + (aiMember == null ? 0 : 1);
    }

    public List<Member> snapshot() {
        List<Member> members = new ArrayList<>(online.values());
        Member ai = aiMember;
        if (ai != null) {
            members.add(ai);
        }
        members.sort(Comparator
                .comparingLong((Member member) -> member.connectedAt)
                .thenComparing(member -> member.id));
        return members;
    }
}
