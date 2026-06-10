package dev.zigr.dt.team.ui.storage;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.commands.IHandler;
import org.eclipse.core.commands.IHandlerListener;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.Adapters;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.handlers.HandlerUtil;
import org.eclipse.xtext.naming.QualifiedName;

import com._1c.g5.v8.dt.common.FileUtil;
import com._1c.g5.v8.dt.core.filesystem.IQualifiedNameFilePathConverter;
import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.RuntimeExecutionException;
import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import com._1c.g5.v8.dt.team.git.infobases.IGitBranchIssueDescriptor;
import com.google.inject.Inject;

public class LockUncommittedHandler implements IHandler {

	@Inject
	private IQualifiedNameFilePathConverter qualifiedNameFilePathConverter;

	@Override
	public Object execute(ExecutionEvent event) throws ExecutionException {
		Shell shell = HandlerUtil.getActiveShell(event);
		IStructuredSelection selection = HandlerUtil.getCurrentStructuredSelection(event);
		Object firstElement = selection.getFirstElement();
		IGitBranchIssueDescriptor issueDescriptor =
				(IGitBranchIssueDescriptor)Adapters.adapt(firstElement, IGitBranchIssueDescriptor.class);
		if (issueDescriptor == null) {
			MessageDialog.openError(shell, "Захватить незакоммиченное",
					"Не удалось определить выбранную ветку хранилища");
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

		logger.step("Старт операции захвата незакоммиченных изменений");
		logger.detail("ИБ: " + issueDescriptor.getInfobase().getName());
		logger.detail("Git-репозиторий: " + issueDescriptor.getRepository().getWorkTree());

		Map<IProject, Map<QualifiedName, Boolean>> objectsByProject;
		try {
			objectsByProject = StorageChangedObjectsResolver.getUncommittedLockObjects(
					issueDescriptor.getRepository(), qualifiedNameFilePathConverter, logger);
		} catch (CoreException | GitAPIException | IOException e) {
			StorageUiPlugin.logError(e.getMessage(), e);
			MessageDialog.openError(shell, "Захватить незакоммиченное",
					"Не удалось определить объекты для захвата: " + e.getMessage()
					+ System.lineSeparator() + System.lineSeparator()
					+ "Журнал: " + logger.getLogFile());
			return null;
		}

		if (objectsByProject.isEmpty()) {
			MessageDialog.openInformation(shell, "Захватить незакоммиченное",
					"Среди незакоммиченных изменений не найдено объектов EDT с заполненными настройками хранилища."
					+ System.lineSeparator() + System.lineSeparator()
					+ "Журнал: " + logger.getLogFile());
			return null;
		}

		if (!confirm(shell, objectsByProject)) {
			return null;
		}

		InfobaseReference infobase = issueDescriptor.getInfobase();
		OperationLogDialog dialog = new OperationLogDialog(shell, "Захватить незакоммиченное", logger,
				monitor -> lockObjects(infobase, objectsByProject, logger, monitor));
		dialog.open();
		return null;
	}

	private boolean confirm(Shell shell, Map<IProject, Map<QualifiedName, Boolean>> objectsByProject) {
		StringBuilder message = new StringBuilder("Захватить объекты из незакоммиченных Git-изменений?");
		int objectCount = 0;
		for (Map.Entry<IProject, Map<QualifiedName, Boolean>> projectEntry : objectsByProject.entrySet()) {
			message.append(System.lineSeparator())
					.append(System.lineSeparator())
					.append(projectEntry.getKey().getName())
					.append(":");
			for (QualifiedName objectName : projectEntry.getValue().keySet()) {
				objectCount++;
				message.append(System.lineSeparator()).append("  ").append(objectName);
			}
		}
		message.append(System.lineSeparator())
				.append(System.lineSeparator())
				.append("Всего объектов: ")
				.append(objectCount);
		return MessageDialog.openQuestion(shell, "Захватить незакоммиченное", message.toString());
	}

	private boolean lockObjects(InfobaseReference infobase, Map<IProject, Map<QualifiedName, Boolean>> objectsByProject,
			OperationLogger logger, IProgressMonitor monitor) {
		boolean result = true;
		monitor.beginTask("Захват незакоммиченных изменений", objectsByProject.size());
		for (Map.Entry<IProject, Map<QualifiedName, Boolean>> projectEntry : objectsByProject.entrySet()) {
			IProject project = projectEntry.getKey();
			Map<QualifiedName, Boolean> lockObjects = projectEntry.getValue();
			logger.step("Захват объектов проекта " + project.getName());
			monitor.subTask("Проект " + project.getName());
			try {
				lockProjectObjects(infobase, project, lockObjects, logger, monitor);
			} catch (IOException | CoreException | RuntimeExecutionException | InterruptedException e) {
				if (e instanceof InterruptedException) {
					Thread.currentThread().interrupt();
				}
				logger.error(e.getMessage(), e);
				result = false;
				break;
			} finally {
				monitor.worked(1);
			}
		}
		monitor.done();
		StorageLockLabelDecorator.refresh();
		return result;
	}

	private void lockProjectObjects(InfobaseReference infobase, IProject project,
			Map<QualifiedName, Boolean> lockObjects, OperationLogger logger, IProgressMonitor monitor)
			throws IOException, CoreException, RuntimeExecutionException, InterruptedException {
		Path rootDirectory = FileUtil.createTempDirectory("ZigrLockUncommitted").toPath();
		Designer designer = null;
		try {
			logger.detail("Временный каталог: " + rootDirectory);
			designer = new Designer(infobase, project.getName(), rootDirectory);
			logger.detail("Цель хранилища: " + designer.getStorageTargetDescription());

			logger.step("Закрытие активной сессии конфигуратора");
			monitor.subTask("Закрытие активной сессии конфигуратора");
			designer.closeDesignerSession();

			logger.step("Захват объектов в хранилище");
			monitor.subTask("Захват объектов в хранилище");
			designer.lockObjects(lockObjects, logger);

			StorageLockStateStore.getInstance().markLocked(project.getName(), lockObjects.keySet());
			logger.detail("Локальное состояние захвата обновлено: объектов=" + lockObjects.size());
		} finally {
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
