package dev.zigr.dt.team.ui.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.commands.IHandler;
import org.eclipse.core.commands.IHandlerListener;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.handlers.HandlerUtil;
import org.eclipse.xtext.naming.QualifiedName;

import com._1c.g5.v8.dt.common.FileUtil;
import com._1c.g5.v8.dt.core.filesystem.IQualifiedNameFilePathConverter;
import com._1c.g5.v8.dt.core.platform.IResourceLookup;
import com._1c.g5.v8.dt.export.IExportOperation;
import com._1c.g5.v8.dt.export.IExportOperationFactory;
import com._1c.g5.v8.dt.export.IExportStrategy;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.RuntimeExecutionException;
import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import com.google.inject.Inject;

public class StorageNavigatorHandler implements IHandler {

	private static final String COMMAND_LOCK = "dev.zigr.dt.team.ui.storage.command.navigator.lock";
	private static final String COMMAND_UNLOCK = "dev.zigr.dt.team.ui.storage.command.navigator.unlock";
	private static final String COMMAND_COMMIT = "dev.zigr.dt.team.ui.storage.command.navigator.commit";
	private static final String COMMAND_REFRESH_LOCK_STATE = "dev.zigr.dt.team.ui.storage.command.navigator.refreshLockState";

	@Inject
	private IResourceLookup resourceLookup;
	@Inject
	private IQualifiedNameFilePathConverter qualifiedNameFilePathConverter;
	@Inject
	private IExportOperationFactory exportOperationFactory;

