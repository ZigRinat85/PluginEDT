package dev.zigr.dt.team.ui.storage;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.commands.IHandler;
import org.eclipse.core.commands.IHandlerListener;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.Adapters;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.RenameDetector;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.AbstractTreeIterator;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.MessageBox;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.handlers.HandlerUtil;
import org.eclipse.xtext.naming.QualifiedName;

import com._1c.g5.v8.bm.core.BmPlatform;
import com._1c.g5.v8.bm.core.IBmNamespace;
import com._1c.g5.v8.bm.core.IBmPlatformTransaction;
import com._1c.g5.v8.dt.common.FileUtil;
import com._1c.g5.v8.dt.core.filesystem.IQualifiedNameFilePathConverter;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.export.IExportOperation;
import com._1c.g5.v8.dt.export.IExportOperationFactory;
import com._1c.g5.v8.dt.export.IExportStrategy;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.RuntimeExecutionException;
import com._1c.g5.v8.dt.team.git.infobases.IGitBranchIssueDescriptor;
import com.google.inject.Inject;

public class ExportHandler implements IHandler {

	@Inject
	private IQualifiedNameFilePathConverter qualifiedNameFilePathConverter;
	@Inject
	private IBmModelManager modelManager;
	@Inject
	private IExportOperationFactory exportOperationFactory;
	
	private Shell shell;
	private IGitBranchIssueDescriptor issueDescriptor;
	private Settings storageSettings;

	@Override
	public Object execute(ExecutionEvent event) throws ExecutionException {
		shell = HandlerUtil.getActiveShell(event);
		
		MessageBox dialog = new MessageBox(shell, SWT.ICON_QUESTION | SWT.YES | SWT.NO);
		dialog.setText("Поместить в хранилище");
		dialog.setMessage("Уверены?");
		if (dialog.open() == SWT.NO) {
			return null;
		}
		
		IStructuredSelection selection = HandlerUtil.getCurrentStructuredSelection(event);
		Object firstElement = selection.getFirstElement();
		issueDescriptor = (IGitBranchIssueDescriptor) Adapters.adapt(firstElement, IGitBranchIssueDescriptor.class);

		OperationLogger logger;
		try {
			logger = OperationLogger.create();
		} catch (IOException e) {
			StorageUiPlugin.logError(e.getMessage(), e);
			MessageDialog.openError(shell, "Ошибка", "Не удалось создать журнал операции");
			return null;
		}
		logger.step("Старт операции помещения в хранилище");
		logger.detail("ИБ: " + issueDescriptor.getInfobase().getName());
		logger.detail("Ветка хранилища: " + issueDescriptor.getBranch().getName());

		// diff
		Map<String, List<DiffEntry>> allDiff = getBranchDiff(logger);
		if (allDiff == null || allDiff.isEmpty()) {
			return null;
		}

		OperationLogDialog logDialog = new OperationLogDialog(shell, "Поместить в хранилище", logger,
				monitor -> pushAllDiff(allDiff, logger, monitor));
		logDialog.open();

		return null;
	}

	private boolean pushAllDiff(Map<String, List<DiffEntry>> allDiff, OperationLogger logger, IProgressMonitor monitor) {
		boolean result = true;
		monitor.beginTask("Помещение изменений в хранилище", IProgressMonitor.UNKNOWN);
		for (Map.Entry<String, List<DiffEntry>> entry : allDiff.entrySet()) {
			
			String projectName = entry.getKey();
			List<DiffEntry> diff = entry.getValue();
			storageSettings = new Settings(projectName);
			logger.step("Обработка проекта " + projectName);
			monitor.subTask("Проект " + projectName + ": подготовка временного каталога");
			
			// rootDirectory
			Path rootDirectory;
			try {
				rootDirectory = FileUtil.createTempDirectory("Zigr").toPath();
				logger.detail("Временный каталог: " + rootDirectory);
			} catch (IOException e) {
				logger.error(e.getMessage(), e);
				result = false;
				break;
			}
			
			// pushBranchDiff
			try {
				if (pushBranchDiff(projectName, diff, rootDirectory, logger, monitor)) {
					String message = MessageFormat.format("Операция помещения в хранилище выполнена. ИБ={0}. Проект={1}",
							issueDescriptor.getInfobase().getName(), projectName);
					StorageUiPlugin.logInfo(message);
					logger.detail(message);
				} else {
					result = false;
				}
			} catch (IOException | CoreException | RuntimeExecutionException | InterruptedException e) {
				logger.error(e.getMessage(), e);
				result = false;
			}
			
			// очистка временных файлов
			try {
				FileUtil.deleteRecursivelyWithRetries(rootDirectory);
				logger.detail("Временный каталог удален: " + rootDirectory);
			} catch (IOException e) {
				logger.error(e.getMessage(), e);
			}
			
			if (!result) {
				break;
			}
		}
		monitor.done();
		return result;
	}

