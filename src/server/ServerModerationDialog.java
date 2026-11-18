package server;

import model.IdentityAccount;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/**
 * 服务端管理员：入群审核与成员移除。
 */
public class ServerModerationDialog extends JDialog {

    private final ServerRuntime runtime;
    private final IdentityService service;
    private final DefaultListModel<String> pendingModel =
            new DefaultListModel<>();
    private final JList<String> pendingList = new JList<>(pendingModel);
    private final DefaultListModel<String> memberModel =
            new DefaultListModel<>();
    private final JList<String> memberList = new JList<>(memberModel);
    private List<IdentityAccount> pending = new ArrayList<>();
    private List<IdentityAccount> members = new ArrayList<>();

    public ServerModerationDialog(Window owner, ServerRuntime runtime) {
        super(owner, "成员与审核", ModalityType.APPLICATION_MODAL);
        this.runtime = runtime;
        this.service = runtime.getIdentityService();
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setSize(700, 480);
        setMinimumSize(new Dimension(560, 360));
        setLocationRelativeTo(owner);
        setContentPane(buildContent());
        refresh();
    }

    private JPanel buildContent() {
        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        if (service == null) {
            root.add(new JLabel("身份服务未初始化"), BorderLayout.CENTER);
            return root;
        }

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("入群审核", buildPendingTab());
        tabs.addTab("成员管理", buildMemberTab());
        root.add(tabs, BorderLayout.CENTER);

        JButton refresh = new JButton("刷新");
        refresh.addActionListener(e -> refresh());
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        actions.add(refresh);
        root.add(actions, BorderLayout.SOUTH);
        return root;
    }

    private JPanel buildPendingTab() {
        JPanel panel = new JPanel(new BorderLayout(6, 6));
        pendingList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        panel.add(new JScrollPane(pendingList), BorderLayout.CENTER);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton approve = new JButton("通过");
        JButton reject = new JButton("拒绝");
        approve.addActionListener(e -> review(true));
        reject.addActionListener(e -> review(false));
        actions.add(approve);
        actions.add(reject);
        panel.add(actions, BorderLayout.SOUTH);
        return panel;
    }

    private JPanel buildMemberTab() {
        JPanel panel = new JPanel(new BorderLayout(6, 6));
        memberList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        panel.add(new JScrollPane(memberList), BorderLayout.CENTER);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton kick = new JButton("移出群聊");
        kick.addActionListener(e -> kick());
        actions.add(kick);
        panel.add(actions, BorderLayout.SOUTH);
        return panel;
    }

    private void refresh() {
        if (service == null) {
            return;
        }
        try {
            pending = service.pending(500);
            pendingModel.clear();
            for (IdentityAccount account : pending) {
                pendingModel.addElement(account.username + " / "
                        + account.nickname + (account.admin
                        ? " [管理员]" : "")
                        + " / " + account.id);
            }
            members = service.approved(100000);
            memberModel.clear();
            for (IdentityAccount account : members) {
                memberModel.addElement(account.username + " / "
                        + account.nickname + (account.admin
                        ? " [管理员]" : "")
                        + " / " + account.id);
            }
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this,
                    "读取身份数据失败：" + e.getMessage(),
                    "成员与审核", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void review(boolean approve) {
        int index = pendingList.getSelectedIndex();
        if (index < 0 || index >= pending.size()) {
            return;
        }
        IdentityAccount account = pending.get(index);
        try {
            if (approve) {
                IdentityAccount approved = service.approve(
                        account.id, "server-gui");
                runtime.notifyIdentityApproved(approved);
            } else {
                service.reject(account.id, "server-gui");
            }
            refresh();
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this,
                    "审核失败：" + e.getMessage(),
                    "成员与审核", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void kick() {
        int index = memberList.getSelectedIndex();
        if (index < 0 || index >= members.size()) {
            return;
        }
        IdentityAccount account = members.get(index);
        int answer = JOptionPane.showConfirmDialog(this,
                "确定将 " + account.nickname + "（" + account.username
                        + "）移出群聊？\n该用户需要重新注册并审核。",
                "移出成员", JOptionPane.YES_NO_OPTION);
        if (answer != JOptionPane.YES_OPTION) {
            return;
        }
        try {
            runtime.kickIdentity(account.id, "server-gui");
            refresh();
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this,
                    "移除失败：" + e.getMessage(),
                    "成员与审核", JOptionPane.ERROR_MESSAGE);
        }
    }
}