	@Override
	public Object execute(ExecutionEvent event) throws ExecutionException {
		Shell shell = HandlerUtil.getActiveShell(event);
		IStructuredSelection selection = HandlerUtil.getCurrentStructuredSelection(event);
		StorageNavigatorObjectResolver.ResolvedSelection resolvedSelection;
		try {
			resolvedSelection = new StorageNavigatorObjectResolver(resourceLookup, qualifiedNameFilePathConverter)
					.resolveSelection(selection);
		} catch (IllegalArgumentException e) {
			MessageDialog.openError(shell, "Хранилище конфигурации", e.getMessage());
			return null;
		}
		if (resolvedSelection.isEmpty()) {
			MessageDialog.openWarning(shell, "Хранилище конфигурации",
					"Не удалось определить выбранные объекты хранилища");
			return null;
		}

		String commandId = event.getCommand().getId();
		if (COMMAND_REFRESH_LOCK_STATE.equals(commandId)) {
			refreshLockState(shell, resolvedSelection);
			return null;
		}

		if (COMMAND_COMMIT.equals(commandId) && resolvedSelection.exportObjects().isEmpty()) {
			MessageDialog.openWarning(shell, "Поместить в хранилище",
					"Для выбранного элемента нельзя подготовить выгрузку в хранилище");
			return null;
		}

		InfobaseReference infobase;
		try {
			infobase = InfobaseResolver.getDefaultInfobase(resolvedSelection.project());
		} catch (CoreException e) {
			StorageUiPlugin.logError(e.getMessage(), e);
			MessageDialog.openError(shell, "Хранилище конфигурации", e.getMessage());
			return null;
		}

		if (new Settings(resolvedSelection.project().getName()).getAddress().isBlank()) {
			MessageDialog.openWarning(shell, "Хранилище конфигурации",
					"Для проекта " + resolvedSelection.project().getName() + " не заполнен адрес хранилища");
			return null;
		}

		if (!confirm(shell, commandId, resolvedSelection)) {
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

		logger.step(getStartMessage(commandId));
		logger.detail("Проект: " + resolvedSelection.project().getName());
		logger.detail("ИБ: " + infobase.getName());
		for (QualifiedName objectName : resolvedSelection.objectNames()) {
			logger.detail("Объект хранилища: " + objectName);
		}

		OperationLogDialog dialog = new OperationLogDialog(shell, getTitle(commandId), logger,
				monitor -> runOperation(commandId, infobase, resolvedSelection, logger, monitor));
		dialog.open();

		return null;
	}

	private boolean confirm(Shell shell, String commandId, StorageNavigatorObjectResolver.ResolvedSelection selection) {
		String message = getTitle(commandId) + "?"
				+ System.lineSeparator() + System.lineSeparator()
				+ selection.objectNamesText();
		return MessageDialog.openQuestion(shell, getTitle(commandId), message);
	}

	private boolean runOperation(String commandId, InfobaseReference infobase,
			StorageNavigatorObjectResolver.ResolvedSelection selection, OperationLogger logger, IProgressMonitor monitor) {
		try {
			if (COMMAND_LOCK.equals(commandId)) {
				lockObjects(infobase, selection, logger, monitor);
			} else if (COMMAND_UNLOCK.equals(commandId)) {
				unlockObjects(infobase, selection, logger, monitor);
			} else if (COMMAND_COMMIT.equals(commandId)) {
				commitObjects(infobase, selection, logger, monitor);
			} else {
				return false;
			}
			StorageLockLabelDecorator.refresh();
			return true;
		} catch (IOException | CoreException | RuntimeExecutionException | InterruptedException e) {
			if (e instanceof InterruptedException) {
				Thread.currentThread().interrupt();
			}
			logger.error(e.getMessage(), e);
			return false;
		}
	}

	private void lockObjects(InfobaseReference infobase, StorageNavigatorObjectResolver.ResolvedSelection selection,
			OperationLogger logger, IProgressMonitor monitor)
			throws IOException, CoreException, RuntimeExecutionException, InterruptedException {
		Path rootDirectory = FileUtil.createTempDirectory("ZigrLock").toPath();
		Designer designer = null;
		try {
			designer = createDesigner(infobase, selection.project(), rootDirectory, logger);
			closeDesignerSession(designer, logger, monitor);
			designer.lockObjects(selection.objects(), logger);
			StorageLockStateStore.getInstance().markLocked(selection.project().getName(), selection.objectNames());
			logger.detail("Локальное состояние захвата обновлено");
		} finally {
			disposeDesigner(designer, rootDirectory, logger);
		}
	}

	private void unlockObjects(InfobaseReference infobase, StorageNavigatorObjectResolver.ResolvedSelection selection,
			OperationLogger logger, IProgressMonitor monitor)
			throws IOException, CoreException, RuntimeExecutionException, InterruptedException {
		Path rootDirectory = FileUtil.createTempDirectory("ZigrUnlock").toPath();
		Designer designer = null;
		try {
			designer = createDesigner(infobase, selection.project(), rootDirectory, logger);
			closeDesignerSession(designer, logger, monitor);
			designer.unlockObjects(selection.objects(), logger);
			StorageLockStateStore.getInstance().markUnlocked(selection.project().getName(), selection.objectNames());
			logger.detail("Локальное состояние захвата обновлено");
		} finally {
			disposeDesigner(designer, rootDirectory, logger);
		}
	}

	private void commitObjects(InfobaseReference infobase, StorageNavigatorObjectResolver.ResolvedSelection selection,
			OperationLogger logger, IProgressMonitor monitor)
			throws IOException, CoreException, RuntimeExecutionException, InterruptedException {
		Path rootDirectory = FileUtil.createTempDirectory("ZigrNavigatorCommit").toPath();
		Path exportDirectory = FileUtil.createTempDirectory("Export", rootDirectory).toPath();
		Designer designer = null;
		try {
			designer = createDesigner(infobase, selection.project(), rootDirectory, logger);
			closeDesignerSession(designer, logger, monitor);
			ensureLocked(designer, selection, logger);

			logger.step("Выгрузка выбранных объектов EDT в XML");
			monitor.subTask("Выгрузка выбранных объектов EDT в XML");
			IExportOperation exportOperation = exportOperationFactory.createExportOperation(
					exportDirectory,
					designer.getVersion(),
					new SelectedObjectsExportStrategy(),
					selection.exportObjects().toArray(new EObject[0]));
			IStatus status = exportOperation.run(new NullProgressMonitor());
			if (status.getSeverity() == IStatus.ERROR) {
				throw new CoreException(status);
			}

			Path fileList = rootDirectory.resolve("selectedFiles.txt");
			writeExportFileList(exportDirectory, fileList, logger);

			logger.step("Загрузка XML выбранных объектов в ИБ");
			monitor.subTask("Загрузка XML выбранных объектов в ИБ");
			designer.loadConfigurationFromXml(exportDirectory, fileList, logger);

			logger.step("Помещение выбранных объектов в хранилище");
			monitor.subTask("Помещение выбранных объектов в хранилище");
			Path objectsList = designer.createObjectsList(selection.objects(), "commitObjectsList.xml");
			designer.commitObjects(objectsList, getCommitComment(selection), logger);
			StorageLockStateStore.getInstance().markUnlocked(selection.project().getName(), selection.objectNames());
			logger.detail("Локальное состояние захвата обновлено после помещения");

			refreshWorkspace(logger);
		} finally {
			disposeDesigner(designer, rootDirectory, logger);
		}
	}

	private void ensureLocked(Designer designer, StorageNavigatorObjectResolver.ResolvedSelection selection,
			OperationLogger logger) throws IOException, CoreException, InterruptedException {
		boolean allLocked = true;
		for (QualifiedName objectName : selection.objectNames()) {
			if (!StorageLockStateStore.getInstance().isLocked(selection.project().getName(), objectName)) {
				allLocked = false;
				break;
			}
		}
		if (!allLocked) {
			logger.step("Предварительный захват выбранных объектов");
			designer.lockObjects(selection.objects(), logger);
			StorageLockStateStore.getInstance().markLocked(selection.project().getName(), selection.objectNames());
		}
	}

	private Designer createDesigner(InfobaseReference infobase, IProject project, Path rootDirectory,
			OperationLogger logger) throws IOException, CoreException, RuntimeExecutionException, InterruptedException {
		logger.detail("Временный каталог: " + rootDirectory);
		Designer designer = new Designer(infobase, project.getName(), rootDirectory);
		logger.detail("Цель хранилища: " + designer.getStorageTargetDescription());
		return designer;
	}

	private void closeDesignerSession(Designer designer, OperationLogger logger, IProgressMonitor monitor)
			throws CoreException, InterruptedException {
		try {
			logger.step("Закрытие активной сессии конфигуратора");
			monitor.subTask("Закрытие активной сессии конфигуратора");
			designer.closeDesignerSession();
		} catch (Exception e) {
			throw new CoreException(StorageUiPlugin.createErrorStatus(
					"Не удалось закрыть активную сессию конфигуратора", e));
		}
	}

	private void disposeDesigner(Designer designer, Path rootDirectory, OperationLogger logger) {
		if (designer != null) {
			designer.dispose();
		}
		try {
			FileUtil.deleteRecursivelyWithRetries(rootDirectory);
			logger.detail("Временный каталог удален: " + rootDirectory);
		} catch (IOException e) {
			logger.error(e.getMessage(), e);
		}
	}

	private void writeExportFileList(Path exportDirectory, Path fileList, OperationLogger logger) throws IOException {
		try (var stream = Files.walk(exportDirectory)) {
			String content = String.join(System.lineSeparator(),
					stream.filter(Files::isRegularFile)
							.map(Path::toString)
							.toList());
			Files.writeString(fileList, content);
		}
		logger.detail("Файл списка загрузки: " + fileList);
	}

	private void refreshWorkspace(OperationLogger logger) throws CoreException {
		ResourcesPlugin.getWorkspace().getRoot().refreshLocal(IResource.DEPTH_INFINITE, new NullProgressMonitor());
		logger.detail("Workspace обновлен после операции с хранилищем");
	}

	private String getCommitComment(StorageNavigatorObjectResolver.ResolvedSelection selection) {
		return "EDT: selected objects, project " + selection.project().getName()
				+ ", objects " + selection.objectNamesText();
	}

	private void refreshLockState(Shell shell, StorageNavigatorObjectResolver.ResolvedSelection selection) {
		OperationLogger logger;
		try {
			logger = OperationLogger.create();
			StorageLockStateRefreshService.RefreshResult result = StorageLockStateRefreshService.refresh(
					selection.project(), selection.objectNames(), logger);
			StorageLockLabelDecorator.refresh();
			MessageDialog.openInformation(shell, "Обновить состояние захвата",
					result.dialogMessage()
					+ System.lineSeparator()
					+ System.lineSeparator()
					+ "Журнал: " + logger.getLogFile());
		} catch (RuntimeException | IOException e) {
			StorageUiPlugin.logError(e.getMessage(), e);
			MessageDialog.openError(shell, "Обновить состояние захвата",
					"Не удалось обновить локальное состояние захвата: " + e.getMessage());
		}
	}

	private String getStartMessage(String commandId) {
		if (COMMAND_LOCK.equals(commandId)) {
			return "Старт операции захвата объектов";
		}
		if (COMMAND_UNLOCK.equals(commandId)) {
			return "Старт операции снятия захвата объектов";
		}
		if (COMMAND_COMMIT.equals(commandId)) {
			return "Старт операции помещения выбранных объектов";
		}
		return "Старт операции хранилища";
	}

	private String getTitle(String commandId) {
		if (COMMAND_LOCK.equals(commandId)) {
			return "Захватить в хранилище";
		}
		if (COMMAND_UNLOCK.equals(commandId)) {
			return "Снять захват";
		}
		if (COMMAND_COMMIT.equals(commandId)) {
			return "Поместить в хранилище";
		}
		if (COMMAND_REFRESH_LOCK_STATE.equals(commandId)) {
			return "Обновить состояние захвата";
		}
		return "Хранилище конфигурации";
	}

	private static final class SelectedObjectsExportStrategy implements IExportStrategy {
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
