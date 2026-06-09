package dev.zigr.dt.team.ui.storage;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.xtext.naming.QualifiedName;

import com._1c.g5.v8.dt.core.platform.IExtensionProject;
import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAccessManager;
import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAccessSettings;
import com._1c.g5.v8.dt.platform.services.core.infobases.InfobaseAccessType;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.IInfobaseChangesResolver;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.IInfobaseConfigurationChange;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.IInfobaseSynchronizationManager;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.IInfobaseUpdateConflictResolver;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.IInfobaseUpdateConflictResolver.IConflictResolveAssist;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.InfobaseChangesResolutionResult;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.InfobaseConflictResolution;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.InfobaseConflictResolutionResult;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.InfobaseSyncResolution;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.InfobaseSynchronizationException;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.ObjectChange;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.ObjectChangeType;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.v2.IInfobaseSynchronizationFlow;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.v2.IInfobaseSynchronizationStateManager;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.v2.IUpdateProjectFlow;
import com._1c.g5.v8.dt.platform.services.core.runtimes.RuntimeInstallations;
import com._1c.g5.v8.dt.platform.services.core.runtimes.environments.IResolvableRuntimeInstallation;
import com._1c.g5.v8.dt.platform.services.core.runtimes.environments.IResolvableRuntimeInstallationManager;
import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.ComponentExecutorInfo;
import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.IDesignerSessionThickClientLauncher;
import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.ILaunchableRuntimeComponent;
import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.IRuntimeComponentManager;
import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.IRuntimeComponentTypes;
import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.RuntimeExecutionException;
import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.impl.RuntimeExecutionCommandBuilder;
import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import com._1c.g5.v8.dt.platform.services.model.RuntimeInstallation;
import com._1c.g5.v8.dt.platform.version.Version;
import com._1c.g5.v8.dt.team.git.infobases.IGitBranchIssueDescriptor;
import com._1c.g5.wiring.ServiceAccess;
import com._1c.g5.wiring.ServiceSupplier;

public class Designer {
	
	private ServiceSupplier<IInfobaseAccessManager> infobaseAccessManagerSupplier = 
			ServiceAccess.supplier(IInfobaseAccessManager.class, StorageUiPlugin.getDefault());	
	private ServiceSupplier<IRuntimeComponentManager> runtimeComponentManagerSupplier = 
			ServiceAccess.supplier(IRuntimeComponentManager.class, StorageUiPlugin.getDefault());	
	private ServiceSupplier<IV8ProjectManager> v8ProjectManagerSupplier = 
			ServiceAccess.supplier(IV8ProjectManager.class, StorageUiPlugin.getDefault());	
	private ServiceSupplier<IResolvableRuntimeInstallationManager> resolvableRuntimeInstallationManagerSupplier = 
			ServiceAccess.supplier(IResolvableRuntimeInstallationManager.class, StorageUiPlugin.getDefault());	
	private ServiceSupplier<IInfobaseSynchronizationManager> infobaseSynchronizationManagerSupplier = 
			ServiceAccess.supplier(IInfobaseSynchronizationManager.class, StorageUiPlugin.getDefault());	
	private ServiceSupplier<IInfobaseSynchronizationStateManager> infobaseSynchronizationStateManagerSupplier = 
			ServiceAccess.supplier(IInfobaseSynchronizationStateManager.class, StorageUiPlugin.getDefault());	
	
	private IGitBranchIssueDescriptor issueDescriptor;
	private IProject project;
	private Path rootDirectory;
	private Version version;
	private ComponentExecutorInfo<ILaunchableRuntimeComponent, IDesignerSessionThickClientLauncher> thickClient;
	private String extensionName;
	
	public Designer(IGitBranchIssueDescriptor issueDescriptor, String projectName, Path rootDirectory) throws CoreException, IOException, InterruptedException, RuntimeExecutionException {
		this.issueDescriptor = issueDescriptor;
		this.project = getV8ProjectManager().getProject(projectName).getProject();
		this.rootDirectory = rootDirectory;
		
		InfobaseReference infobase = issueDescriptor.getInfobase();
		IResolvableRuntimeInstallation actualInstallation = getResolvableRuntimeInstallationManager().resolveByProjectAndInfobase(
				RuntimeInstallations.ENTERPRISE_PLATFORM, project, infobase, InfobaseAccessType.UPDATE);
		RuntimeInstallation installation = actualInstallation.resolve(List.of(IRuntimeComponentTypes.THICK_CLIENT), infobase.getAppArch());
		version = installation.getVersion();
		thickClient = getRuntimeComponentManager().resolveExecutor(
				ILaunchableRuntimeComponent.class, IDesignerSessionThickClientLauncher.class, installation, IRuntimeComponentTypes.THICK_CLIENT);
		
		extensionName = getExtensionName();
	}
	
