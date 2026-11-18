package server;

import model.Member;

import java.util.List;

/**
 * 网络层到界面的回调。Headless 模式用打印实现。
 */
public interface ServerEvents {

    void onLog(String message);

    /**
     * 异常日志：界面/终端只显示摘要，文件日志记录堆栈。
     */
    default void onError(String message, Throwable error) {
        onLog(error == null || error.getMessage() == null
                ? message : message + "：" + error.getMessage());
    }

    void onMembersChanged(List<Member> members);

    void onStatusChanged(String status);

    void onServerAddress(String address);
}
