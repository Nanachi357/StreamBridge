package dev.streambridge.spike;

import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.Properties;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Small Windows development launcher, intentionally separate from the product UI. */
public final class LocalUiLauncher {
    private final JFrame window = new JFrame("StreamBridge — запуск");
    private final JComboBox<String> browser = new JComboBox<>(new String[]{"За замовчуванням", "Google Chrome", "Microsoft Edge", "Інший .exe…"});
    private final JLabel message = new JLabel("Перевіряємо Java, FFmpeg, FFprobe та Chromium…");
    private final JButton open = new JButton("Відкрити UI"), copy = new JButton("Скопіювати посилання"), stop = new JButton("Завершити StreamBridge");
    private final CountDownLatch finished = new CountDownLatch(1);
    private final AtomicBoolean closing = new AtomicBoolean();
    private final Path preferences;
    private final LauncherSupport.Instance instance;
    private volatile LocalUiSpike app;
    private volatile LocalUiSession session;
    private volatile String launchUrl;
    private String custom = "";

    private LocalUiLauncher(Path directory, LauncherSupport.Instance instance) {
        this.instance = instance; preferences = directory.resolve("browser.properties");
    }
    public static void main(String[] args) throws Exception {
        if (args.length == 1 && args[0].startsWith("--self-check=")) {
            System.exit(PackagedSelfCheck.run(Path.of(args[0].substring("--self-check=".length()))));
            return;
        }
        try { run(args); }
        catch (Exception failure) {
            if (args.length > 0) throw failure; // Diagnostic CLI paths stay non-interactive.
            JOptionPane.showMessageDialog(null, failure.getMessage(), "StreamBridge — помилка запуску", JOptionPane.ERROR_MESSAGE);
        }
    }
    private static void run(String[] args) throws Exception {
        if (!System.getProperty("os.name").startsWith("Windows")) throw new IllegalStateException("Windows pilot only");
        if (args.length == 1 && args[0].equals("--check-only")) {
            LauncherSupport.probeDependencies(); System.out.println("Startup dependencies: OK"); return;
        }
        if (args.length != 0) throw new IllegalArgumentException("Unknown launcher option");
        Path directory = Path.of(System.getenv("LOCALAPPDATA"), "StreamBridge", "pilot-launcher");
        try (var instance = new LauncherSupport.Instance(directory)) {
            var launcher = new LocalUiLauncher(directory, instance);
            var hook = new Thread(launcher::shutdownResources, "streambridge-launcher-shutdown");
            Runtime.getRuntime().addShutdownHook(hook);
            try {
                SwingUtilities.invokeAndWait(launcher::show);
                launcher.initialize();
                launcher.finished.await();
            } finally { launcher.shutdownResources(); Runtime.getRuntime().removeShutdownHook(hook); }
        }
    }
    private void show() {
        window.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        window.addWindowListener(new WindowAdapter() { @Override public void windowClosing(WindowEvent e) { requestStop(); } });
        var panel = new JPanel(new GridLayout(0, 1, 8, 12)); panel.setBorder(BorderFactory.createEmptyBorder(20,20,20,20));
        panel.add(new JLabel("Браузер для UI (не Chromium для пошуку відео):")); panel.add(browser);
        panel.add(message); panel.add(open); panel.add(copy); panel.add(stop);
        panel.add(new JLabel("Закриття вкладки UI не зупиняє завантаження."));
        window.setContentPane(panel); window.setSize(770,340); window.setLocationByPlatform(true);
        open.setEnabled(false); copy.setEnabled(false); stop.setEnabled(false);
        loadPreference();
        browser.addActionListener(e -> {
            if (browser.getSelectedIndex() == 3) {
                var chooser = new JFileChooser(); chooser.setDialogTitle("Вибери виконуваний файл браузера (.exe)");
                if (chooser.showOpenDialog(window) == JFileChooser.APPROVE_OPTION) custom = chooser.getSelectedFile().getAbsolutePath();
                else if (custom.isEmpty()) browser.setSelectedIndex(0);
            }
        });
        open.addActionListener(e -> openBrowser());
        copy.addActionListener(e -> {
            try { Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(launchUrl), null); message.setText("Посилання скопійовано. Воно містить приватний ключ — не передавай його іншим."); }
            catch (Exception failure) { showFallback(); }
        });
        stop.addActionListener(e -> requestStop()); window.setVisible(true);
    }
    private void initialize() {
        try {
            if (instance.owner()) {
                LauncherSupport.probeDependencies();
                session = LocalUiSession.forUser(null);
                app = new LocalUiSpike(session); launchUrl = app.launchUrl();
                if (!LauncherSupport.ready(launchUrl)) throw new IllegalStateException("Backend не підтвердив готовність.");
                instance.publish(launchUrl);
            } else launchUrl = instance.awaitExisting(Duration.ofSeconds(70));
            SwingUtilities.invokeLater(() -> {
                open.setEnabled(true); copy.setEnabled(true); stop.setEnabled(true);
                if (!instance.owner()) stop.setText("Закрити це вікно запуску");
                message.setText(instance.owner() ? "Сервіс готовий. Обери браузер або відкрий UI." : "Використовуємо вже запущений сервіс; нового екземпляра немає.");
                openBrowser();
            });
        } catch (Exception failure) {
            SwingUtilities.invokeLater(() -> {
                message.setText("Запуск не завершено. Закрий це вікно після перегляду помилки.");
                stop.setEnabled(true); stop.setText("Закрити");
                JOptionPane.showMessageDialog(window, failure.getMessage(), "Не вдалося запустити StreamBridge", JOptionPane.ERROR_MESSAGE);
            });
        }
    }
    private String selection() { return switch (browser.getSelectedIndex()) { case 1 -> "chrome"; case 2 -> "edge"; case 3 -> custom; default -> "default"; }; }
    private void openBrowser() {
        String selection = selection(); open.setEnabled(false);
        Thread.startVirtualThread(() -> {
            try {
                LauncherSupport.open(selection, launchUrl);
                var saved = new Properties(); saved.setProperty("browser", selection);
                try (var output = Files.newOutputStream(preferences)) { saved.store(output, "UI browser only; no session key"); }
                SwingUtilities.invokeLater(() -> message.setText("Посилання передано браузеру. Якщо UI не відкрився — скопіюй посилання."));
            } catch (Exception failure) {
                SwingUtilities.invokeLater(() -> { message.setText("Не вдалося відкрити браузер. Вибери інший або скопіюй посилання."); JOptionPane.showMessageDialog(window, failure.getMessage(), "Браузер недоступний", JOptionPane.WARNING_MESSAGE); });
            } finally { SwingUtilities.invokeLater(() -> open.setEnabled(!closing.get())); }
        });
    }
    private void loadPreference() {
        try {
            var saved = new Properties(); try (var input = Files.newInputStream(preferences)) { saved.load(input); }
            String value = saved.getProperty("browser", "default");
            browser.setSelectedIndex(switch(value) { case "default" -> 0; case "chrome" -> 1; case "edge" -> 2; default -> { custom = value; yield 3; } });
        } catch (Exception absent) { /* First launch uses the system browser. */ }
    }
    private void showFallback() {
        var text = new JTextField(launchUrl); text.setEditable(false);
        JOptionPane.showMessageDialog(window, text, "Приватне стартове посилання", JOptionPane.INFORMATION_MESSAGE);
    }
    private void requestStop() {
        if (!stop.isEnabled() || closing.get()) return;
        if (session != null && Boolean.TRUE.equals(session.snapshot().get("workerAlive"))
                && JOptionPane.showConfirmDialog(window, "Є активна операція. Скасувати її та завершити StreamBridge?", "Завершення", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;
        if (!closing.compareAndSet(false,true)) return;
        open.setEnabled(false); copy.setEnabled(false); stop.setEnabled(false); browser.setEnabled(false);
        message.setText("Завершуємо сервіс і належні йому процеси…");
        Thread.startVirtualThread(() -> {
            shutdownResources();
            SwingUtilities.invokeLater(() -> { window.dispose(); finished.countDown(); });
        });
    }
    private synchronized void shutdownResources() {
        if (app != null) { app.close(); app = null; }
    }
}