	private IInfobaseAccessManager getInfobaseAccessManager() {
		return infobaseAccessManagerSupplier.get();
	}
	
	private IRuntimeComponentManager getRuntimeComponentManager() {
		return runtimeComponentManagerSupplier.get();
	}
	
	private IV8ProjectManager getV8ProjectManager() {
		return v8ProjectManagerSupplier.get();
	}
	
	private IResolvableRuntimeInstallationManager getResolvableRuntimeInstallationManager() {
		return resolvableRuntimeInstallationManagerSupplier.get();
	}
	
	private IInfobaseSynchronizationStateManager getInfobaseSynchronizationStateManager() {
		return infobaseSynchronizationStateManagerSupplier.get();
	}

	private IInfobaseSynchronizationManager getInfobaseSynchronizationManager() {
		return infobaseSynchronizationManagerSupplier.get();
	}
	
	public void dispose() {
		infobaseAccessManagerSupplier.close();
		runtimeComponentManagerSupplier.close();
		v8ProjectManagerSupplier.close();
		resolvableRuntimeInstallationManagerSupplier.close();
		infobaseSynchronizationManagerSupplier.close();
		infobaseSynchronizationStateManagerSupplier.close();
	}
	
	public Version getVersion() {
		return version;
	}

	public IProject getProject() {
		return project;
	}

	public String getStorageTargetDescription() {
		if (extensionName == null || extensionName.isEmpty()) {
			return "основная конфигурация";
		}
		return "расширение " + extensionName;
	}

	public String getResolvedExtensionName() {
		return extensionName;
	}
	
	public void closeDesignerSession() throws RuntimeExecutionException {
		thickClient.getExecutor().closeDesignerSession(thickClient.getComponent(), issueDescriptor.getInfobase(), null);
	}

	private RuntimeExecutionCommandBuilder getCommandBuilder(Path log) throws CoreException {
		InfobaseReference infobase = issueDescriptor.getInfobase();
		IInfobaseAccessSettings settings = getInfobaseAccessManager().resolveSettings(infobase);
		File launchFile = thickClient.getComponent().getFile();
		
		RuntimeExecutionCommandBuilder result = new RuntimeExecutionCommandBuilder(launchFile, RuntimeExecutionCommandBuilder.ThickClientMode.DESIGNER)
				.forInfobase(infobase, false).userName(settings.userName()).userPassword(settings.password())
				.disableStartupDialogs().interfaceLanguage("ru").logTo(log.toFile(), true);
		
		return result;
	}

	public void loadConfigurationFromXml(Path sourceFolder, Path fileList, OperationLogger logger) throws CoreException, IOException, InterruptedException, RuntimeExecutionException {
		Path log = rootDirectory.resolve("loadCfgOut.txt");
		
		RuntimeExecutionCommandBuilder command = getCommandBuilder(log)
			.importXmlToInfobase(sourceFolder).fileList(fileList).updateConfigDumpInfo();
		
		if (!extensionName.isEmpty()) {
			command.forExtension(extensionName);
		}
		command.additionalParameters(getRepositoryConnectionParameters());
		logCommandContext(logger, "Загрузка XML в конфигурацию", getRepositoryConnectionParameters());
		
		int returnCode = runCommand("Загрузка XML в конфигурацию", command, log, logger);
		if (returnCode != 0) {
			IStatus status = StorageUiPlugin.createErrorStatus(Files.readString(log));
			throw new CoreException(status);
		}
	}

