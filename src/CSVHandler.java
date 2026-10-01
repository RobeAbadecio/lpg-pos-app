import java.io.*;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

class CSVHandler {
    // ==== Save Record (Updated) ====
    public static void saveRecord(String filename, String[] data) {
        String path = FileUtils.getDataPath(filename);
        try (FileWriter fw = new FileWriter(path, true)) {
            fw.append(String.join(",", data)).append("\n");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // ==== Read Records (Updated) ====
    public static List<String[]> readRecords(String filename) {
        String path = FileUtils.getDataPath(filename);
        List<String[]> records = new ArrayList<>();
        File file = new File(path);
        if (!file.exists()) return records;

        try (Scanner scanner = new Scanner(file)) {
            while (scanner.hasNextLine()) {
                records.add(scanner.nextLine().split(","));
            }
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        }
        return records;
    }

    // ==== Update Record (Path Updated, Logic Unchanged) ====
    public static void updateRecord(String filename, String id, String[] newData) {
        String inputPath = FileUtils.getDataPath(filename);
        String tempPath = FileUtils.getDataPath("temp.csv");

        try {
            File inputFile = new File(inputPath);
            File tempFile = new File(tempPath);

            BufferedReader reader = new BufferedReader(new FileReader(inputFile));
            BufferedWriter writer = new BufferedWriter(new FileWriter(tempFile));

            String currentLine;
            while ((currentLine = reader.readLine()) != null) {
                String[] data = currentLine.split(",");
                if (data[0].equals(id)) {
                    writer.write(String.join(",", newData));
                } else {
                    writer.write(currentLine);
                }
                writer.newLine();
            }
            writer.close();
            reader.close();
            Files.move(tempFile.toPath(), inputFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // ==== Delete Record (Path Updated, Logic Unchanged) ====
    public static void deleteRecord(String filename, String id) {
        String inputPath = FileUtils.getDataPath(filename);
        String tempPath = FileUtils.getDataPath("temp.csv");

        try {
            File inputFile = new File(inputPath);
            File tempFile = new File(tempPath);

            BufferedReader reader = new BufferedReader(new FileReader(inputFile));
            BufferedWriter writer = new BufferedWriter(new FileWriter(tempFile));

            String currentLine;
            while ((currentLine = reader.readLine()) != null) {
                String[] data = currentLine.split(",");
                if (!data[0].equals(id)) {
                    writer.write(currentLine);
                    writer.newLine();
                }
            }
            writer.close();
            reader.close();
            Files.move(tempFile.toPath(), inputFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}