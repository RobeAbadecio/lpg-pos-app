import javax.swing.*;
import javax.swing.border.LineBorder;
import javax.swing.border.TitledBorder;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableCellRenderer;
import java.awt.*;
import java.util.List;

class LPGPanel extends JPanel {
    private JTextField brandField, priceField, weightField;
    private JTable lpgTable;
    private DefaultTableModel tableModel;

    public LPGPanel() {
        setLayout(new BorderLayout(10, 10));
        setBorder(BorderFactory.createEmptyBorder(15, 15, 15, 15));
        add(createInputPanel(), BorderLayout.NORTH);
        add(new JScrollPane(createTablePanel()), BorderLayout.CENTER);
        loadLPGData();
    }

    private JPanel createInputPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(new TitledBorder("LPG Product Management"));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(5, 5, 5, 5);
        gbc.anchor = GridBagConstraints.WEST;

        addField(panel, gbc, "Brand:", 0);
        brandField = createStyledTextField(300, 28);
        panel.add(brandField, getGBC(1, 0));

        addField(panel, gbc, "Price:", 1);
        priceField = createStyledTextField(300, 28);
        panel.add(priceField, getGBC(1, 1));

        addField(panel, gbc, "Weight (kg):", 2);
        weightField = createStyledTextField(300, 28);
        panel.add(weightField, getGBC(1, 2));

        JButton saveBtn = new StyledButton("Save Product");
        GridBagConstraints saveGbc = getGBC(1, 3);
        saveGbc.anchor = GridBagConstraints.EAST;
        panel.add(saveBtn, saveGbc);
        saveBtn.addActionListener(e -> saveLPG());

