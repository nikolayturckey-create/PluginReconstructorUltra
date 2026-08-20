package dev.nik.reconstructor.gui;

import dev.nik.reconstructor.core.RecoveryEngine;
import dev.nik.reconstructor.core.RecoveryOptions;
import dev.nik.reconstructor.core.RecoveryResult;
import dev.nik.reconstructor.decompile.EngineMode;
import dev.nik.reconstructor.util.FilesEx;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.TransferHandler;
import javax.swing.UIManager;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Insets;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutionException;

/** One-screen UI: choose/drop a JAR, press one button, open src/main/java. */
public final class GuiLauncher {
    private GuiLauncher() {}

    public static void launch() {
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) {
                // The default Swing look and feel is always available.
            }
            new Window().show();
        });
    }

    private static final class Window {
        private final JFrame frame = new JFrame("Plugin Reconstructor Ultra " + RecoveryEngine.VERSION);
        private final JLabel fileName = new JLabel("Перетащи JAR сюда", SwingConstants.CENTER);
        private final JLabel filePath = new JLabel("или нажми «Выбрать JAR»", SwingConstants.CENTER);
        private final JButton chooseButton = new JButton("Выбрать JAR");
        private final JButton startButton = new JButton("Извлечь Java-исходники");
        private final JButton openSourcesButton = new JButton("Открыть исходники");
        private final JButton openResultButton = new JButton("Открыть всю папку");
        private final JProgressBar progressBar = new JProgressBar();
        private final JLabel status = new JLabel("Все настройки автоматические.", SwingConstants.CENTER);
        private final JTextArea log = new JTextArea();

        private Path inputJar;
        private Path lastOutput;
        private Path lastSources;

        private void show() {
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            frame.setMinimumSize(new Dimension(720, 570));
            frame.setSize(780, 620);
            frame.setLocationRelativeTo(null);

            JPanel root = new JPanel(new BorderLayout(14, 14));
            root.setBorder(BorderFactory.createEmptyBorder(18, 18, 18, 18));
            root.add(header(), BorderLayout.NORTH);
            root.add(center(), BorderLayout.CENTER);
            root.add(bottom(), BorderLayout.SOUTH);
            frame.setContentPane(root);
            frame.setTransferHandler(new JarTransferHandler());
            frame.setVisible(true);
        }

        private JPanel header() {
            JPanel panel = new JPanel();
            panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
            JLabel title = new JLabel("JAR → готовый Java-проект", SwingConstants.CENTER);
            title.setAlignmentX(0.5f);
            title.setFont(title.getFont().deriveFont(Font.BOLD, 24f));
            JLabel subtitle = new JLabel("Vineflower + CFR + Procyon, повторные попытки для пропущенных классов", SwingConstants.CENTER);
            subtitle.setAlignmentX(0.5f);
            subtitle.setForeground(new Color(85, 85, 85));
            panel.add(title);
            panel.add(Box.createVerticalStrut(5));
            panel.add(subtitle);
            return panel;
        }

        private JPanel center() {
            JPanel panel = new JPanel(new BorderLayout(12, 12));

            JPanel drop = new JPanel();
            drop.setPreferredSize(new Dimension(640, 170));
            drop.setLayout(new BoxLayout(drop, BoxLayout.Y_AXIS));
            drop.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(new Color(130, 130, 130), 2, true),
                    BorderFactory.createEmptyBorder(26, 18, 22, 18)
            ));
            fileName.setAlignmentX(0.5f);
            fileName.setFont(fileName.getFont().deriveFont(Font.BOLD, 19f));
            filePath.setAlignmentX(0.5f);
            filePath.setForeground(new Color(95, 95, 95));
            chooseButton.setAlignmentX(0.5f);
            chooseButton.setMargin(new Insets(8, 20, 8, 20));
            chooseButton.addActionListener(event -> chooseJar());
            drop.add(Box.createVerticalGlue());
            drop.add(fileName);
            drop.add(Box.createVerticalStrut(7));
            drop.add(filePath);
            drop.add(Box.createVerticalStrut(18));
            drop.add(chooseButton);
            drop.add(Box.createVerticalGlue());
            drop.setTransferHandler(new JarTransferHandler());
            panel.add(drop, BorderLayout.NORTH);

            log.setEditable(false);
            log.setLineWrap(true);
            log.setWrapStyleWord(true);
            log.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
            log.setText("Выбери обычный JAR, Minecraft-плагин или обфусцированный JAR.\n"
                    + "Результат появится рядом с исходным файлом в папке Recovered_<имя>.\n");
            JScrollPane scroll = new JScrollPane(log);
            scroll.setBorder(BorderFactory.createTitledBorder("Ход работы"));
            panel.add(scroll, BorderLayout.CENTER);
            return panel;
        }

        private JPanel bottom() {
            JPanel panel = new JPanel();
            panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));

            progressBar.setIndeterminate(false);
            progressBar.setStringPainted(false);
            progressBar.setAlignmentX(0.5f);
            status.setAlignmentX(0.5f);

            startButton.setEnabled(false);
            startButton.setAlignmentX(0.5f);
            startButton.setFont(startButton.getFont().deriveFont(Font.BOLD, 17f));
            startButton.setMargin(new Insets(12, 34, 12, 34));
            startButton.addActionListener(event -> recover());

            JPanel opens = new JPanel();
            openSourcesButton.setEnabled(false);
            openResultButton.setEnabled(false);
            openSourcesButton.addActionListener(event -> openPath(lastSources));
            openResultButton.addActionListener(event -> openPath(lastOutput));
            opens.add(openSourcesButton);
            opens.add(openResultButton);

            JLabel note = new JLabel("Точный исходник 1-в-1 вернуть нельзя, если обфускатор удалил имена/структуру; программа ничего не скрывает и перечисляет пропуски.", SwingConstants.CENTER);
            note.setAlignmentX(0.5f);
            note.setForeground(new Color(95, 95, 95));
            note.setFont(note.getFont().deriveFont(11f));

            panel.add(progressBar);
            panel.add(Box.createVerticalStrut(7));
            panel.add(status);
            panel.add(Box.createVerticalStrut(10));
            panel.add(startButton);
            panel.add(Box.createVerticalStrut(5));
            panel.add(opens);
            panel.add(Box.createVerticalStrut(4));
            panel.add(note);
            return panel;
        }

        private void chooseJar() {
            JFileChooser chooser = new JFileChooser(initialDirectory());
            chooser.setDialogTitle("Выбери JAR");
            chooser.setFileFilter(new FileNameExtensionFilter("Java archives (*.jar)", "jar"));
            if (chooser.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) {
                selectJar(chooser.getSelectedFile().toPath());
            }
        }

        private void selectJar(Path candidate) {
            Path normalized = candidate.toAbsolutePath().normalize();
            if (!Files.isRegularFile(normalized)
                    || !normalized.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar")) {
                showError("Нужен существующий файл с расширением .jar");
                return;
            }
            inputJar = normalized;
            fileName.setText(normalized.getFileName().toString());
            filePath.setText(shorten(normalized.toString(), 92));
            filePath.setToolTipText(normalized.toString());
            startButton.setEnabled(true);
            status.setText("Нажми одну кнопку — остальные параметры выберутся автоматически.");
            lastOutput = null;
            lastSources = null;
            openSourcesButton.setEnabled(false);
            openResultButton.setEnabled(false);
        }

        private void recover() {
            if (inputJar == null || !Files.isRegularFile(inputJar)) {
                showError("Сначала выбери JAR.");
                return;
            }
            Path output = nextAvailableOutput(inputJar);
            RecoveryOptions options = new RecoveryOptions(
                    inputJar,
                    output,
                    defaultToolsDirectory(),
                    null,
                    null,
                    null,
                    EngineMode.AUTO,
                    false,
                    true,
                    false,
                    defaultMemoryMb(),
                    Duration.ofMinutes(30),
                    null
            );

            setBusy(true);
            lastOutput = null;
            lastSources = null;
            append("\n=== " + inputJar.getFileName() + " ===\n");
            append("Результат: " + output + "\n");

            new SwingWorker<RecoveryResult, String>() {
                @Override
                protected RecoveryResult doInBackground() throws Exception {
                    return new RecoveryEngine().recover(options, this::publish);
                }

                @Override
                protected void process(List<String> chunks) {
                    chunks.forEach(message -> {
                        status.setText(message);
                        append(message + "\n");
                    });
                }

                @Override
                protected void done() {
                    setBusy(false);
                    try {
                        RecoveryResult result = get();
                        lastOutput = result.outputDirectory();
                        lastSources = result.sourceDirectory();
                        openSourcesButton.setEnabled(Files.isDirectory(lastSources));
                        openResultButton.setEnabled(Files.isDirectory(lastOutput));
                        String coverage = result.recoveredSourceUnits() + "/" + result.expectedSourceUnits();
                        if (result.completeJavaCoverage()) {
                            status.setText("Готово: Java-покрытие " + coverage + ".");
                            append("\nГотово. Все ожидаемые source units представлены Java-файлами.\n");
                        } else {
                            status.setText("Готово частично: " + coverage + "; пропущено " + result.missingSourceUnits() + ".");
                            append("\nНормальный Java не получен для " + result.missingSourceUnits()
                                    + " source units. Список: MISSING_SOURCES.txt\n");
                        }
                        append("Оригинальных class-файлов сохранено: " + result.classFiles() + "/" + result.classFiles() + "\n");
                        Toolkit.getDefaultToolkit().beep();
                        showCompletion(result);
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                        showError("Восстановление прервано.");
                    } catch (ExecutionException error) {
                        showError("Ошибка восстановления: " + safeMessage(error.getCause()));
                    }
                }
            }.execute();
        }

        private void showCompletion(RecoveryResult result) {
            String message;
            int type;
            if (result.completeJavaCoverage()) {
                message = "Готово. Получено " + result.sourceFiles() + " Java-файлов.\n"
                        + "Покрытие: " + result.recoveredSourceUnits() + "/" + result.expectedSourceUnits() + ".\n\n"
                        + result.sourceDirectory();
                type = JOptionPane.INFORMATION_MESSAGE;
            } else {
                message = "Обработка завершена, но абсолютно полный Java получить не удалось.\n"
                        + "Покрытие: " + result.recoveredSourceUnits() + "/" + result.expectedSourceUnits() + ".\n"
                        + "Пропуски перечислены в MISSING_SOURCES.txt; исходные .class сохранены полностью.\n\n"
                        + result.outputDirectory();
                type = JOptionPane.WARNING_MESSAGE;
            }
            JOptionPane.showMessageDialog(frame, message, "Plugin Reconstructor Ultra", type);
        }

        private void setBusy(boolean busy) {
            chooseButton.setEnabled(!busy);
            startButton.setEnabled(!busy && inputJar != null);
            progressBar.setIndeterminate(busy);
            openSourcesButton.setEnabled(!busy && lastSources != null && Files.isDirectory(lastSources));
            openResultButton.setEnabled(!busy && lastOutput != null && Files.isDirectory(lastOutput));
            if (busy) status.setText("Начинаю обработку...");
        }

        private void append(String message) {
            if (!SwingUtilities.isEventDispatchThread()) {
                SwingUtilities.invokeLater(() -> append(message));
                return;
            }
            log.append(message);
            log.setCaretPosition(log.getDocument().getLength());
        }

        private void openPath(Path path) {
            if (path == null || !Files.isDirectory(path)) {
                showError("Папка ещё не создана.");
                return;
            }
            try {
                if (!Desktop.isDesktopSupported()) throw new IllegalStateException("Открытие папок не поддерживается системой.");
                Desktop.getDesktop().open(path.toFile());
            } catch (Exception error) {
                showError("Не удалось открыть папку: " + safeMessage(error));
            }
        }

        private File initialDirectory() {
            if (inputJar != null && inputJar.getParent() != null) return inputJar.getParent().toFile();
            return new File(System.getProperty("user.home", "."));
        }

        private void showError(String message) {
            append("ОШИБКА: " + message + "\n");
            status.setText("Ошибка: " + message);
            JOptionPane.showMessageDialog(frame, message, "Plugin Reconstructor Ultra", JOptionPane.ERROR_MESSAGE);
        }

        private final class JarTransferHandler extends TransferHandler {
            private static final long serialVersionUID = 1L;
            @Override
            public boolean canImport(TransferSupport support) {
                return !progressBar.isIndeterminate() && support.isDataFlavorSupported(DataFlavor.javaFileListFlavor);
            }

            @Override
            @SuppressWarnings("unchecked")
            public boolean importData(TransferSupport support) {
                if (!canImport(support)) return false;
                try {
                    Transferable transferable = support.getTransferable();
                    List<File> files = (List<File>) transferable.getTransferData(DataFlavor.javaFileListFlavor);
                    for (File file : files) {
                        if (file.isFile() && file.getName().toLowerCase(Locale.ROOT).endsWith(".jar")) {
                            selectJar(file.toPath());
                            return true;
                        }
                    }
                    showError("Среди перетащенных файлов нет JAR.");
                } catch (Exception error) {
                    showError("Не удалось принять файл: " + safeMessage(error));
                }
                return false;
            }
        }
    }

    private static Path nextAvailableOutput(Path input) {
        String name = input.getFileName().toString();
        int dot = name.toLowerCase(Locale.ROOT).lastIndexOf(".jar");
        String base = dot > 0 ? name.substring(0, dot) : name;
        Path parent = input.getParent() == null ? Path.of(".").toAbsolutePath().normalize() : input.getParent();
        String safe = "Recovered_" + FilesEx.sanitizeFileName(base);
        Path candidate = parent.resolve(safe);
        int suffix = 2;
        while (Files.exists(candidate)) candidate = parent.resolve(safe + "_" + suffix++);
        return candidate.toAbsolutePath().normalize();
    }

    private static Path defaultToolsDirectory() {
        return Path.of(System.getProperty("user.home", "."), ".plugin-reconstructor-ultra", "tools")
                .toAbsolutePath().normalize();
    }

    private static int defaultMemoryMb() {
        long max = Runtime.getRuntime().maxMemory() / (1024L * 1024L);
        if (max <= 0) return 2048;
        return (int) Math.max(1024, Math.min(4096, max / 2));
    }

    private static String shorten(String value, int max) {
        if (value.length() <= max) return value;
        int side = Math.max(10, (max - 3) / 2);
        return value.substring(0, side) + "..." + value.substring(value.length() - side);
    }

    private static String safeMessage(Throwable error) {
        if (error == null) return "неизвестная ошибка";
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }
}