	public void commitObjects(Path lockObjectsList, String comment, OperationLogger logger)
			throws CoreException, IOException, InterruptedException {
		Path log = rootDirectory.resolve("commitObjectsOut.txt");
		RuntimeExecutionCommandBuilder command = getCommandBuilder(log);
		String additionalStartupParameters = getRepositoryConnectionParameters()
			+ " /ConfigurationRepositoryCommit -Objects " + quoteParameter(lockObjectsList.toString())
			+ " -comment " + quoteParameter(comment)
			+ " -force";
		if (!extensionName.isEmpty()) {
			additionalStartupParameters = additionalStartupParameters + " -Extension " + quoteParameter(extensionName);
		}
		command.additionalParameters(additionalStartupParameters);
		logCommandContext(logger, "Помещение изменений в хранилище", additionalStartupParameters);

		int returnCode = runCommand("Помещение изменений в хранилище", command, log, logger);
		if (returnCode != 0) {
			IStatus status = StorageUiPlugin.createErrorStatus(Files.readString(log));
			throw new CoreException(status);
		}
	}

	public List<String> updateConfigurationFromRepository(OperationLogger logger)
			throws CoreException, IOException, InterruptedException {
		Path log = rootDirectory.resolve("updateCfgOut.txt");
		RuntimeExecutionCommandBuilder command = getCommandBuilder(log);
		String additionalStartupParameters = getRepositoryConnectionParameters()
			+ " /ConfigurationRepositoryUpdateCfg -revised -force";
		if (!extensionName.isEmpty()) {
			additionalStartupParameters = additionalStartupParameters + " -Extension " + quoteParameter(extensionName);
		}
		command.additionalParameters(additionalStartupParameters);
		logCommandContext(logger, "Получение конфигурации из хранилища", additionalStartupParameters);

		int returnCode = runCommand("Получение конфигурации из хранилища", command, log, logger);
		if (returnCode != 0) {
			IStatus status = StorageUiPlugin.createErrorStatus(Files.readString(log));
			throw new CoreException(status);
		}
		List<String> updatedObjects = readUpdatedRepositoryObjects(log);
		logger.detail("Из хранилища получено объектов: " + updatedObjects.size());
		for (String objectName : updatedObjects) {
			logger.detail("Из хранилища получен объект: " + objectName);
		}
		return updatedObjects;
	}

	public void updateDatabaseConfiguration(OperationLogger logger)
			throws CoreException, IOException, InterruptedException {
		Path log = rootDirectory.resolve("updateDbCfgOut.txt");
		RuntimeExecutionCommandBuilder command = getCommandBuilder(log);
		String additionalStartupParameters = "/UpdateDBCfg -Dynamic+";
		if (!extensionName.isEmpty()) {
			additionalStartupParameters = additionalStartupParameters + " -Extension " + quoteParameter(extensionName);
		}
		command.additionalParameters(additionalStartupParameters);
		logCommandContext(logger, "Обновление конфигурации базы данных", additionalStartupParameters);

		int returnCode = runCommand("Обновление конфигурации базы данных", command, log, logger);
		if (returnCode != 0) {
			IStatus status = StorageUiPlugin.createErrorStatus(Files.readString(log));
			throw new CoreException(status);
		}
	}

	private List<String> readUpdatedRepositoryObjects(Path log) throws IOException {
		List<String> result = new ArrayList<String>();
		String marker = "Объект получен из хранилища:";
		for (String rawLine : Files.readString(log).split("\\R")) {
			String line = rawLine.replace("\uFEFF", "").trim();
			int markerIndex = line.indexOf(marker);
			if (markerIndex >= 0) {
				result.add(line.substring(markerIndex + marker.length()).trim());
			}
		}
		return result;
	}

	public void dumpConfigurationToXml(Path exportDirectory, OperationLogger logger)
			throws CoreException, IOException, InterruptedException, RuntimeExecutionException {
		Path log = rootDirectory.resolve("dumpCfgOut.txt");
		RuntimeExecutionCommandBuilder command = getCommandBuilder(log)
			.exportXmlFromInfobase(exportDirectory);
		if (!extensionName.isEmpty()) {
			command.forExtension(extensionName);
		}
		String exportParameters = "ExportXmlFromInfobase " + quoteParameter(exportDirectory.toString());
		if (!extensionName.isEmpty()) {
			exportParameters = exportParameters + " -Extension " + quoteParameter(extensionName);
		}
		logCommandContext(logger, "Выгрузка конфигурации в XML", exportParameters);

		int returnCode = runCommand("Выгрузка конфигурации в XML", command, log, logger);
		if (returnCode != 0) {
			IStatus status = StorageUiPlugin.createErrorStatus(Files.readString(log));
			throw new CoreException(status);
		}
	}

