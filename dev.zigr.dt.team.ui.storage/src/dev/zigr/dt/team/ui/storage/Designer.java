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
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.xtext.naming.QualifiedName;

import com._1c.g5.v8.dt.core.platform.IExtensionProject;
import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAccessManager;
import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAccessSettings;
import com._1c.g5.v8.dt.platform.services.core.infobases.InfobaseAccessType;
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
	
	public void dispose() {
		infobaseAccessManagerSupplier.close();
		runtimeComponentManagerSupplier.close();
		v8ProjectManagerSupplier.close();
		resolvableRuntimeInstallationManagerSupplier.close();
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
		
		Process process = command.start();
		int returnCode = process.waitFor();
		logger.commandResult("Загрузка XML в конфигурацию", log, returnCode);
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

		Process process = command.start();
		int returnCode = process.waitFor();
		logger.commandResult("Помещение изменений в хранилище", log, returnCode);
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

		Process process = command.start();
		int returnCode = process.waitFor();
		logger.commandResult("Получение конфигурации из хранилища", log, returnCode);
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

		Process process = command.start();
		int returnCode = process.waitFor();
		logger.commandResult("Выгрузка конфигурации в XML", log, returnCode);
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
		
		Process process = command.start();
		int returnCode = process.waitFor();
		logger.commandResult("Захват объектов в хранилище", log, returnCode);
		if (returnCode != 0) {
			if (!extensionName.isEmpty()) { // имя расширения могло быть переименовано в EDT
				Path logListExtNames = rootDirectory.resolve("listExtNamesOut.txt");
				command = getCommandBuilder(logListExtNames);
				command.listConfigurationExtensions();
				process = command.start();
				returnCode = process.waitFor();
				logger.commandResult("Получение списка расширений ИБ", logListExtNames, returnCode);
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
							command.additionalParameters(MessageFormat.format(additionalStartupParameters, " -Extension " + quoteParameter(line)));
							process = command.start();
							returnCode = process.waitFor();
							logger.commandResult("Захват объектов в расширении " + line, logExtensionLockObjects, returnCode);
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
		
		Process process = command.start();
		int returnCode = process.waitFor();
		logger.commandResult("Сравнение конфигурации с конфигурацией БД", log, returnCode);
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

	private void logCommandContext(OperationLogger logger, String title, String additionalParameters) {
		logger.detail(title + ": проект=" + project.getName() + ", цель=" + getStorageTargetDescription());
		logger.detail(title + ": параметры=" + maskSensitiveParameters(additionalParameters));
	}

	private String maskSensitiveParameters(String parameters) {
		return parameters.replaceAll("(?i)(/ConfigurationRepositoryP\\s+)\"[^\"]*\"", "$1\"******\"");
	}

	private String quoteParameter(String value) {
		return "\"" + value.replace("\"", "'") + "\"";
	}

}
