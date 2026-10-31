package server;

import model.Member;

import java.util.List;

/**
 * 网络层到界面的回调。Headless 模式用打印实现。
 */
public interface ServerEvents {

    void onLog(String message);

    void onMembersChanged(List<Member> members);

    void onStatusChanged(String status);

    void onServerAddress(String address);
}