	public void updateProjectSynchronizationState(Path sourceFolder, OperationLogger logger) throws CoreException {
		// актуализация ConfigDumpInfo.xml в ветке хранилища
		IUpdateProjectFlow updateProjectFlow = null;
		try {
			updateProjectFlow = getInfobaseSynchronizationStateManager().startUpdateProjectFlow(
					getV8ProjectManager().getProject(project).getDtProject(), issueDescriptor.getInfobase());
			updateActualConfigDumpInfo(updateProjectFlow, sourceFolder); // передаем именно каталог, где лежит файл ConfigDumpInfo.xml
			// updateProjectFlow.setActualGenerationId(retrieveGenerationId()); для нас необязательно
			updateProjectFlow.finish();
			logger.detail("Состояние синхронизации EDT обновлено по " + sourceFolder.resolve("ConfigDumpInfo.xml"));
		} catch (Exception e) {
			if (updateProjectFlow != null && !updateProjectFlow.isFinished()) {
				try {
					updateProjectFlow.cancel();
				} catch (Exception cancelException) {
					e.addSuppressed(cancelException);
				}
			}
			IStatus status = StorageUiPlugin.createErrorStatus("Не удалось обновить состояние синхронизации проекта после операции с хранилищем", e);
			throw new CoreException(status);
		}
	}

	public InfobaseChangesResolutionResult retrieveConfigurationChangesFromInfobase(OperationLogger logger, IProgressMonitor monitor)
			throws CoreException {
		IProgressMonitor actualMonitor = monitor != null ? monitor : new NullProgressMonitor();
		logger.detail("Штатное получение изменений из ИБ в EDT-проект");
		logger.detail("EDT-проект: " + project.getName());
		logger.detail("ИБ: " + issueDescriptor.getInfobase().getName());
		logger.detail("Сервис синхронизации EDT: " + getInfobaseSynchronizationManager().getClass().getName());

		InfobaseSyncResolution resolution = getInfobaseSynchronizationManager().retrieveInfobaseChanges(
				project,
				issueDescriptor.getInfobase(),
				new PullChangesResolver(logger),
				true,
				actualMonitor);
		logInfobaseSyncResolution(resolution, logger);
		IStatus asyncConflictStatus = waitForConflictResolution(resolution, logger);
		ensureInfobaseSyncResolved(resolution, asyncConflictStatus, logger);
		project.refreshLocal(IResource.DEPTH_INFINITE, new NullProgressMonitor());
		logger.detail("EDT-проект обновлен из ИБ штатным механизмом");
		return resolution.getInfobaseChangesResolutionResult();
	}

	private IStatus waitForConflictResolution(InfobaseSyncResolution resolution, OperationLogger logger) throws CoreException {
		if (resolution == null || resolution.getConflictResolution() == null) {
			return null;
		}
		CompletableFuture<IStatus> conflictResolution = resolution.getConflictResolution();
		try {
			IStatus status = conflictResolution.get();
			logStatus("Результат асинхронного разрешения конфликтов EDT", status, logger);
			if (status != null && status.matches(IStatus.ERROR | IStatus.CANCEL)) {
				throw new CoreException(status);
			}
			return status;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new CoreException(StorageUiPlugin.createErrorStatus(
					"Получение изменений из ИБ в EDT прервано во время ожидания разрешения конфликтов", e));
		} catch (ExecutionException e) {
			throw new CoreException(StorageUiPlugin.createErrorStatus(
					"Ошибка при асинхронном разрешении конфликтов EDT", e.getCause() != null ? e.getCause() : e));
		}
	}

