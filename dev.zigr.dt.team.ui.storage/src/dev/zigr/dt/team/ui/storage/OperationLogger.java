package dev.zigr.dt.team.ui.storage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.nio.file.StandardOpenOption;

public class OperationLogger {

	private static final DateTimeFormatter FILE_NAME_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
	private static final DateTimeFormatter LINE_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

	private final Path logFile;
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

	public void step(String message) {
		stepNumber++;
		write("STEP " + stepNumber + ". " + message);
		StorageUiPlugin.logInfo(message);
	}

	public void detail(String message) {
		write("     " + message);
	}

	public void commandResult(String title, Path log, int returnCode) {
		detail(title + ": returnCode=" + returnCode + ", log=" + log);
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
