package dev.zigr.dt.team.ui.storage;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jgit.lib.Repository;

import com._1c.g5.v8.dt.common.FileUtil;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.InfobaseChangesResolutionResult;
import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.RuntimeExecutionException;
import com._1c.g5.v8.dt.team.git.infobases.IGitBranchIssueDescriptor;

final class StoragePullService {

	private StoragePullService() {
	}

	static List<IProject> getConfiguredProjects(IGitBranchIssueDescriptor issueDescriptor, OperationLogger logger) {
		List<IProject> result = new ArrayList<IProject>();
		Repository repository = issueDescriptor.getRepository();
		File workTree = repository.getWorkTree();
		if (workTree == null) {
			logger.detail("У выбранного Git-репозитория не найден рабочий каталог");
			return result;
		}

		Path repositoryRoot = workTree.toPath().toAbsolutePath().normalize();
		logger.detail("Рабочий каталог Git: " + repositoryRoot);
		for (IProject project : org.eclipse.core.resources.ResourcesPlugin.getWorkspace().getRoot().getProjects()) {
			if (!project.isAccessible() || project.getLocation() == null) {
				continue;
			}

			Path projectLocation = project.getLocation().toFile().toPath().toAbsolutePath().normalize();
			if (!projectLocation.startsWith(repositoryRoot)) {
				continue;
			}

			Settings settings = new Settings(project.getName());
			if (settings.getAddress().isBlank()) {
				logger.detail("Проект пропущен, адрес хранилища не заполнен: " + project.getName());
				continue;
			}

			logger.detail("Проект для получения из хранилища: " + project.getName() + ", путь=" + projectLocation);
			result.add(project);
		}

		return result;
	}

	static boolean pullAllProjects(IGitBranchIssueDescriptor issueDescriptor, List<IProject> projects,
			OperationLogger logger, IProgressMonitor monitor) {
		boolean result = true;
		IProgressMonitor actualMonitor = monitor != null ? monitor : new NullProgressMonitor();
		actualMonitor.beginTask("Получение изменений из хранилища", projects.size());
		for (IProject project : projects) {
			logger.step("Обработка проекта " + project.getName());
			actualMonitor.subTask("Проект " + project.getName());
			try {
				pullProject(issueDescriptor, project, logger, actualMonitor);
				StorageUiPlugin.logInfo("Операция получения из хранилища выполнена. Проект=" + project.getName());
			} catch (IOException | CoreException | RuntimeExecutionException | InterruptedException e) {
				if (e instanceof InterruptedException) {
					Thread.currentThread().interrupt();
				}
				logger.error(e.getMessage(), e);
				result = false;
				break;
			} finally {
				actualMonitor.worked(1);
			}
		}
		actualMonitor.done();
		return result;
	}