	private boolean pushBranchDiff(String projectName, List<DiffEntry> diff, Path rootDirectory, OperationLogger logger, IProgressMonitor monitor) throws IOException, CoreException, RuntimeExecutionException, InterruptedException {
		Path exportDirectory = FileUtil.createTempDirectory("Export", rootDirectory).toPath();
		Designer designer = new Designer(issueDescriptor, projectName, rootDirectory);
		logger.detail("Каталог XML-выгрузки: " + exportDirectory);
		
		// закрытие агента конфигуратора
		logger.step("Закрытие активной сессии конфигуратора");
		monitor.subTask("Закрытие активной сессии конфигуратора");
		designer.closeDesignerSession();
		
		// получение списка объектов к захвату
		logger.step("Определение объектов хранилища для захвата");
		Map<QualifiedName, Boolean> lockObjects = getLockObjects(diff);
		if (lockObjects.isEmpty()) {
			IStatus status = StorageUiPlugin.createErrorStatus("Не удалось определить объекты для захвата");
			throw new CoreException(status);
		}
		for (Map.Entry<QualifiedName, Boolean> lockObject : lockObjects.entrySet()) {
			logger.detail("Объект к захвату: " + lockObject.getKey() + ", includeChildObjects=" + lockObject.getValue());
		}
		
		// захват объектов
		logger.step("Захват объектов в хранилище");
		monitor.subTask("Захват объектов в хранилище");
		Path lockObjectsList = designer.lockObjects(lockObjects, logger);
		
		// проверка отличия конфигурации от конфигурации БД
		logger.step("Проверка отличий конфигурации от конфигурации БД");
		monitor.subTask("Сравнение конфигурации и конфигурации БД");
		if (!designer.isConfigurationSame(logger)) {
			if (storageSettings.getPushIfConfigurationChanged()) {
				int[] buttonId = new int[] { SWT.NO };
				shell.getDisplay().syncExec(() -> {
					MessageBox dialog = new MessageBox(shell, SWT.ICON_WARNING | SWT.YES | SWT.NO);
					dialog.setText("Внимание!!!");
					String textMessage = textMessageIfConfigurationChanged(projectName)
							+ System.lineSeparator() + System.lineSeparator()
							+ "Все равно продолжить помещение?";
					dialog.setMessage(textMessage);
					buttonId[0] = dialog.open();
				});
				if (buttonId[0] == SWT.NO) {
					String message = MessageFormat.format("Операция помещения в хранилище отменена пользователем. ИБ={0}. Проект={1}",
							issueDescriptor.getInfobase().getName(), projectName);
					StorageUiPlugin.logInfo(message);
					logger.detail(message);
					return false;
				}
			} else {
				String textMessage = textMessageIfConfigurationChanged(projectName);
				IStatus status = StorageUiPlugin.createErrorStatus(textMessage);
				throw new CoreException(status);
			}
		}
		
		// выгрузка файлов в формате v8
		logger.step("Выгрузка измененных объектов EDT в XML");
		monitor.subTask("Выгрузка объектов EDT в XML");
		EObject[] topObjects = getTopObjects(projectName, diff);
		logger.detail("Количество верхних объектов EDT для выгрузки: " + topObjects.length);
		IExportOperation exportOperation = exportOperationFactory.createExportOperation
				(exportDirectory, designer.getVersion(), new IncrementalExportStrategy(), topObjects);
		IProgressMonitor exportMonitor = new NullProgressMonitor();
		IStatus status = exportOperation.run(exportMonitor);
		if (status.getSeverity() == 4) { 
			throw new CoreException(status);
		}
		
		// получение списка файлов к загрузке в базу 1с
		V8FileBuilder v8FileBuilder = new V8FileBuilder(exportDirectory, projectName);
		v8FileBuilder.setSourceFiles(diff);
		Set<Path> exportFiles = v8FileBuilder.getExportFiles();
		logger.step("Формирование списка файлов для загрузки в 1С");
		Path listFiles = rootDirectory.resolve("listFiles.txt");
		try (BufferedWriter writer = new BufferedWriter(new FileWriter(listFiles.toString(), StandardCharsets.UTF_8))){
			for (Path exportFile : exportFiles) {
				writer.append(exportFile.toString()+System.lineSeparator());
				logger.detail("Файл к загрузке: " + exportFile);
			}
		} catch (IOException e) {
			throw e;
		}
		logger.detail("Файл списка загрузки: " + listFiles);
		
		// загрузка файлов в базу 1с
		logger.step("Загрузка XML в конфигурацию ИБ");
		monitor.subTask("Загрузка XML в конфигурацию ИБ");
		designer.loadConfigurationFromXml(exportDirectory, listFiles, logger);

		// помещение изменений в хранилище
		logger.step("Помещение изменений в хранилище 1С");
		monitor.subTask("Помещение изменений в хранилище 1С");
		String commitComment = getCommitComment(projectName, diff);
		logger.detail("Комментарий помещения: " + commitComment);
		designer.commitObjects(lockObjectsList, commitComment, logger);

		// обновление состояния синхронизации EDT
		logger.step("Обновление состояния синхронизации EDT");
		monitor.subTask("Обновление состояния синхронизации EDT");
		designer.updateProjectSynchronizationState(exportDirectory, logger);
		
		// 
		designer.dispose();
		return true;
	}

