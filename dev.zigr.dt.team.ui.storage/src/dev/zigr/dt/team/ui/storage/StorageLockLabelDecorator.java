package dev.zigr.dt.team.ui.storage;

import org.eclipse.jface.viewers.IDecoration;
import org.eclipse.jface.viewers.ILightweightLabelDecorator;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.ui.PlatformUI;

public final class StorageLockLabelDecorator extends LabelProvider implements ILightweightLabelDecorator {

	static final String ID = "dev.zigr.dt.team.ui.storage.lockLabelDecorator";

	@Override
	public void decorate(Object element, IDecoration decoration) {
		StorageNavigatorObjectResolver.ResolvedObject resolvedObject =
				StorageNavigatorObjectResolver.resolveForDecorator(element);
		if (resolvedObject == null || resolvedObject.project() == null || resolvedObject.objectName() == null) {
			return;
		}
		if (StorageLockStateStore.getInstance().isLocked(
				resolvedObject.project().getName(), resolvedObject.objectName())) {
			decoration.addSuffix(" [захвачен]");
		}
	}

	static void refresh() {
		if (PlatformUI.isWorkbenchRunning()) {
			PlatformUI.getWorkbench().getDisplay().asyncExec(() ->
					PlatformUI.getWorkbench().getDecoratorManager().update(ID));
		}
	}
}
