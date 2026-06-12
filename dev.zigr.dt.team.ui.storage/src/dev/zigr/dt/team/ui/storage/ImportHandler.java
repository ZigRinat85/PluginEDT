package dev.zigr.dt.team.ui.storage;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.commands.IHandler;
import org.eclipse.core.commands.IHandlerListener;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.Adapters;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Platform;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.handlers.HandlerUtil;
import org.osgi.framework.Bundle;

import com._1c.g5.v8.dt.common.FileUtil;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.InfobaseChangesResolutionResult;
import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.RuntimeExecutionException;
import com._1c.g5.v8.dt.platform.version.Version;
import com._1c.g5.v8.dt.team.git.infobases.IGitBranchIssueDescriptor;

public class ImportHandler implements IHandler {

	private Shell shell;
	private IGitBranchIssueDescriptor issueDescriptor;

	@Override
	public Object execute(ExecutionEvent event) throws ExecutionException {
		shell = HandlerUtil.getActiveShell(event);
		IStructuredSelection selection = HandlerUtil.getCurrentStructuredSelection(event);
		Object firstElement = selection.getFirstElement();
		issueDescriptor = (IGitBranchIssueDescriptor) Adapters.adapt(firstElement, IGitBranchIssueDescriptor.class);
		if (issueDescriptor == null) {
			MessageDialog.openError(shell, "Получить из хранилища", "Не удалось определить выбранную ветку хранилища");
			return null;
		}

		OperationLogger logger;
		try {
			logger = OperationLogger.create();
		} catch (IOException e) {
			StorageUiPlugin.logError(e.getMessage(), e);
			MessageDialog.openError(shell, "Ошибка", "Не удалось создать журнал операции");
			return null;
		}
		logger.step("Старт операции получения из хранилища");
		logger.detail("ИБ: " + issueDescriptor.getInfobase().getName());
		logger.detail("Ветка хранилища: " + issueDescriptor.getBranch().getName());
		logger.detail("Выбранный элемент: " + firstElement.getClass().getName());

		List<IProject> projects = StoragePullService.getConfiguredProjects(issueDescriptor, logger);
		if (projects.isEmpty()) {
			MessageDialog.openWarning(shell, "Получить из хранилища",
					"Не найдены проекты текущего репозитория с заполненным адресом хранилища. Журнал: " + logger.getLogFile());
			return null;
		}

		OperationLogDialog dialog = new OperationLogDialog(shell, "Получить из хранилища", logger,
				monitor -> StoragePullService.pullAllProjects(issueDescriptor, projects, logger, monitor));
		dialog.open();

		return null;
	}

	private List<IProject> getConfiguredProjects(OperationLogger logger) {
		List<IProject> result = new ArrayList<IProject>();
		Repository repository = issueDescriptor.getRepository();
		File workTree = repository.getWorkTree();
		if (workTree == null) {
			logger.detail("У выбранного Git-репозитория не найден рабочий каталог");
			return result;
		}

		Path repositoryRoot = workTree.toPath().toAbsolutePath().normalize();
		logger.detail("Рабочий каталог Git: " + repositoryRoot);
		for (IProject project : ResourcesPlugin.getWorkspace().getRoot().getProjects()) {
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

	private boolean pullAllProjects(List<IProject> projects, OperationLogger logger, IProgressMonitor monitor) {
		boolean result = true;
		monitor.beginTask("Получение изменений из хранилища", projects.size());
		for (IProject project : projects) {
			logger.step("Обработка проекта " + project.getName());
			monitor.subTask("Проект " + project.getName());
			try {
				pullProject(project, logger, monitor);
				StorageUiPlugin.logInfo("Операция получения из хранилища выполнена. Проект=" + project.getName());
			} catch (IOException | CoreException | RuntimeExecutionException | InterruptedException
					| InvocationTargetException e) {
				logger.error(e.getMessage(), e);
				result = false;
				break;
			} finally {
				monitor.worked(1);
			}
		}
		monitor.done();
		return result;
	}

	private void pullProject(IProject project, OperationLogger logger, IProgressMonitor monitor)
			throws IOException, CoreException, RuntimeExecutionException, InterruptedException, InvocationTargetException {
		Path rootDirectory = FileUtil.createTempDirectory("ZigrPull").toPath();
		Path exportDirectory = null;
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
				savePendingObjects(project, updatedObjects, logger);
			}
			List<String> expectedObjects = new ArrayList<String>(updatedObjects);
			if (expectedObjects.isEmpty()) {
				logger.step("Поиск незавершенного списка объектов для контроля EDT-импорта");
				expectedObjects = loadPendingObjects(project, logger);
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
			clearPendingObjects(project, logger);
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
				if (exportDirectory != null) {
					logger.detail("XML-выгрузка fallback сохранена для диагностики: " + exportDirectory);
				}
			}
		}
	}