	private EObject[] getTopObjects(String projectName, List<DiffEntry> diff) {
		
		Set<EObject> topObjects = new HashSet<EObject>();
		IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
		
		Set<String> sourceFiles = new HashSet<String>();
		for (DiffEntry entry : diff) {
			String sourceFile = getProjectSourcePath(entry.getNewPath());
			if (V8FileBuilder.isV8File(sourceFile)) {
				sourceFiles.add(sourceFile);
			}
		}
		
		Set<String> fqnStrings = new HashSet<String>();
		for (String sourceFile : sourceFiles) {
			QualifiedName fqn = qualifiedNameFilePathConverter.getFqn(sourceFile);
			if (fqn == null) {
				continue;
			}
			int segmentCount = fqn.getSegmentCount();
			if ("Configuration".equals(fqn.getFirstSegment())) {
				fqnStrings.add("Configuration");
			} else if (segmentCount >= 2) {
				fqnStrings.add(fqn.skipLast(segmentCount - 2).toString());
			}
		}
		
		BmPlatform platform = modelManager.getBmPlatform();
		IBmNamespace ns = modelManager.getBmNamespace(project);
		IBmPlatformTransaction transaction = platform.beginReadOnlyTransaction(true);
		for (String fqnString : fqnStrings) {
			EObject topObject = (EObject) transaction.getTopObjectByFqn(ns, fqnString);
			if (topObject != null) {
				topObjects.add(topObject);
			}
		}
		transaction.commit();
		
		EObject[] result = new EObject[topObjects.size()];
		topObjects.toArray(result);
		return result;
	}

