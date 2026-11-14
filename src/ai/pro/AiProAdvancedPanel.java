package ai.pro;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * AI Pro 高级选项：按分组折叠展示全部 ai.pro.* 参数。
 */
public class AiProAdvancedPanel extends JPanel {

    private final AiProConfig config;
    private final Map<String, JComponent> inputs = new LinkedHashMap<>();
    private final Map<String, AiProConfigField> fields = new LinkedHashMap<>();

    public AiProAdvancedPanel(AiProConfig config) {
        super(new BorderLayout(0, 8));
        this.config = config;
        setBorder(new EmptyBorder(8, 8, 8, 8));

        JTextArea hint = new JTextArea(
                "以下为 AI Pro 高级参数。如果您不清楚这些参数的作用，"
                        + "请不要随意修改。保存后需重启服务才会生效。");
        hint.setEditable(false);
        hint.setLineWrap(true);
        hint.setWrapStyleWord(true);
        hint.setOpaque(false);
        hint.setFocusable(false);
        hint.setColumns(50);
        hint.setRows(2);
        hint.setForeground(new Color(0xB26A00));
        hint.setBorder(new EmptyBorder(2, 2, 6, 2));
        add(hint, BorderLayout.NORTH);

        JPanel groups = new JPanel();
        groups.setLayout(new BoxLayout(groups, BoxLayout.Y_AXIS));
        groups.setOpaque(false);
        for (String group : AiProConfigMeta.groups()) {
            List<AiProConfigField> groupFields =
                    AiProConfigMeta.fieldsForGroup(group);
            if (groupFields.isEmpty()) {
                continue;
            }
            groups.add(new CollapsibleSection(group,
                    groupFields.size(), buildGroup(groupFields)));
            groups.add(Box.createVerticalStrut(4));
        }

        JScrollPane scroll = new JScrollPane(groups);
        scroll.setBorder(null);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        add(scroll, BorderLayout.CENTER);
        loadValues();
    }

    private JPanel buildGroup(List<AiProConfigField> groupFields) {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(new EmptyBorder(8, 14, 8, 8));
        int row = 0;
        for (AiProConfigField field : groupFields) {
            JLabel label = new JLabel(field.label);
            label.setToolTipText(field.tooltip());

            JComponent input = createInput(field);
            inputs.put(field.key, input);
            fields.put(field.key, field);

            JLabel hint = new JLabel(field.rangeHint());
            hint.setForeground(new Color(0x777777));
            hint.setToolTipText(field.tooltip());

            GridBagConstraints labelConstraints = new GridBagConstraints();
            labelConstraints.gridx = 0;
            labelConstraints.gridy = row;
            labelConstraints.anchor = GridBagConstraints.WEST;
            labelConstraints.insets = new Insets(0, 0, 8, 12);
            panel.add(label, labelConstraints);

            GridBagConstraints inputConstraints = new GridBagConstraints();
            inputConstraints.gridx = 1;
            inputConstraints.gridy = row;
            inputConstraints.weightx = 1;
            inputConstraints.fill = GridBagConstraints.HORIZONTAL;
            inputConstraints.anchor = GridBagConstraints.WEST;
            inputConstraints.insets = new Insets(0, 0, 8, 8);
            panel.add(input, inputConstraints);

            GridBagConstraints hintConstraints = new GridBagConstraints();
            hintConstraints.gridx = 2;
            hintConstraints.gridy = row;
            hintConstraints.anchor = GridBagConstraints.WEST;
            hintConstraints.insets = new Insets(0, 0, 8, 0);
            panel.add(hint, hintConstraints);
            row++;
        }
        return panel;
    }

    private JComponent createInput(AiProConfigField field) {
        JComponent input;
        switch (field.type) {
            case BOOLEAN:
                JCheckBox check = new JCheckBox();
                check.setOpaque(false);
                input = check;
                break;
            case SECRET:
                JPasswordField password = new JPasswordField(24);
                password.setEchoChar('•');
                styleTextInput(password);
                input = password;
                break;
            default:
                JTextField text = new JTextField(24);
                styleTextInput(text);
                input = text;
                break;
        }
        input.setToolTipText(field.tooltip());
        return input;
    }