	private void savePendingObjects(IProject project, List<String> objects, OperationLogger logger) throws IOException {
		Path pendingFile = getPendingObjectsFile(project);
		Files.createDirectories(pendingFile.getParent());
		Files.write(pendingFile, objects, StandardCharsets.UTF_8);
		logger.detail("Список объектов для незавершенного EDT-импорта сохранен: " + pendingFile);
	}

	private List<String> loadPendingObjects(IProject project, OperationLogger logger) throws IOException {
		Path pendingFile = getPendingObjectsFile(project);
		if (!Files.isRegularFile(pendingFile)) {
			logger.detail("Незавершенный список объектов не найден: " + pendingFile);
			return new ArrayList<String>();
		}
		List<String> result = readObjectList(pendingFile);
		logger.detail("Незавершенный список объектов загружен: " + pendingFile + ", объектов=" + result.size());
		return result;
	}

	private void clearPendingObjects(IProject project, OperationLogger logger) throws IOException {
		Path pendingFile = getPendingObjectsFile(project);
		if (Files.deleteIfExists(pendingFile)) {
			logger.detail("Незавершенный список объектов очищен: " + pendingFile);
		}
	}

	private Path getPendingObjectsFile(IProject project) {
		Path stateDirectory = StorageUiPlugin.getDefault().getStateLocation().toFile().toPath();
		String key = issueDescriptor.getBranch().getName() + "-" + project.getName();
		return stateDirectory.resolve("pending-pull-" + key.replaceAll("[^A-Za-zА-Яа-я0-9._-]", "_") + ".txt");
	}

	private List<String> loadRecentUpdatedObjectsFromLogs(IProject project, OperationLogger logger) throws IOException {
		Path logDirectory = logger.getLogFile().getParent();
		if (logDirectory == null || !Files.isDirectory(logDirectory)) {
			return new ArrayList<String>();
		}

		try (Stream<Path> stream = Files.list(logDirectory)) {
			Iterator<Path> iterator = stream
					.filter(path -> path.getFileName().toString().startsWith("storage-operation-"))
					.filter(path -> path.getFileName().toString().endsWith(".log"))
					.filter(path -> !path.equals(logger.getLogFile()))
					.sorted(Comparator.comparingLong(this::getLastModifiedMillis).reversed()).iterator();
			while (iterator.hasNext()) {
				Path log = iterator.next();
				List<String> objects = readFailedOperationObjects(log, project.getName());
				if (!objects.isEmpty()) {
					logger.detail("Список объектов восстановлен из прошлого неуспешного журнала: " + log);
					for (String objectName : objects) {
						logger.detail("Восстановлен объект для EDT-импорта: " + objectName);
					}
					return objects;
				}
			}
		}
		logger.detail("В прошлых журналах не найден незавершенный список объектов для проекта " + project.getName());
		return new ArrayList<String>();
	}

	private long getLastModifiedMillis(Path path) {
		try {
			return Files.getLastModifiedTime(path).toMillis();
		} catch (IOException e) {
			return 0;
		}
	}

	private List<String> readFailedOperationObjects(Path log, String projectName) throws IOException {
		List<String> result = new ArrayList<String>();
		boolean projectFound = false;
		boolean failed = false;
		for (String line : Files.readAllLines(log, StandardCharsets.UTF_8)) {
			if (line.contains("Обработка проекта " + projectName)
					|| line.contains("Проект для получения из хранилища: " + projectName)) {
				projectFound = true;
			}
			if (line.contains("ERROR") || line.contains("Временный каталог сохранен для диагностики")) {
				failed = true;
			}
			String objectName = extractUpdatedObjectName(line);
			if (objectName != null) {
				result.add(objectName);
			}
		}
		return projectFound && failed ? result : new ArrayList<String>();
	}