	private void ensureInfobaseSyncResolved(InfobaseSyncResolution resolution, IStatus asyncConflictStatus,
			OperationLogger logger) throws CoreException {
		if (resolution == null) {
			throw new CoreException(StorageUiPlugin.createErrorStatus(
					"EDT не вернула результат получения изменений из ИБ"));
		}
		InfobaseChangesResolutionResult result = resolution.getInfobaseChangesResolutionResult();
		if (resolution.isFinished()
				&& (result == InfobaseChangesResolutionResult.NO_CHANGES
						|| result == InfobaseChangesResolutionResult.CHANGES_RESOLVED)) {
			return;
		}
		if (resolution.isFinished()
				&& result == InfobaseChangesResolutionResult.CHANGES_NOT_RESOLVED
				&& asyncConflictStatus != null
				&& !asyncConflictStatus.matches(IStatus.ERROR | IStatus.CANCEL)) {
			logger.detail("EDT вернула CHANGES_NOT_RESOLVED, но отложенное разрешение завершилось успешно; считаем импорт выполненным");
			return;
		}
		throw new CoreException(StorageUiPlugin.createErrorStatus(
				"EDT не смогла применить изменения из ИБ. result=" + result + ", finished=" + resolution.isFinished()));
	}

	private void logInfobaseSyncResolution(InfobaseSyncResolution resolution, OperationLogger logger) {
		if (resolution == null) {
			logger.detail("Результат штатного получения из ИБ: null");
			return;
		}
		logger.detail("Результат штатного получения из ИБ: result="
				+ resolution.getInfobaseChangesResolutionResult()
				+ ", finished=" + resolution.isFinished()
				+ ", hasAsyncConflictResolution=" + (resolution.getConflictResolution() != null));
	}

	private void logStatus(String title, IStatus status, OperationLogger logger) {
		if (status == null) {
			logger.detail(title + ": статус не возвращен");
			return;
		}
		logger.detail(title + ": severity=" + status.getSeverity() + ", message=" + status.getMessage());
		for (IStatus child : status.getChildren()) {
			logger.detail(title + " / child: severity=" + child.getSeverity() + ", message=" + child.getMessage());
		}
	}

	private final class PullChangesResolver implements IInfobaseChangesResolver {

		private static final int MAX_LOGGED_OBJECT_CHANGES = 100;

		private final OperationLogger logger;

		private PullChangesResolver(OperationLogger logger) {
			this.logger = logger;
		}

		@Override
		public InfobaseConflictResolution resolveInfobaseChanges(IProject syncProject, InfobaseReference infobase,
				Set<EObject> projectNewObjects, Set<EObject> projectModifiedObjects, Set<String> projectDeletedObjects,
				IInfobaseConfigurationChange infobaseChanges, IInfobaseUpdateConflictResolver conflictResolver,
				IConflictResolveAssist conflictResolveAssist, IInfobaseSynchronizationFlow synchronizationFlow,
				IProgressMonitor monitor) throws InfobaseSynchronizationException {
			logProjectChanges(projectNewObjects, projectModifiedObjects, projectDeletedObjects);
			logInfobaseChanges(infobaseChanges);
			if (infobaseChanges == null || infobaseChanges.isEmpty()) {
				logger.detail("EDT не обнаружила входящих изменений ИБ");
				return new InfobaseConflictResolution(InfobaseConflictResolutionResult.OVERRIDDEN);
			}
			InfobaseConflictResolution resolution = conflictResolver.resolveConflict(syncProject, infobase,
					projectNewObjects, projectModifiedObjects, projectDeletedObjects, infobaseChanges,
					conflictResolveAssist, synchronizationFlow, monitor);
			logger.detail("Результат разрешения изменений ИБ: " + describeConflictResolution(resolution));
			return resolution;
		}

		private void logProjectChanges(Set<EObject> projectNewObjects, Set<EObject> projectModifiedObjects,
				Set<String> projectDeletedObjects) {
			logger.detail("Локальные изменения EDT перед импортом из ИБ: new=" + size(projectNewObjects)
					+ ", modified=" + size(projectModifiedObjects)
					+ ", deleted=" + size(projectDeletedObjects));
		}