    private static void styleTextInput(JTextField field) {
        field.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(0xB8B8B8), 1, true),
                new EmptyBorder(4, 8, 4, 8)));
        field.setBackground(Color.WHITE);
    }

    private void loadValues() {
        for (AiProConfigField field : AiProConfigMeta.advancedFields()) {
            JComponent input = inputs.get(field.key);
            if (input == null) {
                continue;
            }
            String value = config.getString(field.key);
            if (input instanceof JCheckBox check) {
                check.setSelected(Boolean.parseBoolean(value));
            } else if (input instanceof JPasswordField password) {
                password.setText(value);
            } else if (input instanceof JTextField text) {
                text.setText(value);
            }
        }
    }

    /**
     * 将高级选项写回属性集合并逐项校验；失败时定位到对应字段。
     */
    public void applyTo(Properties properties) {
        for (AiProConfigField field : AiProConfigMeta.advancedFields()) {
            JComponent input = inputs.get(field.key);
            if (input == null) {
                continue;
            }
            String raw = readValue(input);
            String value;
            try {
                value = AiProConfigMeta.normalize(field, raw);
            } catch (IllegalArgumentException e) {
                showField(field);
                throw new IllegalArgumentException(field.label
                        + "（" + field.key + "）：" + e.getMessage());
            }
            properties.setProperty(field.key, value);
        }

        try {
            new AiProConfig(properties).validate();
        } catch (IllegalArgumentException e) {
            focusByMessage(e.getMessage());
            throw new IllegalArgumentException("AI Pro 高级选项无效："
                    + e.getMessage());
        }
    }

    private static String readValue(JComponent input) {
        if (input instanceof JCheckBox check) {
            return String.valueOf(check.isSelected());
        }
        if (input instanceof JPasswordField password) {
            return new String(password.getPassword());
        }
        if (input instanceof JTextField text) {
            return text.getText();
        }
        return "";
    }

    private void focusByMessage(String message) {
        if (message == null) {
            return;
        }
        for (AiProConfigField field : fields.values()) {
            if (message.contains(field.key)) {
                showField(field);
                return;
            }
        }
    }

    private void showField(AiProConfigField field) {
        JComponent input = inputs.get(field.key);
        if (input == null) {
            return;
        }
        Component component = input;
        while (component != null) {
            if (component instanceof CollapsibleSection section) {
                section.setExpanded(true);
            }
            component = component.getParent();
        }
        SwingUtilities.invokeLater(() -> {
            input.requestFocusInWindow();
            input.scrollRectToVisible(new Rectangle(
                    0, 0, input.getWidth(), input.getHeight()));
        });
    }

    private static final class CollapsibleSection extends JPanel {

        private final JPanel body;
        private final JButton header;
        private final String title;
        private final int fieldCount;
        private boolean expanded;

        CollapsibleSection(String title, int fieldCount, JPanel body) {
            super(new BorderLayout());
            this.title = title;
            this.fieldCount = fieldCount;
            this.body = body;
            setOpaque(false);
            setAlignmentX(Component.LEFT_ALIGNMENT);

            header = new JButton(headerText(false));
            header.setHorizontalAlignment(SwingConstants.LEFT);
            header.setFocusPainted(false);
            header.setContentAreaFilled(false);
            header.setBorder(new EmptyBorder(7, 4, 7, 4));
            header.setCursor(new Cursor(Cursor.HAND_CURSOR));
            header.setFont(header.getFont().deriveFont(Font.BOLD));
            header.addActionListener(e -> setExpanded(!expanded));
            add(header, BorderLayout.NORTH);
            add(body, BorderLayout.CENTER);
            body.setVisible(false);
        }

        void setExpanded(boolean expanded) {
            if (this.expanded == expanded) {
                return;
            }
            this.expanded = expanded;
            body.setVisible(expanded);
            header.setText(headerText(expanded));
            revalidate();
            repaint();
        }

        private String headerText(boolean expanded) {
            return (expanded ? "▼ " : "▶ ") + title
                    + "（" + fieldCount + " 项）";
        }

        @Override
        public Dimension getMaximumSize() {
            return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
        }
    }
}
