package dev.zigr.dt.team.ui.storage;

import java.util.Optional;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.CoreException;

import com._1c.g5.v8.dt.core.platform.IDependentProject;
import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAssociation;
import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAssociationManager;
import com._1c.g5.v8.dt.platform.services.core.infobases.InfobaseAssociationException;
import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import com._1c.g5.wiring.ServiceAccess;
import com._1c.g5.wiring.ServiceSupplier;

final class InfobaseResolver {

	private InfobaseResolver() {
	}

	static InfobaseReference getDefaultInfobase(IProject project) throws CoreException {
		ServiceSupplier<IInfobaseAssociationManager> supplier =
				ServiceAccess.supplier(IInfobaseAssociationManager.class, StorageUiPlugin.getDefault());
		ServiceSupplier<IV8ProjectManager> projectManagerSupplier =
				ServiceAccess.supplier(IV8ProjectManager.class, StorageUiPlugin.getDefault());
		try {
			IInfobaseAssociationManager associationManager = supplier.get();
			Optional<IInfobaseAssociation> association = associationManager.getAssociation(project);
			IProject associationProject = project;
			if (association.isEmpty()) {
				IProject parentProject = getParentProject(projectManagerSupplier.get(), project);
				if (parentProject != null) {
					association = associationManager.getAssociation(parentProject);
					associationProject = parentProject;
				}
			}
			if (association.isEmpty()) {
				throw new CoreException(StorageUiPlugin.createErrorStatus(
						"Для проекта " + project.getName()
								+ " не найдена связанная информационная база"
								+ (associationProject == project ? "" : " базового проекта " + associationProject.getName())));
			}

			InfobaseReference infobase = association.get().getDefaultInfobase();
			if (infobase == null) {
				throw new CoreException(StorageUiPlugin.createErrorStatus(
						"Для проекта " + associationProject.getName() + " не выбрана информационная база по умолчанию"));
			}
			return infobase;
		} catch (InfobaseAssociationException e) {
			throw new CoreException(StorageUiPlugin.createErrorStatus(
					"Не удалось определить связанную информационную базу проекта " + project.getName(), e));
		} finally {
			supplier.close();
			projectManagerSupplier.close();
		}
	}

	private static IProject getParentProject(IV8ProjectManager projectManager, IProject project) {
		IV8Project v8Project = projectManager.getProject(project);
		if (v8Project == null) {
			return null;
		}
		if (v8Project instanceof IDependentProject dependentProject) {
			return dependentProject.getParentProject();
		}
		return null;
	}
}
