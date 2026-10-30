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

    public RoomService(Room room) {
        this.room = room;
    }

    public Room getRoom() {
        return room;
    }

    public Member join(String nickname, String ip) {
        String id;
        do {
            id = "u-" + UUID.randomUUID().toString()
                    .replace("-", "")
                    .substring(0, 12);
        } while (online.containsKey(id));

        Member member = new Member(
                id,
                nickname,
                0,
                false,
                ip,
                System.currentTimeMillis()
        );
        online.put(id, member);
        return member;
    }

    public void leave(String userId) {
        online.remove(userId);
    }

    public int size() {
        return online.size();
    }

    public List<Member> snapshot() {
        List<Member> members = new ArrayList<>(online.values());
        members.sort(Comparator
                .comparingLong((Member member) -> member.connectedAt)
                .thenComparing(member -> member.id));
        return members;
    }
}
