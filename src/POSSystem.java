import javax.swing.*;

public class POSSystem extends JFrame {
    private JTabbedPane tabbedPane;
    private TransactionPanel transactionPanel;

    public POSSystem() {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception e) {
            e.printStackTrace();
        }

        setTitle("LPG POS System");
        setSize(1200, 800);
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setLocationRelativeTo(null);

        tabbedPane = new JTabbedPane();
        tabbedPane.addTab("Customers", new CustomerPanel());
        tabbedPane.addTab("LPG Products", new LPGPanel());
        transactionPanel = new TransactionPanel();
        tabbedPane.addTab("Transactions", transactionPanel);

        tabbedPane.addChangeListener(e -> {
            if (tabbedPane.getSelectedIndex() == 2) {
                transactionPanel.refreshDropdowns();
            }
        });

        add(tabbedPane);
        setVisible(true);
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new POSSystem());
    }
}