	private List<String> readObjectList(Path path) throws IOException {
		List<String> result = new ArrayList<String>();
		for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
			String objectName = line.trim();
			if (!objectName.isEmpty()) {
				result.add(objectName);
			}
		}
		return result;
	}

	private String extractUpdatedObjectName(String line) {
		String objectName = extractAfterMarker(line, "Объект получен из хранилища:");
		if (objectName != null) {
			return objectName;
		}
		return extractAfterMarker(line, "Из хранилища получен объект:");
	}

	private String extractAfterMarker(String line, String marker) {
		String normalizedLine = line.replace("\uFEFF", "");
		int markerIndex = normalizedLine.indexOf(marker);
		if (markerIndex < 0) {
			return null;
		}
		String value = normalizedLine.substring(markerIndex + marker.length()).trim();
		return value.isEmpty() ? null : value;
	}

	private List<String> findChangedBslObjects(Path exportDirectory, IProject project, OperationLogger logger)
			throws IOException {
		Set<String> result = new LinkedHashSet<String>();
		Path projectSourceDirectory = project.getLocation().toFile().toPath().resolve("src");
		logger.detail("Сравнение BSL-файлов с EDT-каталогом: " + projectSourceDirectory);
		try (Stream<Path> stream = Files.walk(exportDirectory)) {
			Iterator<Path> iterator = stream.filter(Files::isRegularFile)
					.filter(path -> path.getFileName().toString().endsWith(".bsl")).iterator();
			while (iterator.hasNext()) {
				Path dumpFile = iterator.next();
				Path relativeDumpFile = exportDirectory.relativize(dumpFile);
				Path relativeProjectFile = toProjectSourcePath(relativeDumpFile);
				if (relativeProjectFile == null) {
					continue;
				}
				Path projectFile = projectSourceDirectory.resolve(relativeProjectFile);
				if (!Files.isRegularFile(projectFile)) {
					continue;
				}
				if (Files.mismatch(dumpFile, projectFile) == -1) {
					continue;
				}
				String objectName = getObjectNameByDumpPath(relativeDumpFile);
				if (objectName != null) {
					result.add(objectName);
					logger.detail("Найдено отличие BSL: объект=" + objectName + ", файл=" + relativeProjectFile);
				}
			}
		}
		logger.detail("Объектов для EDT-импорта по отличающимся BSL-файлам: " + result.size());
		return new ArrayList<String>(result);
	}

	private Path toProjectSourcePath(Path relativeDumpFile) {
		Path result = Path.of("");
		boolean hasSegments = false;
		for (Path segment : relativeDumpFile) {
			if ("Ext".equals(segment.toString())) {
				continue;
			}
			result = result.resolve(segment.toString());
			hasSegments = true;
		}
		return hasSegments ? result : null;
	}

	private String getObjectNameByDumpPath(Path relativeDumpFile) {
		if (relativeDumpFile.getNameCount() == 0) {
			return null;
		}
		String root = relativeDumpFile.getName(0).toString();
		if ("Ext".equals(root)) {
			return "Configuration";
		}
		if (relativeDumpFile.getNameCount() < 2) {
			return null;
		}
		String objectType = getObjectTypeByRootDirectoryName(root);
		if (objectType == null) {
			return null;
		}
		return objectType + "." + relativeDumpFile.getName(1);
	}

	private String getObjectTypeByRootDirectoryName(String rootDirectoryName) {
		return switch (rootDirectoryName) {
		case "Subsystems" -> "Subsystem";
		case "CommonModules" -> "CommonModule";
		case "SessionParameters" -> "SessionParameter";
		case "Roles" -> "Role";
		case "CommonAttributes" -> "CommonAttribute";
		case "ExchangePlans" -> "ExchangePlan";
		case "FilterCriteria" -> "FilterCriterion";
		case "EventSubscriptions" -> "EventSubscription";
		case "ScheduledJobs" -> "ScheduledJob";
		case "FunctionalOptions" -> "FunctionalOption";
		case "FunctionalOptionsParameters" -> "FunctionalOptionsParameter";
		case "DefinedTypes" -> "DefinedType";
		case "SettingsStorages" -> "SettingsStorage";
		case "CommonForms" -> "CommonForm";
		case "CommonCommands" -> "CommonCommand";
		case "CommandGroups" -> "CommandGroup";
		case "CommonTemplates" -> "CommonTemplate";
		case "CommonPictures" -> "CommonPicture";
		case "XDTOPackages" -> "XDTOPackage";
		case "WebServices" -> "WebService";
		case "HTTPServices" -> "HTTPService";
		case "WSReferences" -> "WSReference";
		case "IntegrationServices" -> "IntegrationService";
		case "Bots" -> "Bot";
		case "WebSocketClients" -> "WebSocketClient";
		case "StyleItems" -> "StyleItem";
		case "Styles" -> "Style";
		case "Constants" -> "Constant";
		case "Catalogs" -> "Catalog";
		case "Documents" -> "Document";
		case "DocumentNumerators" -> "DocumentNumerator";
		case "Sequences" -> "Sequence";
		case "DocumentJournals" -> "DocumentJournal";
		case "Enums" -> "Enum";
		case "Reports" -> "Report";
		case "DataProcessors" -> "DataProcessor";
		case "ChartsOfCharacteristicTypes" -> "ChartOfCharacteristicTypes";
		case "ChartsOfAccounts" -> "ChartOfAccounts";
		case "ChartsOfCalculationTypes" -> "ChartOfCalculationTypes";
		case "InformationRegisters" -> "InformationRegister";
		case "AccumulationRegisters" -> "AccumulationRegister";
		case "AccountingRegisters" -> "AccountingRegister";
		case "CalculationRegisters" -> "CalculationRegister";
		case "BusinessProcesses" -> "BusinessProcess";
		case "Tasks" -> "Task";
		case "ExternalDataSources" -> "ExternalDataSource";
		default -> null;
		};
	}

	private void preparePartialImportDirectory(Path exportDirectory, Path importDirectory, List<String> updatedObjects,
			OperationLogger logger) throws IOException, CoreException {
		copyFileIfExists(exportDirectory.resolve("Configuration.xml"), importDirectory.resolve("Configuration.xml"), logger);
		copyFileIfExists(exportDirectory.resolve("ConfigDumpInfo.xml"), importDirectory.resolve("ConfigDumpInfo.xml"), logger);

		List<String> unsupportedObjects = new ArrayList<String>();
		for (String objectName : updatedObjects) {
			if (!copyObjectDump(exportDirectory, importDirectory, objectName, logger)) {
				unsupportedObjects.add(objectName);
			}
		}
		if (!unsupportedObjects.isEmpty()) {
			IStatus status = StorageUiPlugin.createErrorStatus(
					"Не удалось подготовить частичный импорт EDT для объектов: " + String.join(", ", unsupportedObjects));
			throw new CoreException(status);
		}
	}

	private boolean copyObjectDump(Path exportDirectory, Path importDirectory, String objectName, OperationLogger logger)
			throws IOException {
		if ("Configuration".equals(objectName)) {
			copyDirectoryIfExists(exportDirectory.resolve("Ext"), importDirectory.resolve("Ext"), logger);
			return true;
		}

		int delimiter = objectName.indexOf('.');
		if (delimiter <= 0 || delimiter == objectName.length() - 1) {
			logger.detail("Не удалось разобрать имя объекта хранилища: " + objectName);
			return false;
		}

		String objectType = objectName.substring(0, delimiter);
		String objectPath = objectName.substring(delimiter + 1);
		List<Path> relativeObjectPaths = getObjectDumpPaths(objectType, objectPath);
		if (relativeObjectPaths.isEmpty()) {
			logger.detail("Неизвестный тип объекта хранилища: " + objectName);
			return false;
		}

		for (Path relativeObjectPath : relativeObjectPaths) {
			boolean copied = false;
			copied |= copyFileIfExists(exportDirectory.resolve(relativeObjectPath + ".xml"),
					importDirectory.resolve(relativeObjectPath + ".xml"), logger);
			copied |= copyDirectoryIfExists(exportDirectory.resolve(relativeObjectPath), importDirectory.resolve(relativeObjectPath),
					logger);
			if (copied) {
				return true;
			}
			logger.detail("В XML-выгрузке не найден объект хранилища: " + objectName + ", путь=" + relativeObjectPath);
		}
		return false;
	}

	private List<Path> getObjectDumpPaths(String objectType, String objectPath) {
		String rootDirectoryName = getObjectRootDirectoryName(objectType);
		if (rootDirectoryName == null) {
			return new ArrayList<Path>();
		}
		List<Path> result = new ArrayList<Path>();
		Path normalizedPath = Path.of(rootDirectoryName).resolve(normalizeRepositoryObjectPath(objectPath));
		result.add(normalizedPath);

		Path legacyPath = Path.of(rootDirectoryName).resolve(objectPath.replace('.', java.io.File.separatorChar));
		if (!legacyPath.equals(normalizedPath)) {
			result.add(legacyPath);
		}
		return result;
	}

	private Path normalizeRepositoryObjectPath(String objectPath) {
		String[] segments = objectPath.split("\\.");
		Path result = Path.of("");
		for (int i = 0; i < segments.length; i++) {
			String segment = segments[i];
			if (i > 0 && i % 2 == 1) {
				segment = getChildObjectDirectoryName(segment);
			}
			result = result.resolve(segment);
		}
		return result;
	}

	private String getChildObjectDirectoryName(String childObjectType) {
		return switch (childObjectType) {
		case "Attribute" -> "Attributes";
		case "Command" -> "Commands";
		case "Dimension" -> "Dimensions";
		case "Form" -> "Forms";
		case "Resource" -> "Resources";
		case "TabularSection" -> "TabularSections";
		case "Template" -> "Templates";
		default -> childObjectType;
		};
	}

	private String getObjectRootDirectoryName(String objectType) {
		return switch (objectType) {
		case "Subsystem" -> "Subsystems";
		case "CommonModule" -> "CommonModules";
		case "SessionParameter" -> "SessionParameters";
		case "Role" -> "Roles";
		case "CommonAttribute" -> "CommonAttributes";
		case "ExchangePlan" -> "ExchangePlans";
		case "FilterCriterion" -> "FilterCriteria";
		case "EventSubscription" -> "EventSubscriptions";
		case "ScheduledJob" -> "ScheduledJobs";
		case "FunctionalOption" -> "FunctionalOptions";
		case "FunctionalOptionsParameter" -> "FunctionalOptionsParameters";
		case "DefinedType" -> "DefinedTypes";
		case "SettingsStorage" -> "SettingsStorages";
		case "CommonForm" -> "CommonForms";
		case "CommonCommand" -> "CommonCommands";
		case "CommandGroup" -> "CommandGroups";
		case "CommonTemplate" -> "CommonTemplates";
		case "CommonPicture" -> "CommonPictures";
		case "XDTOPackage" -> "XDTOPackages";
		case "WebService" -> "WebServices";
		case "HTTPService" -> "HTTPServices";
		case "WSReference" -> "WSReferences";
		case "IntegrationService" -> "IntegrationServices";
		case "Bot" -> "Bots";
		case "WebSocketClient" -> "WebSocketClients";
		case "StyleItem" -> "StyleItems";
		case "Style" -> "Styles";
		case "Constant" -> "Constants";
		case "Catalog" -> "Catalogs";
		case "Document" -> "Documents";
		case "DocumentNumerator" -> "DocumentNumerators";
		case "Sequence" -> "Sequences";
		case "DocumentJournal" -> "DocumentJournals";
		case "Enum" -> "Enums";
		case "Report" -> "Reports";
		case "DataProcessor" -> "DataProcessors";
		case "ChartOfCharacteristicTypes" -> "ChartsOfCharacteristicTypes";
		case "ChartOfAccounts" -> "ChartsOfAccounts";
		case "ChartOfCalculationTypes" -> "ChartsOfCalculationTypes";
		case "InformationRegister" -> "InformationRegisters";
		case "AccumulationRegister" -> "AccumulationRegisters";
		case "AccountingRegister" -> "AccountingRegisters";
		case "CalculationRegister" -> "CalculationRegisters";
		case "BusinessProcess" -> "BusinessProcesses";
		case "Task" -> "Tasks";
		case "ExternalDataSource" -> "ExternalDataSources";
		default -> null;
		};
	}

	private boolean copyFileIfExists(Path source, Path target, OperationLogger logger) throws IOException {
		if (!Files.isRegularFile(source)) {
			return false;
		}
		Files.createDirectories(target.getParent());
		Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
		logger.detail("Файл добавлен в XML-импорт EDT: " + target);
		return true;
	}

	private boolean copyDirectoryIfExists(Path source, Path target, OperationLogger logger) throws IOException {
		if (!Files.isDirectory(source)) {
			return false;
		}
		try (Stream<Path> stream = Files.walk(source)) {
			Iterator<Path> iterator = stream.iterator();
			while (iterator.hasNext()) {
				Path currentSource = iterator.next();
				Path currentTarget = target.resolve(source.relativize(currentSource));
				if (Files.isDirectory(currentSource)) {
					Files.createDirectories(currentTarget);
				} else {
					Files.createDirectories(currentTarget.getParent());
					Files.copy(currentSource, currentTarget, StandardCopyOption.REPLACE_EXISTING);
				}
			}
		}
		logger.detail("Каталог добавлен в XML-импорт EDT: " + target);
		return true;
	}

	private void logDumpSummary(Path exportDirectory, OperationLogger logger) throws IOException {
		List<Path> sampleFiles = new ArrayList<Path>();
		long[] totalFiles = new long[] { 0 };
		try (Stream<Path> stream = Files.walk(exportDirectory)) {
			stream.filter(Files::isRegularFile).forEach(path -> {
				totalFiles[0]++;
				if (sampleFiles.size() < 80) {
					sampleFiles.add(path);
				}
			});
		}

		logger.detail("XML-выгрузка: файлов=" + totalFiles[0] + ", каталог=" + exportDirectory);
		for (Path path : sampleFiles) {
			logger.detail("XML-выгрузка / файл: " + exportDirectory.relativize(path));
		}
		if (totalFiles[0] > sampleFiles.size()) {
			logger.detail("XML-выгрузка / файл: ... еще " + (totalFiles[0] - sampleFiles.size()));
		}
	}

	private void importXmlToProject(IProject project, Version version, Path exportDirectory, OperationLogger logger)
			throws InvocationTargetException, InterruptedException, CoreException {
		try {
			Object importServiceRegistry = createImportServiceRegistry();
			Method getImportService = importServiceRegistry.getClass().getMethod("getImportService", Version.class);
			Object importService = getImportService.invoke(importServiceRegistry, version);
			logger.detail("Сервис импорта EDT: " + importService.getClass().getName());
			Method work = importService.getClass().getMethod("work", IProject.class, Path.class, IProgressMonitor.class);
			IStatus status = (IStatus) work.invoke(importService, project, exportDirectory, new NullProgressMonitor());
			logStatus("Импорт XML в EDT", status, logger);
			if (status != null && status.matches(IStatus.ERROR | IStatus.CANCEL)) {
				throw new CoreException(status);
			}
			project.refreshLocal(IResource.DEPTH_INFINITE, new NullProgressMonitor());
		} catch (InvocationTargetException e) {
			Throwable cause = e.getCause() != null ? e.getCause() : e;
			if (cause instanceof CoreException coreException) {
				throw coreException;
			}
			IStatus status = StorageUiPlugin.createErrorStatus("Ошибка импорта XML EDT", cause);
			throw new CoreException(status);
		} catch (Exception e) {
			IStatus status = StorageUiPlugin.createErrorStatus("Не удалось выполнить импорт XML EDT", e);
			throw new CoreException(status);
		}
	}

	private Object createImportServiceRegistry() throws CoreException {
		try {
			Bundle importBundle = Platform.getBundle("com._1c.g5.v8.dt.import");
			if (importBundle == null) {
				throw new IllegalStateException("Bundle com._1c.g5.v8.dt.import is not found");
			}
			Class<?> registryClass = importBundle.loadClass("com._1c.g5.v8.dt.internal.import_.ImportServiceRegistry");
			return registryClass.getConstructor().newInstance();
		} catch (Exception e) {
			IStatus status = StorageUiPlugin.createErrorStatus("Не удалось создать сервис импорта XML EDT", e);
			throw new CoreException(status);
		}
	}

	private void logStatus(String title, IStatus status, OperationLogger logger) {
		if (status == null) {
			logger.detail(title + ": статус не возвращен");
			return;
		}
		logger.detail(title + ": severity=" + status.getSeverity() + ", message=" + status.getMessage());
		if (status.getSeverity() == IStatus.ERROR && status.getException() != null) {
			logger.error(title + ": " + status.getMessage(), status.getException());
		}
		for (IStatus child : status.getChildren()) {
			logStatus(title + " / child", child, logger);
		}
	}

	@Override
	public boolean isEnabled() {
		return true;
	}

	@Override
	public boolean isHandled() {
		return true;
	}

	@Override
	public void addHandlerListener(IHandlerListener handlerListener) {

	}

	@Override
	public void dispose() {

	}

	@Override
	public void removeHandlerListener(IHandlerListener handlerListener) {

	}

}
