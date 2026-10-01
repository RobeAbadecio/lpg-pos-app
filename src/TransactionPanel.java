import javax.swing.*;
import javax.swing.border.LineBorder;
import javax.swing.border.TitledBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableCellRenderer;
import java.awt.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

public class TransactionPanel extends JPanel {
    private JComboBox<Customer> customerDropdown;
    private JComboBox<LPG> lpgDropdown;
    private JTextField dateField, searchField;
    private JTable transactionTable;
    private DefaultTableModel tableModel;
    private String selectedTransactionId;

    public TransactionPanel() {
        setLayout(new BorderLayout(10, 10));
        setBorder(BorderFactory.createEmptyBorder(15, 15, 15, 15));
        add(createControlPanel(), BorderLayout.NORTH);
        add(new JScrollPane(createHistoryPanel()), BorderLayout.CENTER);
        refreshDropdowns();
        loadTransactionData();
    }

    private JPanel createControlPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(new TitledBorder("Transaction Processing"));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(5, 5, 5, 5);
        gbc.anchor = GridBagConstraints.WEST;

        addField(panel, gbc, "Search Customer:", 0);
        searchField = createSearchField(250, 28);
        panel.add(searchField, getGBC(1, 0));

        addField(panel, gbc, "Select Customer:", 1);
        customerDropdown = new JComboBox<>();
        customerDropdown.setPreferredSize(new Dimension(250, 28));
        panel.add(customerDropdown, getGBC(1, 1));

        addField(panel, gbc, "Select LPG:", 2);
        lpgDropdown = new JComboBox<>();
        lpgDropdown.setPreferredSize(new Dimension(250, 28));
        panel.add(lpgDropdown, getGBC(1, 2));

        addField(panel, gbc, "Transaction Date:", 3);
        dateField = new JTextField(String.valueOf(new Date()));
        dateField.setPreferredSize(new Dimension(250, 28));
        dateField.setEditable(false);
        panel.add(dateField, getGBC(1, 3));

        JButton saveBtn = new StyledButton("Process Transaction");
        GridBagConstraints saveGbc = getGBC(1, 4);
        saveGbc.anchor = GridBagConstraints.EAST;
        panel.add(saveBtn, saveGbc);
        saveBtn.addActionListener(e -> saveTransaction());

