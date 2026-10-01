import java.io.*;
import java.nio.file.*;

public class FileUtils {
    private static final String DATA_DIR = System.getProperty("user.home") + File.separator + "POSSystemData";

    static {
        new File(DATA_DIR).mkdirs();
    }

    public static String getDataPath(String filename) {
        return DATA_DIR + File.separator + filename;
    }

    public static void initializeDataFiles() {
        String[] files = {"Customers.csv", "LPGs.csv", "Transactions.csv"};
        for (String file : files) {
            Path target = Paths.get(getDataPath(file));
            if (!Files.exists(target)) {
                try (InputStream is = FileUtils.class.getResourceAsStream("/" + file)) {
                    if (is != null) {
                        Files.copy(is, target, StandardCopyOption.REPLACE_EXISTING);
                    }
                } catch (IOException e) {
                    System.err.println("Error initializing " + file);
                    e.printStackTrace();
                }
            }
        }
    }
}