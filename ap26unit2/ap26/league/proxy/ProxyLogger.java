package ap26.league.proxy;

import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.time.format.*;

public class ProxyLogger {
    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private final String component;
    private final Path logFile;
    private final PrintWriter logWriter;

    public ProxyLogger(String component) {
        this.component = component;
        this.logFile = createLogFile(component);
        this.logWriter = createLogWriter();
    }

    private Path createLogFile(String component) {
        try {
            Path logDir = Paths.get("/tmp", "logs", "proxy");
            if (!Files.exists(logDir)) {
                Files.createDirectories(logDir);
            }
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            return logDir.resolve(component + "_" + timestamp + ".log");
        } catch (IOException e) {
            throw new RuntimeException("Failed to create log directory", e);
        }
    }

    private PrintWriter createLogWriter() {
        try {
            return new PrintWriter(new FileWriter(logFile.toFile(), true));
        } catch (IOException e) {
            throw new RuntimeException("Failed to create log writer", e);
        }
    }

    public void debug(String message) {
        writeLog("DEBUG", message);
    }

    public void info(String message) {
        writeLog("INFO", message);
    }

    public void warn(String message) {
        writeLog("WARN", message);
        // Warning messages also go to stderr
        System.err.println("[WARN] " + component + ": " + message);
    }

    public void error(String message) {
        writeLog("ERROR", message);
        // Error messages also go to stderr
        System.err.println("[ERROR] " + component + ": " + message);
    }

    public void error(String message, Throwable throwable) {
        writeLog("ERROR", message + " - " + throwable.getMessage());
        if (logWriter != null) {
            throwable.printStackTrace(logWriter);
            logWriter.flush();
        }
        // Error messages also go to stderr
        System.err.println("[ERROR] " + component + ": " + message + " - " + throwable.getMessage());
    }

    public void gameResult(String message) {
        writeLog("GAME", message);
        // Game results go to stdout
        System.out.println("[GAME] " + message);
    }

    private void writeLog(String level, String message) {
        if (logWriter != null) {
            String timestamp = LocalDateTime.now().format(TIMESTAMP_FORMAT);
            logWriter.println(String.format("[%s] %s [%s] %s: %s",
                    timestamp, level, component, Thread.currentThread().getName(), message));
            logWriter.flush();
        }
    }

    public void close() {
        if (logWriter != null) {
            logWriter.close();
        }
    }

    public Path getLogFile() {
        return logFile;
    }
}