		private void logInfobaseChanges(IInfobaseConfigurationChange infobaseChanges) {
			if (infobaseChanges == null) {
				logger.detail("EDT вернула пустое описание изменений ИБ");
				return;
			}
			Set<ObjectChange> objectChanges = infobaseChanges.getObjectChanges();
			logger.detail("Входящие изменения ИБ: objects=" + size(objectChanges)
					+ ", fullReloadRequired=" + infobaseChanges.isFullReloadRequired()
					+ ", new=" + countChanges(objectChanges, ObjectChangeType.NEW)
					+ ", modified=" + countChanges(objectChanges, ObjectChangeType.MODIFIED)
					+ ", deleted=" + countChanges(objectChanges, ObjectChangeType.DELETED));
			if (objectChanges == null) {
				return;
			}
			int logged = 0;
			for (ObjectChange change : objectChanges) {
				if (logged >= MAX_LOGGED_OBJECT_CHANGES) {
					logger.detail("Входящие изменения ИБ: ... еще "
							+ (objectChanges.size() - MAX_LOGGED_OBJECT_CHANGES));
					break;
				}
				logger.detail("Входящее изменение ИБ: " + change.getType() + " "
						+ change.getPlatformQualifiedName());
				logged++;
			}
		}

		private int countChanges(Set<ObjectChange> changes, ObjectChangeType type) {
			if (changes == null) {
				return 0;
			}
			int result = 0;
			for (ObjectChange change : changes) {
				if (change.getType() == type) {
					result++;
				}
			}
			return result;
		}

		private int size(Set<?> values) {
			return values == null ? 0 : values.size();
		}

		private String describeConflictResolution(InfobaseConflictResolution resolution) {
			if (resolution == null) {
				return "null";
			}
			return "result=" + resolution.getResolutionResult()
					+ ", hasAsyncStatus=" + (resolution.getConflictResolution() != null);
		}
	}

	private void updateActualConfigDumpInfo(IUpdateProjectFlow updateProjectFlow, Path sourceFolder) throws Exception {
		try {
			invokeUpdateProjectFlowMethod(updateProjectFlow, "loadActualConfigDumpInfo", sourceFolder);
		} catch (NoSuchMethodException e) {
			invokeUpdateProjectFlowMethod(updateProjectFlow, "setActualConfigDumpInfo", sourceFolder);
		}
	}

	private void invokeUpdateProjectFlowMethod(IUpdateProjectFlow updateProjectFlow, String methodName, Path sourceFolder)
			throws Exception {
		Method method = updateProjectFlow.getClass().getMethod(methodName, Path.class);
		try {
			method.invoke(updateProjectFlow, sourceFolder);
		} catch (InvocationTargetException e) {
			Throwable cause = e.getCause();
			if (cause instanceof Exception exception) {
				throw exception;
			}
			if (cause instanceof Error error) {
				throw error;
			}
			throw e;
		}
	}
	
