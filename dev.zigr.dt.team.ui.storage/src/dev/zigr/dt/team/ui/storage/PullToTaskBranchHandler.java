package dev.zigr.dt.team.ui.storage;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

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
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jface.dialogs.InputDialog;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.window.Window;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.handlers.HandlerUtil;

import com._1c.g5.v8.dt.common.FileUtil;
import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.RuntimeExecutionException;
import com._1c.g5.v8.dt.team.git.infobases.IGitBranchIssueDescriptor;

public class PullToTaskBranchHandler implements IHandler {

	@Override
	public Object execute(ExecutionEvent event) throws ExecutionException {
		Shell shell = HandlerUtil.getActiveShell(event);
		Object firstElement = HandlerUtil.getCurrentStructuredSelection(event).getFirstElement();
		IGitBranchIssueDescriptor issueDescriptor = (IGitBranchIssueDescriptor) Adapters.adapt(firstElement,
				IGitBranchIssueDescriptor.class);
		if (issueDescriptor == null) {
			MessageDialog.openError(shell, "Получить из хранилища в задачу",
					"Не удалось определить выбранную ветку хранилища");
			return null;
		}

		String taskBranch = requestTaskBranch(shell);
		if (taskBranch == null) {
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
		logger.step("Старт операции получения из хранилища в ветку задачи");
		logger.detail("ИБ: " + issueDescriptor.getInfobase().getName());
		logger.detail("Ветка хранилища: " + issueDescriptor.getBranch().getName());
		logger.detail("Ветка задачи: " + taskBranch);
		logger.detail("Выбранный элемент: " + firstElement.getClass().getName());

		List<IProject> projects = StoragePullService.getConfiguredProjects(issueDescriptor, logger);
		if (projects.isEmpty()) {
			MessageDialog.openWarning(shell, "Получить из хранилища в задачу",
					"Не найдены проекты текущего репозитория с заполненным адресом хранилища. Журнал: "
							+ logger.getLogFile());
			return null;
		}

		OperationLogDialog dialog = new OperationLogDialog(shell, "Получить из хранилища в задачу", logger,
				monitor -> pullAndCheckout(issueDescriptor, projects, taskBranch, logger, monitor));
		dialog.open();

		return null;
	}

	private String requestTaskBranch(Shell shell) {
		InputDialog dialog = new InputDialog(shell, "Номер задачи", "Введите номер задачи:", "",
				value -> validateBranchName(value));
		if (dialog.open() != Window.OK) {
			return null;
		}
		return dialog.getValue().trim();
	}

	private String validateBranchName(String value) {
		String branchName = value == null ? "" : value.trim();
		if (branchName.isEmpty()) {
			return "Введите номер задачи";
		}
		if (branchName.startsWith(Constants.R_HEADS)) {
			return "Введите короткое имя ветки без refs/heads/";
		}
		if (!Repository.isValidRefName(Constants.R_HEADS + branchName)) {
			return "Недопустимое имя Git-ветки";
		}
		return null;
	}

	private boolean pullAndCheckout(IGitBranchIssueDescriptor issueDescriptor, List<IProject> projects,
			String taskBranch, OperationLogger logger, IProgressMonitor monitor) {
		boolean pullResult = StoragePullService.pullAllProjects(issueDescriptor, projects, logger, monitor);
		if (!pullResult) {
			return false;
		}

		try {
			closeDesignerSessions(issueDescriptor, projects, logger, monitor);
			checkoutTaskBranch(issueDescriptor.getRepository(), taskBranch, logger);
			return true;
		} catch (IOException | CoreException | RuntimeExecutionException | InterruptedException | GitAPIException e) {
			if (e instanceof InterruptedException) {
				Thread.currentThread().interrupt();
			}
			logger.error(e.getMessage(), e);
			return false;
		}
	}

	private void closeDesignerSessions(IGitBranchIssueDescriptor issueDescriptor, List<IProject> projects,
			OperationLogger logger, IProgressMonitor monitor)
			throws IOException, CoreException, RuntimeExecutionException, InterruptedException {
		logger.step("Закрытие активной сессии конфигуратора перед переключением Git-ветки");
		if (monitor != null) {
			monitor.subTask("Закрытие активной сессии конфигуратора");
		}
		Path rootDirectory = FileUtil.createTempDirectory("ZigrCheckout").toPath();
		try {
			for (IProject project : projects) {
				Designer designer = null;
				try {
					designer = new Designer(issueDescriptor, project.getName(), rootDirectory);
					designer.closeDesignerSession();
					logger.detail("Сессия конфигуратора закрыта для проекта " + project.getName());
				} finally {
					if (designer != null) {
						designer.dispose();
					}
				}
			}
		} finally {
			FileUtil.deleteRecursivelyWithRetries(rootDirectory);
		}
	}

	private void checkoutTaskBranch(Repository repository, String taskBranch, OperationLogger logger)
			throws IOException, GitAPIException, CoreException {
		String currentBranchRef = repository.getFullBranch();
		String taskBranchRef = Constants.R_HEADS + taskBranch;
		logger.step("Переход на ветку задачи " + taskBranch);
		logger.detail("Текущая ветка Git: " + currentBranchRef);
		logger.detail("Целевая ветка Git: " + taskBranchRef);

		try (Git git = new Git(repository)) {
			logGitStatus("Состояние Git перед переключением ветки", git.status().call(), logger);
			if (taskBranchRef.equals(currentBranchRef)) {
				logger.detail("Текущая ветка уже соответствует номеру задачи");
				commitCurrentBranchChanges(git, taskBranch, logger);
				refreshWorkspace(logger);
				return;
			}

			commitCurrentBranchChanges(git, taskBranch, logger);
			Ref localBranch = repository.exactRef(taskBranchRef);
			if (localBranch == null) {
				logger.detail("Локальная ветка не найдена, будет создана: " + taskBranch);
				git.checkout().setCreateBranch(true).setName(taskBranch).call();
			} else {
				git.checkout().setName(taskBranch).call();
			}
			refreshWorkspace(logger);
			logger.detail("Активная ветка Git после переключения: " + repository.getFullBranch());
		}
	}

	private void commitCurrentBranchChanges(Git git, String taskBranch, OperationLogger logger)
			throws GitAPIException, CoreException {
		logger.step("Фиксация изменений Git перед переходом на ветку задачи");
		Status status = git.status().call();
		logGitStatus("Состояние Git перед фиксацией", status, logger);
		if (!status.getConflicting().isEmpty()) {
			throw new CoreException(StorageUiPlugin.createErrorStatus(
					"Нельзя перейти на ветку задачи: в рабочем каталоге Git есть конфликты"));
		}
		if (status.isClean()) {
			logger.detail("Изменений Git для фиксации нет");
			return;
		}

		git.add().addFilepattern(".").call();
		git.add().addFilepattern(".").setUpdate(true).call();
		Status stagedStatus = git.status().call();
		logGitStatus("Состояние Git после добавления файлов в индекс", stagedStatus, logger);
		if (!hasStagedChanges(stagedStatus)) {
			logger.detail("После добавления файлов в индекс нет изменений, доступных для коммита");
			return;
		}

		RevCommit commit = git.commit()
				.setMessage("Update from storage before task branch " + taskBranch)
				.call();
		logger.detail("Коммит перед переходом на ветку задачи: " + commit.getName());
	}

	private boolean hasStagedChanges(Status status) {
		return !status.getAdded().isEmpty()
				|| !status.getChanged().isEmpty()
				|| !status.getRemoved().isEmpty();
	}

	private void logGitStatus(String title, Status status, OperationLogger logger) {
		logger.detail(title + ": clean=" + status.isClean()
				+ ", added=" + status.getAdded().size()
				+ ", changed=" + status.getChanged().size()
				+ ", modified=" + status.getModified().size()
				+ ", removed=" + status.getRemoved().size()
				+ ", missing=" + status.getMissing().size()
				+ ", untracked=" + status.getUntracked().size()
				+ ", conflicting=" + status.getConflicting().size());
	}

	private void refreshWorkspace(OperationLogger logger) throws CoreException {
		ResourcesPlugin.getWorkspace().getRoot().refreshLocal(IResource.DEPTH_INFINITE, new NullProgressMonitor());
		logger.detail("Workspace обновлен после Git-операции");
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
