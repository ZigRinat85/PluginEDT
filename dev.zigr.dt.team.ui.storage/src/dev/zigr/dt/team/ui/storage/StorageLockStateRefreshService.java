package dev.zigr.dt.team.ui.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.regex.Pattern;

import org.eclipse.core.resources.IProject;
import org.eclipse.xtext.naming.QualifiedName;

final class StorageLockStateRefreshService {

	private static final Pattern URI_SCHEME = Pattern.compile("^[a-zA-Z][a-zA-Z0-9+.-]*://.*");

	private StorageLockStateRefreshService() {
	}

	static RefreshResult refresh(IProject project, Collection<QualifiedName> selectedObjects, OperationLogger logger) {
		StorageLockStateStore store = StorageLockStateStore.getInstance();
		logger.step("Перечитывание локального состояния захвата");
		store.reload();
		int projectLockedCount = store.countLocked(project.getName());
		int selectedLockedCount = store.countLocked(project.getName(), selectedObjects);
		logger.detail("Файл локального состояния: " + store.stateFile());
		logger.detail("Локально известных захватов в проекте: " + projectLockedCount);
		logger.detail("Локально известных захватов среди выбранных объектов: "
				+ selectedLockedCount + " из " + selectedObjects.size());

		logger.step("Проверка источника внешнего состояния захвата");
		String externalStateMessage = inspectExternalStateSource(project, logger);
		return new RefreshResult(projectLockedCount, selectedLockedCount, selectedObjects.size(), externalStateMessage);
	}

	private static String inspectExternalStateSource(IProject project, OperationLogger logger) {
		Settings settings = new Settings(project.getName());
		String address = settings.getAddress();
		if (address == null || address.isBlank()) {
			String message = "адрес хранилища не заполнен";
			logger.detail("Внешнее состояние не обновлено: " + message);
			return message;
		}
		logger.detail("Адрес хранилища: " + address);
		if (URI_SCHEME.matcher(address).matches()) {
			String message = "для удаленного хранилища нет безопасной batch-команды получения текущих захватов";
			logger.detail("Внешнее состояние не обновлено: " + message);
			return message;
		}
		try {
			return inspectLocalRepository(Path.of(address), logger);
		} catch (InvalidPathException e) {
			String message = "адрес хранилища не является локальным путем";
			logger.detail("Внешнее состояние не обновлено: " + message);
			return message;
		}
	}

	private static String inspectLocalRepository(Path repositoryPath, OperationLogger logger) {
		if (!Files.exists(repositoryPath)) {
			String message = "локальный путь хранилища не найден";
			logger.detail("Внешнее состояние не обновлено: " + message);
			return message;
		}
		if (!Files.isDirectory(repositoryPath)) {
			String message = "адрес хранилища не указывает на каталог";
			logger.detail("Внешнее состояние не обновлено: " + message);
			return message;
		}
		Path repositoryDatabase = repositoryPath.resolve("1cv8ddb.1CD");
		if (!Files.isRegularFile(repositoryDatabase)) {
			String message = "каталог найден, но файл 1cv8ddb.1CD не обнаружен";
			logger.detail("Внешнее состояние не обновлено: " + message);
			return message;
		}
		logger.detail("Найдена внутренняя база файлового хранилища: " + repositoryDatabase);
		try {
			logger.detail("Размер 1cv8ddb.1CD: " + Files.size(repositoryDatabase) + " байт");
		} catch (IOException e) {
			logger.detail("Не удалось получить размер 1cv8ddb.1CD: " + e.getMessage());
		}
		String message = "файловое хранилище найдено, но текущие захваты хранятся внутри 1cv8ddb.1CD;"
				+ " безопасный публичный считыватель пока не подключен";
		logger.detail("Внешнее состояние не обновлено: " + message);
		return message;
	}

	record RefreshResult(int projectLockedCount, int selectedLockedCount, int selectedCount,
			String externalStateMessage) {

		String dialogMessage() {
			return "Локальное состояние захвата перечитано."
					+ System.lineSeparator()
					+ "Локально известных захватов в проекте: " + projectLockedCount
					+ System.lineSeparator()
					+ "Среди выбранных объектов: " + selectedLockedCount + " из " + selectedCount
					+ System.lineSeparator()
					+ System.lineSeparator()
					+ "Внешние захваты не обновлены: " + externalStateMessage + ".";
		}
	}
}