	private Map<QualifiedName, Boolean> getLockObjects(List<DiffEntry> diff) {
		Map<QualifiedName, Boolean> result = new HashMap<QualifiedName, Boolean>();
		
		Set<String> sourceFiles = new HashSet<String>();
		for (DiffEntry entry : diff) {
			String oldPath = entry.getOldPath();
			String newPath = entry.getNewPath();
			String sourceFile;
			if (oldPath == DiffEntry.DEV_NULL) {
				sourceFile = newPath;
			}
			else {
				sourceFile = oldPath;
			}
			sourceFile = getProjectSourcePath(sourceFile);
			
			if (V8FileBuilder.isV8File(sourceFile)) {
				sourceFiles.add(sourceFile);
			}
		}
		
		for (String sourceFile : sourceFiles) {
			QualifiedName fqn = qualifiedNameFilePathConverter.getFqn(sourceFile);
			if (fqn == null) {
				continue;
			}
			int segmentCount = fqn.getSegmentCount();
			String firstSegment = fqn.getFirstSegment();
			if ("Configuration".equals(firstSegment)) {
				result.put(fqn.skipLast(segmentCount - 1), false);
			} else if ("Subsystem".equals(firstSegment)) {
				int firstCount = 0;
				for (int i = 0; i < segmentCount; i = i + 2) {
					if ("Subsystem".equals(fqn.getSegment(i))) {
						firstCount = i + 2;
					} 
					else {
						break;
					}
				}
				if (firstCount > 0) {
					result.put(fqn.skipLast(segmentCount - firstCount), false);
				}
			} else if ("ExternalDataSource".equals(firstSegment)) {
				// сложная структура, редко используется
				result.put(fqn.skipLast(segmentCount - 2), true);
				
			} else if ("CalculationRegister".equals(firstSegment) && sourceFile.endsWith(".mdo")) {
				// Recalculation самостоятельный объект, но встроен в файл .mdo. Безусловный захват с подчиненными
				result.put(fqn.skipLast(segmentCount - 2), true);
				
			} else if (segmentCount >= 4 
					&& ("Form".equals(fqn.getSegment(2)) || "Template".equals(fqn.getSegment(2)))) {
				result.put(fqn.skipLast(segmentCount - 4), false);
				
			} else if (storageSettings.getExportMDWithMDO()) {
				if (sourceFile.endsWith(".mdo")) {
					// файлы .mdo захватываем с подчиненными, если включена настройка
					result.put(fqn.skipLast(segmentCount - 2), true);
				} else {
					if (result.get(fqn.skipLast(segmentCount - 2)) == null) { // возможно уже был добавлен .mdo
						result.put(fqn.skipLast(segmentCount - 2), false);
					}
				}
				
			} else {
				result.put(fqn.skipLast(segmentCount - 2), false);
			}
		}
		
		return result;
	}

	public Map<String, List<DiffEntry>> getBranchDiff(OperationLogger logger) {
		
		Map<String, List<DiffEntry>> result = new HashMap<String, List<DiffEntry>>();
		
		List<DiffEntry> allDiff;
		Repository repository = issueDescriptor.getRepository();
		try (Git git = new Git(repository)) {
			String importBranch = issueDescriptor.getBranch().getName();
			String currentBranch = repository.getFullBranch();
			logger.detail("Текущая ветка Git: " + currentBranch);
			logger.detail("Ветка хранилища Git: " + importBranch);
			if (importBranch.equals(currentBranch)) {
				MessageDialog.openWarning(shell, "Внимание", "Нельзя выбирать текущую ветку");
				return null;
			}
			// the diff works on TreeIterators, we prepare two for the two branches
			AbstractTreeIterator oldTreeParser = prepareTreeParser(repository, importBranch);
			AbstractTreeIterator newTreeParser = prepareTreeParser(repository, currentBranch);
			// then the procelain diff-command returns a list of diff entries
			allDiff = git.diff().setOldTree(oldTreeParser).setNewTree(newTreeParser).call();
			// RenameDetector
			RenameDetector rd = new RenameDetector(repository);
			rd.addAll(allDiff);
			allDiff = rd.compute();
			logger.step("Анализ отличий веток Git");
			logger.detail("Всего отличий Git: " + allDiff.size());
			if (allDiff.isEmpty()) {
				MessageDialog.openWarning(shell, "Внимание", "Ветки не различаются");
				return null;
			}
		} catch (GitAPIException | IOException e) {
			StorageUiPlugin.logError(e.getMessage(), e);
			MessageDialog.openError(shell, "Ошибка", "Не удалось определить различия веток (см. Журнал ошибок)");
			return null;
		}
		
		for (DiffEntry entry : allDiff) {
			String oldPath = entry.getOldPath();
			String newPath = entry.getNewPath();
			String sourceFile;
			if (newPath == DiffEntry.DEV_NULL) {
				sourceFile = oldPath;
			}
			else {
				sourceFile = newPath;
			}
			
			org.eclipse.core.runtime.IPath path = new org.eclipse.core.runtime.Path(sourceFile);
			String projectName = getProjectName(path);
			if (projectName == null) {
				continue;
			}
			
			List<DiffEntry> projectDiff = result.get(projectName);
			if (projectDiff == null) {
				projectDiff = new ArrayList<DiffEntry>();
				result.put(projectName, projectDiff);
			}
			projectDiff.add(entry);
			logger.detail("Изменение проекта " + projectName + ": " + entry.getChangeType() + " " + sourceFile);
		}
		if (result.isEmpty()) {
			String message = "Ветки различаются, но среди изменений не найдены файлы проектов EDT в папке src. "
					+ "Проверьте структуру репозитория и что изменения зафиксированы в Git.";
			StorageUiPlugin.logInfo(message);
			logger.detail(message);
			MessageDialog.openWarning(shell, "Внимание", message);
		}
		
		return result;
	}

