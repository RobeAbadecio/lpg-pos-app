import javax.swing.*;
import javax.swing.border.LineBorder;
import javax.swing.border.TitledBorder;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableCellRenderer;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;

public class StyledButton extends JButton {
    public StyledButton(String text) {
        super(text);
        setFont(new Font("Segoe UI", Font.BOLD, 14));
        setBackground(new Color(15, 82, 186));
        setForeground(Color.BLACK);
        setFocusPainted(false);
        setBorder(BorderFactory.createCompoundBorder(
                new LineBorder(new Color(10, 60, 150), 1),
                BorderFactory.createEmptyBorder(8, 20, 8, 20)
        ));
        addMouseListener(new MouseAdapter() {
            public void mouseEntered(MouseEvent e) {
                setBackground(new Color(25, 113, 232));
            }
            public void mouseExited(MouseEvent e) {
                setBackground(new Color(15, 82, 186));
            }
        });
    }
}
