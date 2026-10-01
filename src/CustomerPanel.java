import javax.swing.*;
import javax.swing.border.LineBorder;
import javax.swing.border.TitledBorder;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableCellRenderer;
import java.awt.*;
import java.util.List;

class CustomerPanel extends JPanel {
    private JTextField firstNameField, lastNameField, contactField, addressField;
    private JTable customerTable;
    private DefaultTableModel tableModel;

    public CustomerPanel() {
        setLayout(new BorderLayout(10, 10));
        setBorder(BorderFactory.createEmptyBorder(15, 15, 15, 15));
        add(createInputPanel(), BorderLayout.NORTH);
        add(new JScrollPane(createTablePanel()), BorderLayout.CENTER);
        loadCustomerData();
    }

    private JPanel createInputPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(new TitledBorder("New Customer Registration"));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(5, 5, 5, 5);
        gbc.anchor = GridBagConstraints.WEST;

        addField(panel, gbc, "First Name:", 0);
        firstNameField = createStyledTextField(300, 28);
        panel.add(firstNameField, getGBC(1, 0));

        addField(panel, gbc, "Last Name:", 1);
        lastNameField = createStyledTextField(300, 28);
        panel.add(lastNameField, getGBC(1, 1));

        addField(panel, gbc, "Contact No:", 2);
        contactField = createStyledTextField(300, 28);
        panel.add(contactField, getGBC(1, 2));

        addField(panel, gbc, "Address:", 3);
        addressField = createStyledTextField(300, 28);
        panel.add(addressField, getGBC(1, 3));

        JButton saveBtn = new StyledButton("Save Customer");
        saveBtn.setForeground(Color.black);
        GridBagConstraints saveGbc = getGBC(1, 4);
        saveGbc.anchor = GridBagConstraints.EAST;
        panel.add(saveBtn, saveGbc);
        saveBtn.addActionListener(e -> saveCustomer());

        return panel;
    }

    private JPanel createTablePanel() {
        String[] columns = {"ID", "First Name", "Last Name", "Contact", "Address", "Actions"};
        tableModel = new DefaultTableModel(columns, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return column == 5;
            }
        };

        customerTable = new JTable(tableModel);
        styleTable(customerTable);
        customerTable.getColumnModel().getColumn(5).setCellRenderer(new ButtonRenderer());
        customerTable.getColumnModel().getColumn(5).setCellEditor(new ButtonEditor());

        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(new TitledBorder("Existing Customers"));
        panel.add(new JScrollPane(customerTable), BorderLayout.CENTER);
        return panel;
    }

    private void loadCustomerData() {
        tableModel.setRowCount(0);
        CSVHandler.readRecords("Customers.csv").forEach(row ->
                tableModel.addRow(new Object[]{row[0], row[1], row[2], row[3], row[4], "Edit/Delete"})
        );
    }

    private void saveCustomer() {
        if (firstNameField.getText().trim().isEmpty() || lastNameField.getText().trim().isEmpty()) {
            JOptionPane.showMessageDialog(this, "First Name and Last Name are required!", "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }

        java.util.List<String[]> customers = CSVHandler.readRecords("Customers.csv");
        int newId = customers.stream()
                .mapToInt(c -> Integer.parseInt(c[0]))
                .max()
                .orElse(0) + 1;

        String[] data = {
                String.valueOf(newId),
                firstNameField.getText().trim(),
                lastNameField.getText().trim(),
                contactField.getText().trim(),
                addressField.getText().trim()
        };

        CSVHandler.saveRecord("Customers.csv", data);
        loadCustomerData();
        clearFields();
        JOptionPane.showMessageDialog(this, "Customer saved successfully!", "Success", JOptionPane.INFORMATION_MESSAGE);
    }

    private void clearFields() {
        firstNameField.setText("");
        lastNameField.setText("");
        contactField.setText("");
        addressField.setText("");
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

    class ButtonEditor extends DefaultCellEditor {
        private JButton button;
        private String customerId;

        public ButtonEditor() {
            super(new JTextField());
            button = new JButton();
            button.addActionListener(e -> {
                int response = JOptionPane.showConfirmDialog(null,
                        "Do you want to edit this customer?(no to delete)", "Select Action",
                        JOptionPane.YES_NO_OPTION);

                if (response == JOptionPane.YES_OPTION) {
                    showEditDialog(customerId);
                } else {
                    CSVHandler.deleteRecord("Customers.csv", customerId);
                    loadCustomerData();
                }
            });
        }

        public Component getTableCellEditorComponent(JTable table, Object value,
                                                     boolean isSelected, int row, int column) {
            customerId = table.getValueAt(row, 0).toString();
            return button;
        }

        private void showEditDialog(String customerId) {
            List<String[]> customers = CSVHandler.readRecords("Customers.csv");
            String[] customer = customers.stream()
                    .filter(c -> c[0].equals(customerId))
                    .findFirst()
                    .orElse(null);

            JDialog editDialog = new JDialog();
            editDialog.setLayout(new GridLayout(5, 2, 10, 10));

            JTextField firstNameField = new JTextField(customer[1]);
            JTextField lastNameField = new JTextField(customer[2]);
            JTextField contactField = new JTextField(customer[3]);
            JTextField addressField = new JTextField(customer[4]);

            editDialog.add(new JLabel("First Name:"));
            editDialog.add(firstNameField);
            editDialog.add(new JLabel("Last Name:"));
            editDialog.add(lastNameField);
            editDialog.add(new JLabel("Contact:"));
            editDialog.add(contactField);
            editDialog.add(new JLabel("Address:"));
            editDialog.add(addressField);

            JButton saveBtn = new JButton("Save Changes");
            saveBtn.addActionListener(e -> {
                String[] newData = {
                        customerId,
                        firstNameField.getText(),
                        lastNameField.getText(),
                        contactField.getText(),
                        addressField.getText()
                };
                CSVHandler.updateRecord("Customers.csv", customerId, newData);
                loadCustomerData();
                editDialog.dispose();
            });
            editDialog.add(saveBtn);
            editDialog.pack();
            editDialog.setVisible(true);
        }
    }
}