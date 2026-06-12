package dev.zigr.dt.team.ui.storage;

import org.eclipse.jface.viewers.IDecoration;
import org.eclipse.jface.viewers.ILightweightLabelDecorator;
import org.eclipse.jface.viewers.ISelectionProvider;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.jface.viewers.StructuredViewer;
import org.eclipse.ui.IViewPart;
import org.eclipse.ui.IViewReference;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;

public final class StorageLockLabelDecorator extends LabelProvider implements ILightweightLabelDecorator {

	static final String ID = "dev.zigr.dt.team.ui.storage.lockLabelDecorator";
	private static final String EDT_NAVIGATOR_VIEW_ID = "com._1c.g5.v8.dt.navigator.ui.navigator";
	private static final String PROJECT_EXPLORER_VIEW_ID = "org.eclipse.ui.navigator.ProjectExplorer";

	@Override
	public void decorate(Object element, IDecoration decoration) {
		StorageNavigatorObjectResolver.ResolvedObject resolvedObject =
				StorageNavigatorObjectResolver.resolveForDecorator(element);
		if (resolvedObject == null || resolvedObject.project() == null || resolvedObject.objectName() == null) {
			return;
		}
		if (StorageLockStateStore.getInstance().isLocked(
				resolvedObject.project().getName(), resolvedObject.objectName())) {
			decoration.addSuffix(" [захвачено]");
		}
	}

	static void refresh() {
		if (PlatformUI.isWorkbenchRunning()) {
			PlatformUI.getWorkbench().getDisplay().asyncExec(() -> {
				PlatformUI.getWorkbench().getDecoratorManager().update(ID);
				refreshOpenViewers();
			});
		}
	}

	private static void refreshOpenViewers() {
		for (IWorkbenchWindow window : PlatformUI.getWorkbench().getWorkbenchWindows()) {
			for (IWorkbenchPage page : window.getPages()) {
				for (IViewReference viewReference : page.getViewReferences()) {
					if (!EDT_NAVIGATOR_VIEW_ID.equals(viewReference.getId())
							&& !PROJECT_EXPLORER_VIEW_ID.equals(viewReference.getId())) {
						continue;
					}
					IViewPart view = viewReference.getView(false);
					if (view == null) {
						continue;
					}
					ISelectionProvider selectionProvider = view.getSite().getSelectionProvider();
					if (selectionProvider instanceof StructuredViewer viewer) {
						viewer.refresh(true);
					}
				}
			}
		}
	}
}
