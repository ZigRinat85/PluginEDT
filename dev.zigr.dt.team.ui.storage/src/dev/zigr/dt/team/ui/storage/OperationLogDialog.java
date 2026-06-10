package dev.zigr.dt.team.ui.storage;

import java.io.IOException;
import java.nio.file.Files;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;

public class OperationLogDialog extends Dialog {

	@FunctionalInterface
	public interface Operation {
		boolean run(IProgressMonitor monitor) throws Exception;
	}

	private final String title;
	private final OperationLogger logger;
	private final Operation operation;
	private final OperationLogger.Listener listener = this::appendLogLine;

	private Text logText;
	private Label statusLabel;
	private volatile boolean running;
	private boolean started;
	private boolean result;

	public OperationLogDialog(Shell parentShell, String title, OperationLogger logger, Operation operation) {
		super(parentShell);
		this.title = title;
		this.logger = logger;
		this.operation = operation;
		setShellStyle(getShellStyle() | SWT.RESIZE);
	}

	@Override
	public int open() {
		logger.addListener(listener);
		try {
			return super.open();
		} finally {
			logger.removeListener(listener);
		}
	}

	@Override
	public void create() {
		super.create();
		Button closeButton = getButton(IDialogConstants.OK_ID);
		if (closeButton != null) {
			closeButton.setEnabled(false);
		}
		getShell().getDisplay().asyncExec(this::startOperation);
	}

	@Override
	protected void configureShell(Shell newShell) {
		super.configureShell(newShell);
		newShell.setText(title);
	}

	@Override
	protected Control createDialogArea(Composite parent) {
		Composite container = (Composite)super.createDialogArea(parent);
		container.setLayout(new GridLayout(1, false));

		statusLabel = new Label(container, SWT.NONE);
		statusLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
		statusLabel.setText("Операция выполняется...");

		logText = new Text(container, SWT.BORDER | SWT.MULTI | SWT.READ_ONLY | SWT.V_SCROLL | SWT.H_SCROLL);
		logText.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
		logText.setText(readExistingLog());
		logText.setSelection(logText.getCharCount());

		return container;
	}

	@Override
	protected void createButtonsForButtonBar(Composite parent) {
		createButton(parent, IDialogConstants.OK_ID, "Закрыть", true);
	}

	@Override
	protected Point getInitialSize() {
		return new Point(920, 620);
	}

	@Override
	protected void okPressed() {
		if (!running) {
			super.okPressed();
		}
	}

	@Override
	protected void cancelPressed() {
		if (!running) {
			super.cancelPressed();
		}
	}

	@Override
	public boolean close() {
		if (running) {
			return false;
		}
		return super.close();
	}

	public boolean getResult() {
		return result;
	}

	private void startOperation() {
		if (started || getShell() == null || getShell().isDisposed()) {
			return;
		}
		started = true;
		running = true;
		Thread worker = new Thread(() -> {
			boolean operationResult = false;
			try {
				operationResult = operation.run(new NullProgressMonitor());
			} catch (Throwable e) {
				logger.error(e.getMessage(), e);
			}
			boolean finalResult = operationResult;
			Display display = Display.getDefault();
			display.asyncExec(() -> finishOperation(finalResult));
		}, "Configuration repository operation");
		worker.setDaemon(true);
		worker.start();
	}

	private void finishOperation(boolean operationResult) {
		if (getShell() == null || getShell().isDisposed()) {
			return;
		}
		result = operationResult;
		running = false;
		statusLabel.setText(operationResult ? "Операция успешно выполнена" : "Операция не выполнена");
		Button closeButton = getButton(IDialogConstants.OK_ID);
		if (closeButton != null && !closeButton.isDisposed()) {
			closeButton.setEnabled(true);
			closeButton.setFocus();
		}
	}

	private void appendLogLine(String line) {
		Display display = Display.getDefault();
		display.asyncExec(() -> {
			if (logText == null || logText.isDisposed()) {
				return;
			}
			logText.append(line);
			logText.setSelection(logText.getCharCount());
			updateStatusFromLine(line);
		});
	}

	private void updateStatusFromLine(String line) {
		if (statusLabel == null || statusLabel.isDisposed() || !line.contains("STEP ")) {
			return;
		}
		String trimmedLine = line.trim();
		int stepIndex = trimmedLine.indexOf("STEP ");
		if (stepIndex >= 0) {
			statusLabel.setText(trimmedLine.substring(stepIndex));
		}
	}

	private String readExistingLog() {
		try {
			if (Files.exists(logger.getLogFile())) {
				return Files.readString(logger.getLogFile());
			}
		} catch (IOException e) {
			return "Не удалось прочитать журнал операции: " + e.getMessage() + System.lineSeparator();
		}
		return "";
	}
}
