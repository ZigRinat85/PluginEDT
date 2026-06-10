package dev.zigr.dt.team.ui.storage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.Adapters;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.xtext.naming.QualifiedName;

import com._1c.g5.v8.dt.core.filesystem.IQualifiedNameFilePathConverter;
import com._1c.g5.v8.dt.core.platform.IResourceLookup;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.wiring.ServiceAccess;
import com._1c.g5.wiring.ServiceSupplier;

final class StorageNavigatorObjectResolver {

	private final IResourceLookup resourceLookup;
	private final IQualifiedNameFilePathConverter qualifiedNameFilePathConverter;

	StorageNavigatorObjectResolver(IResourceLookup resourceLookup,
			IQualifiedNameFilePathConverter qualifiedNameFilePathConverter) {
		this.resourceLookup = resourceLookup;
		this.qualifiedNameFilePathConverter = qualifiedNameFilePathConverter;
	}

	static ResolvedObject resolveForDecorator(Object element) {
		ServiceSupplier<IResourceLookup> resourceLookupSupplier =
				ServiceAccess.supplier(IResourceLookup.class, StorageUiPlugin.getDefault());
		ServiceSupplier<IQualifiedNameFilePathConverter> converterSupplier =
				ServiceAccess.supplier(IQualifiedNameFilePathConverter.class, StorageUiPlugin.getDefault());
		try {
			return new StorageNavigatorObjectResolver(resourceLookupSupplier.get(), converterSupplier.get())
					.resolveElement(element);
		} catch (RuntimeException e) {
			return null;
		} finally {
			resourceLookupSupplier.close();
			converterSupplier.close();
		}
	}

	ResolvedSelection resolveSelection(IStructuredSelection selection) {
		Map<QualifiedName, Boolean> objects = new LinkedHashMap<QualifiedName, Boolean>();
		List<EObject> exportObjects = new ArrayList<EObject>();
		IProject project = null;
		for (Object element : selection.toArray()) {
			ResolvedObject resolvedObject = resolveElement(element);
			if (resolvedObject == null || resolvedObject.project() == null || resolvedObject.objectName() == null) {
				continue;
			}
			if (project == null) {
				project = resolvedObject.project();
			}
			if (!project.equals(resolvedObject.project())) {
				throw new IllegalArgumentException("Выберите объекты одного EDT-проекта");
			}
			objects.put(resolvedObject.objectName(), Boolean.FALSE);
			if (resolvedObject.exportObject() != null && !exportObjects.contains(resolvedObject.exportObject())) {
				exportObjects.add(resolvedObject.exportObject());
			}
		}
		return new ResolvedSelection(project, objects, exportObjects);
	}

	ResolvedObject resolveElement(Object element) {
		if (element == null) {
			return null;
		}

		IProject project = (IProject)Adapters.adapt(element, IProject.class);
		if (project != null) {
			return new ResolvedObject(project, QualifiedName.create("Configuration"), null);
		}

		EObject eObject = (EObject)Adapters.adapt(element, EObject.class);
		if (eObject == null && element instanceof EObject directEObject) {
			eObject = directEObject;
		}
		if (eObject != null) {
			return resolveEObject(eObject);
		}

		IResource resource = (IResource)Adapters.adapt(element, IResource.class);
		if (resource instanceof IFile file) {
			QualifiedName objectName = getObjectName(file);
			if (objectName != null) {
				return new ResolvedObject(file.getProject(), objectName, null);
			}
		}

		return null;
	}

	private ResolvedObject resolveEObject(EObject eObject) {
		IProject project = resourceLookup.getProject(eObject);
		if (project == null) {
			return null;
		}

		QualifiedName objectName = getObjectName(eObject);
		EObject exportObject = getExportObject(eObject);
		if (objectName == null) {
			IFile file = resourceLookup.getPlatformResource(eObject);
			objectName = getObjectName(file);
		}
		return objectName == null ? null : new ResolvedObject(project, objectName, exportObject);
	}

	private QualifiedName getObjectName(IFile file) {
		if (file == null) {
			return null;
		}
		QualifiedName fqn = qualifiedNameFilePathConverter.getFqn(file);
		return fqn == null ? null : toLockObjectName(fqn);
	}

	private QualifiedName getObjectName(EObject eObject) {
		if (eObject instanceof Configuration) {
			return QualifiedName.create("Configuration");
		}
		EObject current = eObject;
		while (current != null && !(current instanceof MdObject)) {
			current = current.eContainer();
		}
		if (!(current instanceof MdObject mdObject)) {
			return null;
		}

		List<String> segments = new ArrayList<String>();
		while (mdObject != null) {
			String objectType = getRepositoryObjectType(mdObject);
			if (objectType == null || mdObject.getName() == null || mdObject.getName().isBlank()) {
				break;
			}
			segments.add(0, mdObject.getName());
			segments.add(0, objectType);
			EObject parent = mdObject.eContainer();
			mdObject = parent instanceof MdObject parentObject ? parentObject : null;
		}
		return segments.isEmpty() ? null : QualifiedName.create(segments);
	}

	private EObject getExportObject(EObject eObject) {
		if (eObject instanceof Configuration) {
			return eObject;
		}
		EObject current = eObject;
		EObject result = null;
		while (current != null) {
			if (current instanceof MdObject) {
				result = current;
			}
			if (current.eContainer() instanceof Configuration) {
				return current;
			}
			current = current.eContainer();
		}
		return result;
	}

	private QualifiedName toLockObjectName(QualifiedName fqn) {
		if (fqn == null || fqn.getSegmentCount() == 0) {
			return null;
		}
		if ("Configuration".equals(fqn.getFirstSegment())) {
			return QualifiedName.create("Configuration");
		}
		return fqn;
	}

	private String getRepositoryObjectType(MdObject mdObject) {
		String type = mdObject.eClass().getName();
		EObject parent = mdObject.eContainer();
		if (parent instanceof Configuration) {
			return type;
		}
		if (type.endsWith("Form")) {
			return "Form";
		}
		if (type.endsWith("Template")) {
			return "Template";
		}
		if (type.endsWith("Attribute")) {
			return "Attribute";
		}
		if (type.endsWith("Command")) {
			return "Command";
		}
		if (type.endsWith("TabularSection")) {
			return "TabularSection";
		}
		if (type.endsWith("Dimension")) {
			return "Dimension";
		}
		if (type.endsWith("Resource")) {
			return "Resource";
		}
		return type;
	}

	record ResolvedObject(IProject project, QualifiedName objectName, EObject exportObject) {
	}

	record ResolvedSelection(IProject project, Map<QualifiedName, Boolean> objects, List<EObject> exportObjects) {
		boolean isEmpty() {
			return project == null || objects.isEmpty();
		}

		List<QualifiedName> objectNames() {
			return new ArrayList<QualifiedName>(objects.keySet());
		}

		String objectNamesText() {
			List<String> names = new ArrayList<String>();
			for (QualifiedName objectName : objects.keySet()) {
				names.add(objectName.toString());
			}
			return String.join(", ", names);
		}
	}
}