        return panel;
    }

    private JPanel createHistoryPanel() {
        String[] columns = {"ID", "Date", "Customer", "LPG", "Amount", "Actions"};
        tableModel = new DefaultTableModel(columns, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return column == 5;
            }
        };

        transactionTable = new JTable(tableModel);
        styleTable(transactionTable);
        transactionTable.getColumnModel().getColumn(5).setCellRenderer(new ButtonRenderer());
        transactionTable.getColumnModel().getColumn(5).setCellEditor(new TransactionButtonEditor());

        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(new TitledBorder("Transaction History"));
        panel.add(new JScrollPane(transactionTable), BorderLayout.CENTER);
        return panel;
    }

    public void refreshDropdowns() {
        customerDropdown.removeAllItems();
        CSVHandler.readRecords("Customers.csv").stream()
                .map(c -> new Customer(c[0], c[1], c[2], c[3], c[4]))
                .forEach(customerDropdown::addItem);

        lpgDropdown.removeAllItems();
        CSVHandler.readRecords("LPGs.csv").stream()
                .map(l -> new LPG(l[0],l[1],Double.parseDouble(l[2]), Double.parseDouble(l[3])))
                .forEach(lpgDropdown::addItem);
    }

    private void loadTransactionData() {
        tableModel.setRowCount(0);
        CSVHandler.readRecords("Transactions.csv").forEach(row -> {
            // Add validation for row length
            if (row.length < 4) {
                System.err.println("Invalid transaction record: " + Arrays.toString(row));
                return; // Skip invalid rows
            }

            String customerName = getCustomerName(row[1]);
            String lpgDetails = getLPGDetails(row[2]);
            double amount = getLPGPrice(row[2]);
            tableModel.addRow(new Object[]{
                    row[0],    // Transaction ID
                    row[3],    // Date (now index 3 is valid)
                    customerName,
                    lpgDetails,
                    "₱" + amount,
                    "Edit/Delete"
            });
        });
    }

    private String getCustomerName(String custId) {
        return CSVHandler.readRecords("Customers.csv").stream()
                .filter(c -> c[0].equals(custId))
                .findFirst()
                .map(c -> c[1] + " " + c[2])
                .orElse("Unknown Customer");
    }

    private String getLPGDetails(String lpgId) {
        return CSVHandler.readRecords("LPGs.csv").stream()
                .filter(l -> l.length >= 4 && l[0].equals(lpgId)) // Check array length
                .findFirst()
                .map(l -> l[1] + "@" + l[3] + "kg")
                .orElse("Unknown LPG");
    }


    private double getLPGPrice(String lpgId) {
        return CSVHandler.readRecords("LPGs.csv").stream()
                .filter(l -> l.length > 1 && l[0].equals(lpgId))  // Check array length
                .findFirst()
                .map(l -> Double.parseDouble(l[2]))  // Use map() instead of mapToDouble()
                .orElse(0.0);
    }

    private void saveTransaction() {
        if (customerDropdown.getSelectedItem() == null || lpgDropdown.getSelectedItem() == null) {
            JOptionPane.showMessageDialog(this, "Please select both customer and LPG product!", "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }

        Customer customer = (Customer) customerDropdown.getSelectedItem();
        LPG lpg = (LPG) lpgDropdown.getSelectedItem();
        String dateTime = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

        String[] data;
        if (selectedTransactionId != null) {
            data = new String[]{
                    selectedTransactionId,
                    customer.getCustId(),
                    lpg.getLpgId(),
                    dateTime
            };
            CSVHandler.updateRecord("Transactions.csv", selectedTransactionId, data);
            selectedTransactionId = null;
        } else {
            java.util.List<String[]> transactions = CSVHandler.readRecords("Transactions.csv");
            int newId = transactions.stream()
                    .mapToInt(t -> Integer.parseInt(t[0]))
                    .max()
                    .orElse(0) + 1;
            data = new String[]{
                    String.valueOf(newId),
                    customer.getCustId(),
                    lpg.getLpgId(),
                    dateTime
            };
            CSVHandler.saveRecord("Transactions.csv", data);
        }

        loadTransactionData();
        clearFields();
        JOptionPane.showMessageDialog(this, "Transaction saved successfully!", "Success", JOptionPane.INFORMATION_MESSAGE);
    }

    private void clearFields() {
        dateField.setText("");
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

    private JTextField createSearchField(int width, int height) {
        JTextField tf = new JTextField();
        tf.setPreferredSize(new Dimension(width, height));
        tf.setFont(new Font("Segoe UI", Font.PLAIN, 14));
        tf.setBorder(BorderFactory.createCompoundBorder(
                new LineBorder(new Color(200, 200, 200)),
                BorderFactory.createEmptyBorder(5, 10, 5, 10)
        ));
        tf.getDocument().addDocumentListener(new DocumentListener() {
            public void changedUpdate(DocumentEvent e) {
                filterCustomers();
            }

            public void removeUpdate(DocumentEvent e) {
                filterCustomers();
            }

            public void insertUpdate(DocumentEvent e) {
                filterCustomers();
            }
        });
        return tf;
    }

    private void filterCustomers() {
        String searchText = searchField.getText().toLowerCase();
        customerDropdown.removeAllItems();
        CSVHandler.readRecords("Customers.csv").stream()
                .map(c -> new Customer(c[0], c[1], c[2], c[3], c[4]))
                .filter(c -> c.toString().toLowerCase().contains(searchText))
                .forEach(customerDropdown::addItem);
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
            setText("Delete");
            return this;
        }
    }

    class TransactionButtonEditor extends DefaultCellEditor {
        private JButton button;
        private String transactionId;

        public TransactionButtonEditor() {
            super(new JTextField());
            button = new JButton();
            button.addActionListener(e -> {
                int response = JOptionPane.showConfirmDialog(null,
                        "Do you want to delete this transaction?", "Select Action",
                        JOptionPane.YES_NO_OPTION);

                if (response != JOptionPane.YES_OPTION) {
                    showEditDialog(transactionId);
                } else {
                    CSVHandler.deleteRecord("Transactions.csv", transactionId);
                    loadTransactionData();
                }
            });
        }

        public Component getTableCellEditorComponent(JTable table, Object value,
                                                     boolean isSelected, int row, int column) {
            transactionId = table.getValueAt(row, 0).toString();
            return button;
        }

        private void showEditDialog(String transactionId) {
            List<String[]> transactions = CSVHandler.readRecords("Transactions.csv");
            String[] transaction = transactions.stream()
                    .filter(t -> t[0].equals(transactionId))
                    .findFirst()
                    .orElse(null);

            selectedTransactionId = transactionId;

            // Set selected customer
            String custId = transaction[1];
            for (int i = 0; i < customerDropdown.getItemCount(); i++) {
                if (customerDropdown.getItemAt(i).getCustId().equals(custId)) {
                    customerDropdown.setSelectedIndex(i);
                    break;
                }
            }

            // Set selected LPG
            String lpgId = transaction[2];
            for (int i = 0; i < lpgDropdown.getItemCount(); i++) {
                if (lpgDropdown.getItemAt(i).getLpgId().equals(lpgId)) {
                    lpgDropdown.setSelectedIndex(i);
                    break;
                }
            }

            dateField.setText(transaction[3]);
        }
    }
}