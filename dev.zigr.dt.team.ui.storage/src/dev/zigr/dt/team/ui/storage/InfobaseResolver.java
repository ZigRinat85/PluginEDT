package dev.zigr.dt.team.ui.storage;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.CoreException;

import com._1c.g5.v8.dt.core.platform.IDependentProject;
import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAssociation;
import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAssociationManager;
import com._1c.g5.v8.dt.platform.services.core.infobases.InfobaseAssociationContext;
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
			IProject associationProject = project;
			InfobaseReference infobase = findInfobase(associationManager, project);
			if (infobase == null) {
				IProject parentProject = getParentProject(projectManagerSupplier.get(), project);
				if (parentProject != null) {
					infobase = findInfobase(associationManager, parentProject);
					associationProject = parentProject;
				}
			}
			if (infobase == null) {
				throw new CoreException(StorageUiPlugin.createErrorStatus(
						"Для проекта " + project.getName()
								+ " не найдена связанная информационная база"
								+ (associationProject == project ? "" : " базового проекта " + associationProject.getName())));
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

	private static InfobaseReference findInfobase(IInfobaseAssociationManager associationManager, IProject project)
			throws InfobaseAssociationException, CoreException {
		InfobaseReference infobase = getDefaultInfobase(associationManager.getAssociation(project));
		if (infobase != null) {
			return infobase;
		}

		List<AssociationCandidate> candidates = getAssociationCandidates(associationManager, project);
		if (candidates.isEmpty()) {
			return null;
		}

		AssociationCandidate preferred = findCandidate(candidates, "refs/heads/master");
		if (preferred == null) {
			preferred = findCandidate(candidates, "refs/heads/main");
		}
		if (preferred != null) {
			return preferred.infobase();
		}

		if (allCandidatesUseSameInfobase(candidates)) {
			return candidates.get(0).infobase();
		}

		throw new CoreException(StorageUiPlugin.createErrorStatus(
				"Для проекта " + project.getName()
						+ " найдено несколько связанных информационных баз в разных Git-контекстах: "
						+ describeCandidates(candidates)
						+ ". Свяжите информационную базу с текущей Git-веткой или с веткой master/main."));
	}

	private static List<AssociationCandidate> getAssociationCandidates(IInfobaseAssociationManager associationManager,
			IProject project) throws InfobaseAssociationException {
		Collection<InfobaseAssociationContext> contexts = associationManager.getAssociationContexts(project);
		return contexts.stream()
				.map(context -> getAssociationCandidate(associationManager, project, context))
				.filter(Optional::isPresent)
				.map(Optional::get)
				.toList();
	}

	private static Optional<AssociationCandidate> getAssociationCandidate(IInfobaseAssociationManager associationManager,
			IProject project, InfobaseAssociationContext context) {
		try {
			InfobaseReference infobase = getDefaultInfobase(associationManager.getAssociation(project, context));
			return infobase == null ? Optional.empty() : Optional.of(new AssociationCandidate(context, infobase));
		} catch (InfobaseAssociationException e) {
			return Optional.empty();
		}
	}

	private static InfobaseReference getDefaultInfobase(Optional<IInfobaseAssociation> association) {
		if (association.isEmpty()) {
			return null;
		}
		return association.get().getDefaultInfobase();
	}

	private static AssociationCandidate findCandidate(List<AssociationCandidate> candidates, String context) {
		for (AssociationCandidate candidate : candidates) {
			if (context.equals(candidate.contextName())) {
				return candidate;
			}
		}
		return null;
	}

	private static boolean allCandidatesUseSameInfobase(List<AssociationCandidate> candidates) {
		if (candidates.isEmpty()) {
			return false;
		}
		InfobaseReference first = candidates.get(0).infobase();
		for (AssociationCandidate candidate : candidates) {
			if (!first.getUuid().equals(candidate.infobase().getUuid())) {
				return false;
			}
		}
		return true;
	}

	private static String describeCandidates(List<AssociationCandidate> candidates) {
		return String.join(", ", candidates.stream()
				.map(candidate -> candidate.contextName() + " -> " + candidate.infobase().getName())
				.toList());
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

	private record AssociationCandidate(InfobaseAssociationContext context, InfobaseReference infobase) {
		private String contextName() {
			return context.getContext().orElse("default");
		}
	}
}