	public Path lockObjects(Map<QualifiedName, Boolean> lockObjects, OperationLogger logger) throws IOException, CoreException, InterruptedException {
		// формирование файла со списком объектов для захвата
		Path lockObjectsList = rootDirectory.resolve("lockObjectsList.xml");
		String strTemplate = "<Object fullName = \"{0}\" includeChildObjects = \"{1}\" />";
		try (BufferedWriter writer = new BufferedWriter(new FileWriter(lockObjectsList.toString(), StandardCharsets.UTF_8))){
			writer.append("<Objects xmlns=\"http://v8.1c.ru/8.3/config/objects\" version=\"1.0\">"+System.lineSeparator());
			for (Map.Entry<QualifiedName, Boolean> entry : lockObjects.entrySet()) {
				QualifiedName key = entry.getKey();
				Boolean val = entry.getValue();
				if ("Configuration".equals(key.toString())) {
					writer.append("<Configuration includeChildObjects = \"false\" />"+System.lineSeparator());
				} else {
					writer.append(MessageFormat.format(strTemplate, key.toString(), val.toString())+System.lineSeparator());
				}
			}
			writer.append("</Objects>");
		} catch (IOException e) {
			throw e;
		}
		
		Path log = rootDirectory.resolve("lockObjectsOut.txt");
		RuntimeExecutionCommandBuilder command = getCommandBuilder(log);
		String additionalStartupParameters = getRepositoryConnectionParameters()
		+ " /ConfigurationRepositoryLock -Objects " + quoteParameter(lockObjectsList.toString())
		+ "{0}";
		
		if (!extensionName.isEmpty()) {
			command.additionalParameters(MessageFormat.format(additionalStartupParameters, " -Extension " + quoteParameter(extensionName)));
		}
		else {
			command.additionalParameters(MessageFormat.format(additionalStartupParameters, ""));
		}
		
		logCommandContext(logger, "Захват объектов в хранилище", commandParametersForLock(additionalStartupParameters));
		int returnCode = runCommand("Захват объектов в хранилище", command, log, logger);
		if (returnCode != 0) {
			if (!extensionName.isEmpty()) { // имя расширения могло быть переименовано в EDT
				Path logListExtNames = rootDirectory.resolve("listExtNamesOut.txt");
				command = getCommandBuilder(logListExtNames);
				command.listConfigurationExtensions();
				logCommandContext(logger, "Получение списка расширений ИБ", "ListConfigurationExtensions");
				returnCode = runCommand("Получение списка расширений ИБ", command, logListExtNames, logger);
				if (returnCode != 0) {
					IStatus status = StorageUiPlugin.createErrorStatus(Files.readString(logListExtNames));
					throw new CoreException(status);
				}
				else {
					try (BufferedReader reader = new BufferedReader(new FileReader(logListExtNames.toString(),StandardCharsets.UTF_8))) {
						String line;
						boolean extensionIsFound = false;
						while ((line = reader.readLine()) != null) {
							Path logExtensionLockObjects = rootDirectory.resolve("extensionObjectsOut.txt");
							command = getCommandBuilder(logExtensionLockObjects);
							String extensionLockParameters = MessageFormat.format(additionalStartupParameters, " -Extension " + quoteParameter(line));
							command.additionalParameters(extensionLockParameters);
							logCommandContext(logger, "Захват объектов в расширении " + line, extensionLockParameters);
							returnCode = runCommand("Захват объектов в расширении " + line, command, logExtensionLockObjects, logger);
							if (returnCode == 0) {
								extensionName = line;
								extensionIsFound = true;
								break;
							}
						}
						if (!extensionIsFound) {
							Settings storageSettings = new Settings(project.getName());
							IStatus status = StorageUiPlugin.createErrorStatus("В ИБ не обнаружено расширение, подключенное к хранилищу "
										+ storageSettings.getAddress());
							throw new CoreException(status);
						}
					} catch (IOException e) {
						throw e;
					}
				}
			} 
			else {
				IStatus status = StorageUiPlugin.createErrorStatus(Files.readString(log));
				throw new CoreException(status);
			}
		}
		return lockObjectsList;
	}

	public boolean isConfigurationSame(OperationLogger logger) throws CoreException, IOException, InterruptedException {
		Path log = rootDirectory.resolve("compareCfgOut.txt");
		Path reportFile = rootDirectory.resolve("compareCfgReport.txt");
		
		RuntimeExecutionCommandBuilder command = getCommandBuilder(log);
		
		String additionalStartupParameters = "/CompareCfg "
			+ "-FirstConfigurationType {0} -SecondConfigurationType {1} "
			+ "-IncludeChangedObjects -IncludeDeletedObjects -IncludeAddedObjects "
			+ "-ReportType Brief -ReportFormat txt "
			+ "-ReportFile " + quoteParameter(reportFile.toString());
		
		if (!extensionName.isEmpty()) {
			additionalStartupParameters = MessageFormat.format(additionalStartupParameters, 
				"ExtensionConfiguration -FirstName " + quoteParameter(extensionName),
				"ExtensionDBConfiguration -SecondName " + quoteParameter(extensionName));
		}
		else {
			additionalStartupParameters = MessageFormat.format(additionalStartupParameters, "MainConfiguration", "DBConfiguration");
		}
		
		command.additionalParameters(getRepositoryConnectionParameters() + " " + additionalStartupParameters);
		logCommandContext(logger, "Сравнение конфигурации с конфигурацией БД",
				getRepositoryConnectionParameters() + " " + additionalStartupParameters);
		
		int returnCode = runCommand("Сравнение конфигурации с конфигурацией БД", command, log, logger);
		if (returnCode != 0) {
			IStatus status = StorageUiPlugin.createErrorStatus(Files.readString(log));
			throw new CoreException(status);
		}
		
		long lineCount = 0;
		try (BufferedReader reader = new BufferedReader(new FileReader(reportFile.toString(),StandardCharsets.UTF_16))) { // тут UTF_16 почему-то
			lineCount = reader.lines().count();
		} catch (IOException e) {
			throw e;
		}
		
		if (lineCount == 6) { // нет изменений
			logger.detail("Сравнение: конфигурация и конфигурация БД совпадают");
			return true;
		} else {
			logger.detail("Сравнение: найдены отличия, строк в отчете: " + lineCount + ", отчет=" + reportFile);
			return false;
		}
	}

