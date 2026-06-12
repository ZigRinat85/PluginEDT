package dev.zigr.dt.team.ui.storage;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.widgets.Shell;

import com._1c.g5.v8.dt.common.FileUtil;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.InfobaseChangesResolutionResult;
import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.RuntimeExecutionException;
import com._1c.g5.v8.dt.team.git.infobases.IGitBranchIssueDescriptor;

public final class StorageConnector {

	private StorageConnector() {
	}

	public static boolean connect(Shell shell, IGitBranchIssueDescriptor issueDescriptor, IProject project) {
		if (project == null) {
			MessageDialog.openError(shell, "Подключить к хранилищу", "Не удалось определить проект EDT");
			return false;
		}
		InfobaseReference infobase;
		try {
			infobase = issueDescriptor != null ? issueDescriptor.getInfobase() : InfobaseResolver.getDefaultInfobase(project);
		} catch (CoreException e) {
			StorageUiPlugin.logError(e.getMessage(), e);
			MessageDialog.openError(shell, "Подключить к хранилищу", e.getMessage());
			return false;
		}
		if (!confirmConnect(shell, project, infobase)) {
			return false;
		}

		OperationLogger logger;
		try {
			logger = OperationLogger.create();
		} catch (IOException e) {
			StorageUiPlugin.logError(e.getMessage(), e);
			MessageDialog.openError(shell, "Ошибка", "Не удалось создать журнал операции");
			return false;
		}

		logger.step("Старт операции подключения к хранилищу");
		logger.detail("ИБ: " + infobase.getName());
		if (issueDescriptor != null) {
			logger.detail("Ветка хранилища: " + issueDescriptor.getBranch().getName());
		}
		logger.detail("Проект: " + project.getName());

		OperationLogDialog dialog = new OperationLogDialog(shell, "Подключить к хранилищу", logger,
				monitor -> connectProject(infobase, project, logger, monitor));
		dialog.open();
		return dialog.getResult();
	}

	private static boolean confirmConnect(Shell shell, IProject project, InfobaseReference infobase) {
		String message = "Подключить к хранилищу проект " + project.getName() + "?"
				+ System.lineSeparator()
				+ "ИБ: " + infobase.getName()
				+ System.lineSeparator() + System.lineSeparator()
				+ "Текущая конфигурация ИБ или расширения может быть заменена последней версией из хранилища. "
				+ "Перед запуском проверьте адрес, логин и пароль хранилища.";
		return MessageDialog.openQuestion(shell, "Подключить к хранилищу", message);
	}

	private static boolean connectProject(InfobaseReference infobase, IProject project,
			OperationLogger logger, IProgressMonitor monitor)
			throws IOException, CoreException, RuntimeExecutionException, InterruptedException {
		Path rootDirectory = FileUtil.createTempDirectory("ZigrConnect").toPath();
		Designer designer = null;
		boolean success = false;
		try {
			logger.detail("Временный каталог: " + rootDirectory);
			designer = new Designer(infobase, project.getName(), rootDirectory);
			logger.detail("EDT-проект: " + designer.getProject().getName());
			logger.detail("Цель хранилища: " + designer.getStorageTargetDescription());
			if (!designer.getResolvedExtensionName().isEmpty()) {
				logger.detail("Имя расширения EDT: " + designer.getResolvedExtensionName());
			}

			logger.step("Закрытие активной сессии конфигуратора");
			monitor.subTask("Закрытие активной сессии конфигуратора");
			designer.closeDesignerSession();

			logger.step("Подключение конфигурации ИБ к хранилищу");
			monitor.subTask("Подключение конфигурации ИБ к хранилищу");
			List<String> updatedObjects = designer.connectToRepository(logger);
			logger.detail("Объектов, указанных хранилищем как полученные при подключении: " + updatedObjects.size());

			logger.step("Обновление конфигурации базы данных");
			monitor.subTask("Обновление конфигурации базы данных");
			designer.updateDatabaseConfiguration(logger);

			logger.step("Получение изменений из ИБ в EDT штатным механизмом");
			monitor.subTask("Получение изменений из ИБ в EDT");
			InfobaseChangesResolutionResult syncResult = designer.retrieveConfigurationChangesFromInfobase(logger, monitor);
			logger.detail("Результат EDT-импорта после подключения: " + syncResult);
			if (syncResult == InfobaseChangesResolutionResult.NO_CHANGES && !updatedObjects.isEmpty()) {
				throw new CoreException(StorageUiPlugin.createErrorStatus(
						"EDT не импортировала ожидаемые изменения из ИБ после подключения к хранилищу: штатный API вернул NO_CHANGES, ожидаемых объектов="
								+ updatedObjects.size()));
			}
			success = true;
			StorageUiPlugin.logInfo("Операция подключения к хранилищу выполнена. Проект=" + project.getName());
			return true;
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

}
