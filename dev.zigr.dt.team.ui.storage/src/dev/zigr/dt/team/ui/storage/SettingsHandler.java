package dev.zigr.dt.team.ui.storage;

import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.commands.IHandler;
import org.eclipse.core.commands.IHandlerListener;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.Adapters;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.ITreeSelection;
import org.eclipse.jface.viewers.TreePath;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.handlers.HandlerUtil;

import com._1c.g5.v8.dt.team.git.infobases.IGitBranchIssueDescriptor;

public class SettingsHandler implements IHandler {

	@Override
	public Object execute(ExecutionEvent event) throws ExecutionException {
		IStructuredSelection selection = HandlerUtil.getCurrentStructuredSelection(event);
		Object firstElement = selection.getFirstElement();
		IProject project = (IProject) Adapters.adapt(firstElement, IProject.class);
		Shell shell = HandlerUtil.getActiveShell(event);
		if (project == null) {
			MessageDialog.openError(shell, "Настройки хранилища", "Не удалось определить проект EDT");
			return null;
		}
		SettingsDialog dialog = new SettingsDialog(shell, project, getIssueDescriptor(selection));
		dialog.open();
		
		return null;
	}

	private IGitBranchIssueDescriptor getIssueDescriptor(IStructuredSelection selection) {
		IGitBranchIssueDescriptor result = (IGitBranchIssueDescriptor) Adapters.adapt(selection.getFirstElement(),
				IGitBranchIssueDescriptor.class);
		if (result != null) {
			return result;
		}
		if (selection instanceof ITreeSelection treeSelection) {
			for (TreePath path : treeSelection.getPaths()) {
				for (int i = path.getSegmentCount() - 1; i >= 0; i--) {
					result = (IGitBranchIssueDescriptor) Adapters.adapt(path.getSegment(i),
							IGitBranchIssueDescriptor.class);
					if (result != null) {
						return result;
					}
				}
			}
		}
		return null;
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