	private static void pullProject(IGitBranchIssueDescriptor issueDescriptor, IProject project, OperationLogger logger,
			IProgressMonitor monitor)
			throws IOException, CoreException, RuntimeExecutionException, InterruptedException {
		Path rootDirectory = FileUtil.createTempDirectory("ZigrPull").toPath();
		Designer designer = null;
		boolean success = false;
		try {
			logger.detail("Временный каталог: " + rootDirectory);
			designer = new Designer(issueDescriptor, project.getName(), rootDirectory);
			logger.detail("EDT-проект: " + designer.getProject().getName());
			logger.detail("Цель хранилища: " + designer.getStorageTargetDescription());
			if (!designer.getResolvedExtensionName().isEmpty()) {
				logger.detail("Имя расширения EDT: " + designer.getResolvedExtensionName());
			}

			logger.step("Закрытие активной сессии конфигуратора");
			monitor.subTask("Закрытие активной сессии конфигуратора");
			designer.closeDesignerSession();

			logger.step("Получение последней версии из хранилища в ИБ");
			monitor.subTask("Получение последней версии из хранилища");
			List<String> updatedObjects = designer.updateConfigurationFromRepository(logger);
			logger.detail("Объектов, указанных хранилищем как измененные: " + updatedObjects.size());
			if (!updatedObjects.isEmpty()) {
				savePendingObjects(issueDescriptor, project, updatedObjects, logger);
			}
			List<String> expectedObjects = new ArrayList<String>(updatedObjects);
			if (expectedObjects.isEmpty()) {
				logger.step("Поиск незавершенного списка объектов для контроля EDT-импорта");
				expectedObjects = loadPendingObjects(issueDescriptor, project, logger);
			}

			logger.step("Обновление конфигурации базы данных");
			monitor.subTask("Обновление конфигурации базы данных");
			designer.updateDatabaseConfiguration(logger);

			logger.step("Получение изменений из ИБ в EDT штатным механизмом");
			monitor.subTask("Получение изменений из ИБ в EDT");
			InfobaseChangesResolutionResult syncResult = designer.retrieveConfigurationChangesFromInfobase(logger, monitor);
			if (syncResult == InfobaseChangesResolutionResult.NO_CHANGES) {
				logger.detail("EDT API вернул NO_CHANGES; XML fallback отключен, чтобы не менять проект вне штатного импорта EDT");
				if (!expectedObjects.isEmpty()) {
					throw new CoreException(StorageUiPlugin.createErrorStatus(
							"EDT не импортировала ожидаемые изменения из ИБ: штатный API вернул NO_CHANGES, ожидаемых объектов="
									+ expectedObjects.size()));
				}
			}
			clearPendingObjects(issueDescriptor, project, logger);
			success = true;
		} finally {
			if (designer != null) {
				designer.dispose();
			}
			if (success) {
				try {
					FileUtil.deleteRecursivelyWithRetries(rootDirectory);
					logger.detail("Временный каталог удален: " + rootDirectory);
				} catch (IOException e) {
					logger.error(e.getMessage(), e);
				}
			} else {
				logger.detail("Временный каталог сохранен для диагностики: " + rootDirectory);
			}
		}
	}

	private static void savePendingObjects(IGitBranchIssueDescriptor issueDescriptor, IProject project,
			List<String> objects, OperationLogger logger) throws IOException {
		Path pendingFile = getPendingObjectsFile(issueDescriptor, project);
		Files.createDirectories(pendingFile.getParent());
		Files.write(pendingFile, objects, StandardCharsets.UTF_8);
		logger.detail("Список объектов для незавершенного EDT-импорта сохранен: " + pendingFile);
	}

	private static List<String> loadPendingObjects(IGitBranchIssueDescriptor issueDescriptor, IProject project,
			OperationLogger logger) throws IOException {
		Path pendingFile = getPendingObjectsFile(issueDescriptor, project);
		if (!Files.isRegularFile(pendingFile)) {
			logger.detail("Незавершенный список объектов не найден: " + pendingFile);
			return new ArrayList<String>();
		}
		List<String> result = readObjectList(pendingFile);
		logger.detail("Незавершенный список объектов загружен: " + pendingFile + ", объектов=" + result.size());
		return result;
	}

	private static void clearPendingObjects(IGitBranchIssueDescriptor issueDescriptor, IProject project,
			OperationLogger logger) throws IOException {
		Path pendingFile = getPendingObjectsFile(issueDescriptor, project);
		if (Files.deleteIfExists(pendingFile)) {
			logger.detail("Незавершенный список объектов очищен: " + pendingFile);
		}
	}

	private static Path getPendingObjectsFile(IGitBranchIssueDescriptor issueDescriptor, IProject project) {
		Path stateDirectory = StorageUiPlugin.getDefault().getStateLocation().toFile().toPath();
		String key = issueDescriptor.getBranch().getName() + "-" + project.getName();
		return stateDirectory.resolve("pending-pull-" + key.replaceAll("[^A-Za-zА-Яа-я0-9._-]", "_") + ".txt");
	}

	private static List<String> readObjectList(Path path) throws IOException {
		List<String> result = new ArrayList<String>();
		for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
			String objectName = line.trim();
			if (!objectName.isEmpty()) {
				result.add(objectName);
			}
		}
		return result;
	}
}