	public String getExtensionName() throws CoreException, IOException, InterruptedException {
		String result = "";
		IV8Project v8Project = getV8ProjectManager().getProject(project);
		if (v8Project instanceof IExtensionProject extensionProject) {
			result = extensionProject.getConfiguration().getName();
		}
		
		return result;
	}

	public String retrieveGenerationId() throws CoreException, IOException, InterruptedException {
		Path log = rootDirectory.resolve("generationIdOut.txt");
		
		RuntimeExecutionCommandBuilder command = getCommandBuilder(log).additionalParameters("/GetConfigGenerationID");
		
		Process process = command.start();
		int returnCode = process.waitFor();
		if (returnCode != 0) {
			IStatus status = StorageUiPlugin.createErrorStatus(Files.readString(log));
			throw new CoreException(status);
		}
		
		String result = Files.readString(log);
		result = result.replaceAll("\r\n", "");
		
		return result;
	}

	private String getRepositoryConnectionParameters() {
		Settings storageSettings = new Settings(project.getName());
		return "/ConfigurationRepositoryF " + quoteParameter(storageSettings.getAddress())
			+ " /ConfigurationRepositoryN " + quoteParameter(storageSettings.getUser())
			+ (storageSettings.getPassword().isEmpty() ? "" : " /ConfigurationRepositoryP " + quoteParameter(storageSettings.getPassword()));
	}

	private int runCommand(String title, RuntimeExecutionCommandBuilder command, Path log, OperationLogger logger)
			throws IOException, InterruptedException {
		logger.detail(title + ": запуск пакетной команды");
		Process process = command.start();
		logger.detail(title + ": процесс запущен, log=" + log);
		int loggedLength = 0;
		while (true) {
			loggedLength = logNewCommandOutput(title, log, logger, loggedLength);
			if (process.waitFor(500, TimeUnit.MILLISECONDS)) {
				break;
			}
		}
		logNewCommandOutput(title, log, logger, loggedLength);
		int returnCode = process.exitValue();
		logger.commandResult(title, log, returnCode, false);
		return returnCode;
	}

	private int logNewCommandOutput(String title, Path log, OperationLogger logger, int loggedLength) throws IOException {
		String output;
		try {
			if (!Files.exists(log)) {
				return loggedLength;
			}
			output = Files.readString(log);
		} catch (IOException e) {
			return loggedLength;
		}
		if (output.length() < loggedLength) {
			loggedLength = 0;
		}
		if (output.length() == loggedLength) {
			return loggedLength;
		}
		if (loggedLength == 0) {
			logger.detail(title + " output:");
		}
		String newOutput = output.substring(loggedLength);
		for (String line : newOutput.split("\\R")) {
			if (!line.isEmpty()) {
				logger.detail("  " + line);
			}
		}
		return output.length();
	}

	private String commandParametersForLock(String parametersTemplate) {
		if (!extensionName.isEmpty()) {
			return MessageFormat.format(parametersTemplate, " -Extension " + quoteParameter(extensionName));
		}
		return MessageFormat.format(parametersTemplate, "");
	}

	private void logCommandContext(OperationLogger logger, String title, String additionalParameters) {
		logger.detail(title + ": проект=" + project.getName() + ", цель=" + getStorageTargetDescription());
		logger.detail(title + ": исполняемый файл=" + thickClient.getComponent().getFile());
		logger.detail(title + ": ИБ=" + issueDescriptor.getInfobase().getName());
		logger.detail(title + ": пакетная команда=DESIGNER " + maskSensitiveParameters(additionalParameters));
	}

	private String maskSensitiveParameters(String parameters) {
		return parameters.replaceAll("(?i)(/ConfigurationRepositoryP\\s+)\"[^\"]*\"", "$1\"******\"");
	}

	private String quoteParameter(String value) {
		return "\"" + value.replace("\"", "'") + "\"";
	}

}
