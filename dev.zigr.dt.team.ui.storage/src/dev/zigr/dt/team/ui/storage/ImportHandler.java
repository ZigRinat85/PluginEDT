package dev.zigr.dt.team.ui.storage;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

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
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.dialogs.ProgressMonitorDialog;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.handlers.HandlerUtil;

import com._1c.g5.v8.dt.common.FileUtil;
import com._1c.g5.v8.dt.import_.IImportOperation;
import com._1c.g5.v8.dt.import_.IImportOperationFactory;
import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.RuntimeExecutionException;
import com._1c.g5.v8.dt.team.git.infobases.IGitBranchIssueDescriptor;
import com.google.inject.Inject;

public class ImportHandler implements IHandler {

	@Inject
	private IImportOperationFactory importOperationFactory;

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

		List<IProject> projects = getConfiguredProjects(logger);
		if (projects.isEmpty()) {
			MessageDialog.openWarning(shell, "Получить из хранилища",
					"Не найдены проекты текущего репозитория с заполненным адресом хранилища. Журнал: " + logger.getLogFile());
			return null;
		}

		boolean[] result = new boolean[] { true };
		try {
			new ProgressMonitorDialog(shell).run(true, false,
					monitor -> result[0] = pullAllProjects(projects, logger, monitor));
		} catch (InvocationTargetException e) {
			Throwable cause = e.getCause() != null ? e.getCause() : e;
			logger.error(cause.getMessage(), cause);
			result[0] = false;
		} catch (InterruptedException e) {
			logger.error(e.getMessage(), e);
			Thread.currentThread().interrupt();
			result[0] = false;
		}

		if (result[0]) {
			MessageDialog.openInformation(shell, "Получить из хранилища",
					"Операция успешно выполнена" + System.lineSeparator() + "Журнал: " + logger.getLogFile());
		} else {
			MessageDialog.openError(shell, "Получить из хранилища",
					"Операция не выполнена. Журнал: " + logger.getLogFile());
		}

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
		Path exportDirectory = FileUtil.createTempDirectory("StorageDump", rootDirectory).toPath();
		Designer designer = null;
		try {
			logger.detail("Временный каталог: " + rootDirectory);
			logger.detail("Каталог XML-выгрузки: " + exportDirectory);
			designer = new Designer(issueDescriptor, project.getName(), rootDirectory);

			logger.step("Закрытие активной сессии конфигуратора");
			monitor.subTask("Закрытие активной сессии конфигуратора");
			designer.closeDesignerSession();

			logger.step("Получение последней версии из хранилища в ИБ");
			monitor.subTask("Получение последней версии из хранилища");
			designer.updateConfigurationFromRepository(logger);

			logger.step("Выгрузка конфигурации ИБ в XML");
			monitor.subTask("Выгрузка конфигурации ИБ в XML");
			designer.dumpConfigurationToXml(exportDirectory, logger);

			logger.step("Импорт XML в EDT-проект");
			monitor.subTask("Импорт XML в EDT-проект");
			importXmlToProject(designer.getProject(), exportDirectory, logger);

			logger.step("Обновление состояния синхронизации EDT");
			monitor.subTask("Обновление состояния синхронизации EDT");
			designer.updateProjectSynchronizationState(exportDirectory, logger);
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

	private void importXmlToProject(IProject project, Path exportDirectory, OperationLogger logger)
			throws InvocationTargetException, InterruptedException, CoreException {
		IImportOperation importOperation = importOperationFactory.createImportObjectsOperation(project, exportDirectory);
		importOperation.setRefreshProject(true);
		importOperation.run(new NullProgressMonitor());
		IStatus status = importOperation.getStatus();
		logStatus("Импорт XML в EDT", status, logger);
		if (status != null && status.matches(IStatus.ERROR | IStatus.CANCEL)) {
			throw new CoreException(status);
		}
	}

	private void logStatus(String title, IStatus status, OperationLogger logger) {
		if (status == null) {
			logger.detail(title + ": статус не возвращен");
			return;
		}
		logger.detail(title + ": severity=" + status.getSeverity() + ", message=" + status.getMessage());
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
