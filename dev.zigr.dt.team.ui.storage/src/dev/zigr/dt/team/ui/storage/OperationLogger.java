package dev.zigr.dt.team.ui.storage;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.nio.file.StandardOpenOption;

public class OperationLogger {

	public interface Listener {
		void lineWritten(String line);
	}

	private static final DateTimeFormatter FILE_NAME_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
	private static final DateTimeFormatter LINE_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

	private final Path logFile;
	private final List<Listener> listeners = new CopyOnWriteArrayList<Listener>();
	private int stepNumber;

	public static OperationLogger create() throws IOException {
		Path logDirectory;
		if (StorageUiPlugin.getDefault() != null) {
			logDirectory = StorageUiPlugin.getDefault().getStateLocation().toFile().toPath().resolve("operations");
		} else {
			logDirectory = Files.createTempDirectory("PluginEDT-storage-logs");
		}
		Files.createDirectories(logDirectory);
		Path logFile = logDirectory.resolve("storage-operation-" + LocalDateTime.now().format(FILE_NAME_FORMAT) + ".log");
		OperationLogger logger = new OperationLogger(logFile);
		logger.detail("Журнал операции: " + logFile);
		return logger;
	}

	private OperationLogger(Path logFile) {
		this.logFile = logFile;
	}

	public Path getLogFile() {
		return logFile;
	}

	public void addListener(Listener listener) {
		listeners.add(listener);
	}

	public void removeListener(Listener listener) {
		listeners.remove(listener);
	}

	public void step(String message) {
		stepNumber++;
		write("STEP " + stepNumber + ". " + message);
		StorageUiPlugin.logInfo(message);
	}

	public void detail(String message) {
		write("     " + message);
	}

	public void commandResult(String title, Path log, int returnCode) {
		commandResult(title, log, returnCode, true);
	}

	public void commandResult(String title, Path log, int returnCode, boolean includeOutput) {
		detail(title + ": returnCode=" + returnCode + ", log=" + log);
		if (!includeOutput) {
			return;
		}
		String output = readText(log);
		if (!output.isBlank()) {
			detail(title + " output:");
			for (String line : output.split("\\R")) {
				detail("  " + line);
			}
		}
	}

	public void error(String message, Throwable throwable) {
		write("ERROR " + message);
		if (throwable != null) {
			StringWriter stackTrace = new StringWriter();
			throwable.printStackTrace(new PrintWriter(stackTrace));
			for (String line : stackTrace.toString().split("\\R")) {
				write("ERROR   " + line);
			}
		}
		StorageUiPlugin.logError(message, throwable);
	}

	private void write(String message) {
		String line = LocalDateTime.now().format(LINE_FORMAT) + " " + message + System.lineSeparator();
		try {
			Files.writeString(logFile, line, StandardCharsets.UTF_8,
					StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
		} catch (IOException e) {
			StorageUiPlugin.logError(e.getMessage(), e);
		}
		for (Listener listener : listeners) {
			try {
				listener.lineWritten(line);
			} catch (RuntimeException e) {
				StorageUiPlugin.logError(e.getMessage(), e);
			}
		}
	}

	private String readText(Path path) {
		try {
			if (Files.exists(path)) {
				return Files.readString(path);
			}
		} catch (IOException e) {
			return "Не удалось прочитать файл " + path + ": " + e.getMessage();
		}
		return "";
	}
}