        return panel;
    }

    private JPanel createTablePanel() {
        String[] columns = {"ID", "Brand", "Weight", "Price", "Actions"};
        tableModel = new DefaultTableModel(columns, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return column == 4;
            }
        };

        lpgTable = new JTable(tableModel);
        styleTable(lpgTable);
        lpgTable.getColumnModel().getColumn(4).setCellRenderer(new ButtonRenderer());
        lpgTable.getColumnModel().getColumn(4).setCellEditor(new LPGButtonEditor());

        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(new TitledBorder("LPG Products"));
        panel.add(new JScrollPane(lpgTable), BorderLayout.CENTER);
        return panel;
    }

    private void loadLPGData() {
        tableModel.setRowCount(0);
        CSVHandler.readRecords("LPGs.csv").forEach(row ->
                tableModel.addRow(new Object[]{row[0], row[1], row[3] + "kg", "₱" + row[2], "Edit/Delete"})
        );
    }

    private void saveLPG() {
        if (brandField.getText().trim().isEmpty() ||
                priceField.getText().trim().isEmpty() ||
                weightField.getText().trim().isEmpty()) {
            JOptionPane.showMessageDialog(this, "All fields are required!", "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }

        try {
            double price = Double.parseDouble(priceField.getText());
            double weight = Double.parseDouble(weightField.getText());
            String[] data = {
                    brandField.getText().trim(),
                    String.valueOf(price),
                    String.valueOf(weight)
            };

            if (lpgTable.getSelectedRow() != -1) {
                String id = tableModel.getValueAt(lpgTable.getSelectedRow(), 0).toString();
                CSVHandler.updateRecord("LPGs.csv", id, new String[]{id, data[0], data[1], data[2]});
            } else {
                java.util.List<String[]> lpgProducts = CSVHandler.readRecords("LPGs.csv");
                int newId = lpgProducts.stream()
                        .mapToInt(l -> Integer.parseInt(l[0]))
                        .max()
                        .orElse(0) + 1;
                CSVHandler.saveRecord("LPGs.csv", new String[]{String.valueOf(newId), data[0], data[1], data[2]});
            }

            loadLPGData();
            clearFields();
            JOptionPane.showMessageDialog(this, "LPG saved successfully!", "Success", JOptionPane.INFORMATION_MESSAGE);
        } catch (NumberFormatException e) {
            JOptionPane.showMessageDialog(this, "Invalid number format!", "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void clearFields() {
        brandField.setText("");
        priceField.setText("");
        weightField.setText("");
    }

    private void addField(JPanel panel, GridBagConstraints gbc, String label, int yPos) {
        gbc.gridx = 0;
        gbc.gridy = yPos;
        panel.add(new JLabel(label), gbc);
    }

    private GridBagConstraints getGBC(int x, int y) {
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = x;
        gbc.gridy = y;
        gbc.anchor = GridBagConstraints.WEST;
        gbc.insets = new Insets(5, 5, 5, 5);
        return gbc;
    }

    private JTextField createStyledTextField(int width, int height) {
        JTextField tf = new JTextField();
        tf.setPreferredSize(new Dimension(width, height));
        tf.setFont(new Font("Segoe UI", Font.PLAIN, 14));
        tf.setBorder(BorderFactory.createCompoundBorder(
                new LineBorder(new Color(200, 200, 200)),
                BorderFactory.createEmptyBorder(5, 10, 5, 10)
        ));
        return tf;
    }

    private void styleTable(JTable table) {
        table.setFont(new Font("Segoe UI", Font.PLAIN, 14));
        table.getTableHeader().setFont(new Font("Segoe UI", Font.BOLD, 14));
        table.setRowHeight(30);
        table.setShowGrid(true);
        table.setGridColor(new Color(220, 220, 220));
        table.setSelectionBackground(new Color(230, 240, 255));
        table.setAutoCreateRowSorter(true);
    }

    class ButtonRenderer extends JButton implements TableCellRenderer {
        public ButtonRenderer() {
            setOpaque(true);
        }
        public Component getTableCellRendererComponent(JTable table, Object value,
                                                       boolean isSelected, boolean hasFocus, int row, int column) {
            setText("Edit");
            return this;
        }
    }

    class LPGButtonEditor extends DefaultCellEditor {
        private JButton button;
        private String lpgId;

        public LPGButtonEditor() {
            super(new JTextField());
            button = new JButton();
            button.addActionListener(e -> {
                int response = JOptionPane.showConfirmDialog(null,
                        "Do you want to edit this product?(no to delete)", "Select Action",
                        JOptionPane.YES_NO_OPTION);

                if (response == JOptionPane.YES_OPTION) {
                    showEditDialog(lpgId);
                } else {
                    CSVHandler.deleteRecord("LPGs.csv", lpgId);
                    loadLPGData();
                }
            });
        }

        public Component getTableCellEditorComponent(JTable table, Object value,
                                                     boolean isSelected, int row, int column) {
            lpgId = table.getValueAt(row, 0).toString();
            return button;
        }

        private void showEditDialog(String lpgId) {
            List<String[]> lpgProducts = CSVHandler.readRecords("LPGs.csv");
            String[] lpg = lpgProducts.stream()
                    .filter(l -> l[0].equals(lpgId))
                    .findFirst()
                    .orElse(null);

            JDialog editDialog = new JDialog();
            editDialog.setLayout(new GridLayout(4, 2, 10, 10));

            JTextField brandField = new JTextField(lpg[2]);
            JTextField priceField = new JTextField(lpg[1]);
            JTextField weightField = new JTextField(lpg[3]);

            editDialog.add(new JLabel("Brand:"));
            editDialog.add(brandField);
            editDialog.add(new JLabel("Price:"));
            editDialog.add(priceField);
            editDialog.add(new JLabel("Weight:"));
            editDialog.add(weightField);

            JButton saveBtn = new JButton("Save Changes");

            saveBtn.addActionListener(e -> {
                try {
                    String[] newData = {
                            lpgId,
                            priceField.getText(),
                            brandField.getText(),
                            weightField.getText()
                    };
                    CSVHandler.updateRecord("LPGs.csv", lpgId, newData);
                    loadLPGData();
                    editDialog.dispose();
                } catch (NumberFormatException ex) {
                    JOptionPane.showMessageDialog(editDialog, "Invalid number format!", "Error", JOptionPane.ERROR_MESSAGE);
                }
            });
            saveBtn.setForeground(Color.BLACK);
            editDialog.add(saveBtn);
            editDialog.pack();
            editDialog.setVisible(true);
        }
    }
}