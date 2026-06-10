package dev.zigr.dt.team.ui.storage;

import java.io.IOException;

import org.eclipse.core.resources.IProject;
import org.eclipse.equinox.security.storage.StorageException;
import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;
import org.osgi.service.prefs.BackingStoreException;

import com._1c.g5.v8.dt.team.git.infobases.IGitBranchIssueDescriptor;

public class SettingsDialog extends Dialog {
	private IProject project;
	private IGitBranchIssueDescriptor issueDescriptor;
	private Settings storageSettings;
	private Text txtAddress;
	private Text txtUser;
	private Text txtPassword;
	private Text txtCommitCommentTemplate;
	private Button btnExportMDWithMDO;
	private Button btnPushIfConfigurationChanged;

	protected SettingsDialog(Shell parentShell, IProject project, IGitBranchIssueDescriptor issueDescriptor) {
		super(parentShell);

		this.project = project;
		this.issueDescriptor = issueDescriptor;
		storageSettings = new Settings(project.getName());
	}

	@Override
	protected Control createDialogArea(Composite parent) {
		Composite container = (Composite) super.createDialogArea(parent);
		GridLayout layout = new GridLayout(2, false);
		layout.marginRight = 5;
		layout.marginLeft = 10;
		container.setLayout(layout);
		
		// address
		Label lblAddress = new Label(container, SWT.NONE);
		lblAddress.setText("Адрес хранилища:");
		txtAddress = new Text(container, SWT.BORDER);
		txtAddress.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
		txtAddress.setText(storageSettings.getAddress());
		
		// user
		Label lblUser = new Label(container, SWT.NONE);
		lblUser.setText("Логин хранилища:");
		txtUser = new Text(container, SWT.BORDER);
		txtUser.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
		txtUser.setText(storageSettings.getUser());
		
		// password
		Label lblPassword = new Label(container, SWT.NONE);
		lblPassword.setText("Пароль хранилища:");
		txtPassword = new Text(container, SWT.BORDER| SWT.PASSWORD);
		txtPassword.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
		txtPassword.setText(storageSettings.getPassword());

		Label lblConnect = new Label(container, SWT.NONE);
		lblConnect.setText("");
		Button btnConnect = new Button(container, SWT.PUSH);
		btnConnect.setText("Подключиться");
		btnConnect.setLayoutData(new GridData(SWT.RIGHT, SWT.CENTER, false, false));
		btnConnect.setEnabled(issueDescriptor != null);
		if (issueDescriptor == null) {
			btnConnect.setToolTipText("Откройте настройки проекта из панели Разработка, чтобы определить ИБ");
		}
		btnConnect.addListener(SWT.Selection, event -> connectPressed());
		
		// exportMDWithMDO
		btnExportMDWithMDO = new Button(container, SWT.CHECK);
		btnExportMDWithMDO.setLayoutData(new GridData(SWT.TRAIL, SWT.CENTER, true, false));
		btnExportMDWithMDO.setSelection(storageSettings.getExportMDWithMDO());
		Label lblExportMDWithMDO = new Label(container, SWT.NONE);
		lblExportMDWithMDO.setText("При изменении .mdo всегда захватывать подчиненные формы/шаблоны");
		
		// pushIfConfigurationChanged
		btnPushIfConfigurationChanged = new Button(container, SWT.CHECK);
		btnPushIfConfigurationChanged.setLayoutData(new GridData(SWT.TRAIL, SWT.CENTER, true, false));
		btnPushIfConfigurationChanged.setSelection(storageSettings.getPushIfConfigurationChanged());
		Label lblPushIfConfigurationChanged = new Label(container, SWT.NONE);
		lblPushIfConfigurationChanged.setText("Помещать даже если конфигурации различаются");

		// commitCommentTemplate
		Label lblCommitCommentTemplate = new Label(container, SWT.NONE);
		lblCommitCommentTemplate.setText("Шаблон комментария:");
		txtCommitCommentTemplate = new Text(container, SWT.BORDER);
		GridData commitCommentTemplateGridData = new GridData(SWT.FILL, SWT.CENTER, true, false);
		commitCommentTemplateGridData.widthHint = 620;
		txtCommitCommentTemplate.setLayoutData(commitCommentTemplateGridData);
		txtCommitCommentTemplate.setText(storageSettings.getCommitCommentTemplate());

		Label lblCommitCommentTemplateHelp = new Label(container, SWT.WRAP);
		lblCommitCommentTemplateHelp.setText("Доступные поля: {branch}, {storageBranch}, {project}, {changedFiles}, {fileCount}, {files}, {infobase}");
		GridData commitCommentTemplateHelpGridData = new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1);
		commitCommentTemplateHelpGridData.widthHint = 620;
		lblCommitCommentTemplateHelp.setLayoutData(commitCommentTemplateHelpGridData);
		
		return container;
	}

	@Override
	protected boolean isResizable() {
		return true;
	}

	@Override
	protected void createButtonsForButtonBar(Composite parent) {
		createButton(parent, IDialogConstants.OK_ID, IDialogConstants.OK_LABEL, true);
		createButton(parent, IDialogConstants.CANCEL_ID, IDialogConstants.CANCEL_LABEL, false);
	}

	@Override
	protected void okPressed() {
		if (saveSettings()) {
			super.okPressed();
		}
	}

	private void connectPressed() {
		if (!saveSettings()) {
			return;
		}
		if (StorageConnector.connect(getShell(), issueDescriptor, project)) {
			super.okPressed();
		}
	}

	private boolean saveSettings() {
		try {
			storageSettings.setAddress(txtAddress.getText());
			storageSettings.setUser(txtUser.getText());
			storageSettings.setPassword(txtPassword.getText());
			storageSettings.setExportMDWithMDO(btnExportMDWithMDO.getSelection());
			storageSettings.setPushIfConfigurationChanged(btnPushIfConfigurationChanged.getSelection());
			storageSettings.setCommitCommentTemplate(txtCommitCommentTemplate.getText());
			storageSettings.flush();
			return true;
		} catch (StorageException | BackingStoreException | IOException e) {
			StorageUiPlugin.logError(e.getMessage(), e);
			MessageDialog.openError(getShell(), "Ошибка", "Не удалось записать настройки");
			return false;
		}
	}
}
