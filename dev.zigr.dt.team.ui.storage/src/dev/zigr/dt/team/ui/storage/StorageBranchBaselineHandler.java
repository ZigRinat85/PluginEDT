package dev.zigr.dt.team.ui.storage;

import java.io.IOException;
import java.util.concurrent.CancellationException;

import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.commands.IHandler;
import org.eclipse.core.commands.IHandlerListener;
import org.eclipse.core.runtime.Adapters;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.RefUpdate.Result;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.handlers.HandlerUtil;

import com._1c.g5.v8.dt.team.git.infobases.IGitBranchIssueDescriptor;

public class StorageBranchBaselineHandler implements IHandler {

	private Shell shell;

	@Override
	public Object execute(ExecutionEvent event) throws ExecutionException {
		shell = HandlerUtil.getActiveShell(event);
		IStructuredSelection selection = HandlerUtil.getCurrentStructuredSelection(event);
		Object firstElement = selection.getFirstElement();
		IGitBranchIssueDescriptor issueDescriptor = (IGitBranchIssueDescriptor) Adapters.adapt(firstElement,
				IGitBranchIssueDescriptor.class);
		if (issueDescriptor == null) {
			MessageDialog.openError(shell, "Обновить ветку хранилища",
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

		logger.step("Старт обновления ветки хранилища текущей веткой");
		logger.detail("ИБ: " + issueDescriptor.getInfobase().getName());
		logger.detail("Ветка хранилища: " + issueDescriptor.getBranch().getName());

		try {
			updateStorageBranchBaseline(issueDescriptor, logger);
			MessageDialog.openInformation(shell, "Обновить ветку хранилища",
					"Ветка хранилища обновлена до текущей ветки" + System.lineSeparator()
							+ "Журнал: " + logger.getLogFile());
		} catch (CancellationException e) {
			logger.detail(e.getMessage());
			MessageDialog.openInformation(shell, "Обновить ветку хранилища",
					e.getMessage() + System.lineSeparator() + "Журнал: " + logger.getLogFile());
		} catch (IOException e) {
			logger.error(e.getMessage(), e);
			MessageDialog.openError(shell, "Обновить ветку хранилища",
					"Операция не выполнена. " + e.getMessage() + System.lineSeparator()
							+ "Журнал: " + logger.getLogFile());
		}

		return null;
	}

	private void updateStorageBranchBaseline(IGitBranchIssueDescriptor issueDescriptor, OperationLogger logger)
			throws IOException {
		Repository repository = issueDescriptor.getRepository();
		String storageBranchRef = issueDescriptor.getBranch().getName();
		String currentBranchRef = repository.getFullBranch();

		logger.detail("Рабочий каталог Git: " + repository.getWorkTree());
		logger.detail("Текущая ветка Git: " + currentBranchRef);
		logger.detail("Ветка хранилища Git: " + storageBranchRef);

		if (storageBranchRef == null || storageBranchRef.isBlank()) {
			throw new IOException("Не определена выбранная ветка хранилища");
		}
		if (currentBranchRef == null || !currentBranchRef.startsWith(Constants.R_HEADS)) {
			throw new IOException("Текущий Git HEAD не является локальной веткой");
		}
		if (storageBranchRef.equals(currentBranchRef)) {
			throw new IOException("Выбранная ветка хранилища уже является текущей веткой: "
					+ Repository.shortenRefName(currentBranchRef));
		}
		if (!storageBranchRef.startsWith(Constants.R_HEADS)) {
			throw new IOException("Ветка хранилища не является локальной веткой: " + storageBranchRef);
		}
		String storageBranch = Repository.shortenRefName(storageBranchRef);
		String currentBranch = Repository.shortenRefName(currentBranchRef);

		ObjectId storageCommitId = repository.resolve(storageBranchRef);
		ObjectId currentCommitId = repository.resolve(currentBranchRef);
		if (storageCommitId == null) {
			throw new IOException("Не найден коммит ветки хранилища: " + storageBranchRef);
		}
		if (currentCommitId == null) {
			throw new IOException("Не найден коммит текущей ветки: " + currentBranchRef);
		}

		try (RevWalk walk = new RevWalk(repository)) {
			RevCommit storageCommit = walk.parseCommit(storageCommitId);
			RevCommit currentCommit = walk.parseCommit(currentCommitId);
			logger.detail("Коммит ветки хранилища: " + storageCommit.getName());
			logger.detail("Коммит текущей ветки: " + currentCommit.getName());

			if (storageCommit.equals(currentCommit)) {
				logger.detail("Ветка хранилища уже указывает на текущий коммит");
				return;
			}
			if (!walk.isMergedInto(storageCommit, currentCommit)) {
				throw new IOException("Нельзя безопасно обновить " + storageBranch + " до " + currentBranch
						+ ": ветка хранилища не является предком текущей ветки");
			}

			boolean confirmed = MessageDialog.openQuestion(shell, "Обновить ветку хранилища",
					"Передвинуть ветку " + storageBranch + " на текущий коммит ветки " + currentBranch + "?"
							+ System.lineSeparator() + System.lineSeparator()
							+ "Используйте это после успешного помещения изменений в хранилище 1С, чтобы следующий раз сравнивались только новые изменения.");
			if (!confirmed) {
				throw new CancellationException("Операция отменена пользователем");
			}

			RefUpdate update = repository.updateRef(storageBranchRef);
			update.setExpectedOldObjectId(storageCommitId);
			update.setNewObjectId(currentCommitId);
			update.setRefLogMessage("Update storage branch baseline from " + currentBranch, false);
			Result result = update.update(walk);
			logger.detail("Результат обновления ветки Git: " + result);
			if (result != Result.FAST_FORWARD && result != Result.NO_CHANGE) {
				throw new IOException("Git не обновил ветку " + storageBranch + ": " + result);
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