	private static String getProjectName(org.eclipse.core.runtime.IPath path) {
		int sourceSegmentIndex = getSourceSegmentIndex(path);
		if (sourceSegmentIndex <= 0 || sourceSegmentIndex >= path.segmentCount() - 1) {
			return null;
		}

		return path.segment(sourceSegmentIndex - 1);
	}

	private static String getProjectSourcePath(String sourceFile) {
		if (DiffEntry.DEV_NULL.equals(sourceFile)) {
			return sourceFile;
		}

		org.eclipse.core.runtime.IPath path = new org.eclipse.core.runtime.Path(sourceFile);
		int sourceSegmentIndex = getSourceSegmentIndex(path);
		if (sourceSegmentIndex <= 1) {
			return sourceFile;
		}

		return path.removeFirstSegments(sourceSegmentIndex - 1).toString();
	}

	private static int getSourceSegmentIndex(org.eclipse.core.runtime.IPath path) {
		for (int i = 0; i < path.segmentCount(); i++) {
			if ("src".equals(path.segment(i))) {
				return i;
			}
		}

		return -1;
	}

	private String getCommitComment(String projectName, List<DiffEntry> diff) throws IOException {
		Repository repository = issueDescriptor.getRepository();
		String branch = repository.getBranch();
		String storageBranch = issueDescriptor.getBranch().getName();
		String changedFiles = Integer.toString(diff.size());
		String files = getChangedFilesCommentValue(diff);

		return storageSettings.getCommitCommentTemplate()
				.replace("{branch}", branch)
				.replace("{storageBranch}", storageBranch)
				.replace("{project}", projectName)
				.replace("{changedFiles}", changedFiles)
				.replace("{fileCount}", changedFiles)
				.replace("{files}", files)
				.replace("{infobase}", issueDescriptor.getInfobase().getName());
	}

	private String getChangedFilesCommentValue(List<DiffEntry> diff) {
		Set<String> changedFiles = new LinkedHashSet<String>();
		for (DiffEntry entry : diff) {
			String sourceFile = entry.getNewPath();
			if (DiffEntry.DEV_NULL.equals(sourceFile)) {
				sourceFile = entry.getOldPath();
			}
			if (!DiffEntry.DEV_NULL.equals(sourceFile)) {
				changedFiles.add(getProjectSourcePath(sourceFile));
			}
		}

		return String.join(", ", changedFiles);
	}

	private static AbstractTreeIterator prepareTreeParser(Repository repository, String ref) throws IOException {
		// from the commit we can build the tree which allows us to construct the TreeParser
		Ref head = repository.exactRef(ref);
		try (RevWalk walk = new RevWalk(repository)) {
			RevCommit commit = walk.parseCommit(head.getObjectId());
			RevTree tree = walk.parseTree(commit.getTree().getId());
			
			CanonicalTreeParser treeParser = new CanonicalTreeParser();
			try (ObjectReader reader = repository.newObjectReader()) {
				treeParser.reset(reader, tree.getId());
			}
			
			walk.dispose();
			return treeParser;
		}
	}

	private static final class IncrementalExportStrategy implements IExportStrategy {
		
		@Override
		public boolean exportSubordinatesObjects(EObject eObject) {
			return !(eObject instanceof Configuration);
		}

		@Override
		public boolean exportExternalProperties(EObject eObject) {
			return true;
		}

		public boolean exportUnknown() {
			return false;
		}

	}

	private String textMessageIfConfigurationChanged(String projectName) {
		String textMessage = "Проект:{0}. После захвата объектов в хранилище обнаружено отличие конфигурации от конфигурации БД!"
				+ System.lineSeparator() + System.lineSeparator()
				+ "Это могут быть изменения, полученные из хранилища во время захвата. Во избежание потерь этих изменений "
				+ "нужно переключиться на ветку хранилища, импортировать туда все изменения, переключиться на текущую ветку "
				+ "и влить изменения из ветки хранилища.";
		textMessage = MessageFormat.format(textMessage, projectName);
		return textMessage;
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
		// Auto-generated method stub
	}

	@Override
	public void dispose() {
		// Auto-generated method stub
	}

	@Override
	public void removeHandlerListener(IHandlerListener handlerListener) {
		// Auto-generated method stub
	}
}
