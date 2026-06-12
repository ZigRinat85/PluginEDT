package dev.zigr.dt.team.ui.storage;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.xtext.naming.QualifiedName;

import com._1c.g5.v8.dt.core.filesystem.IQualifiedNameFilePathConverter;

final class StorageChangedObjectsResolver {

	private StorageChangedObjectsResolver() {
	}

	static Map<IProject, Map<QualifiedName, Boolean>> getUncommittedLockObjects(Repository repository,
			IQualifiedNameFilePathConverter qualifiedNameFilePathConverter, OperationLogger logger)
			throws CoreException, GitAPIException, IOException {
		try (Git git = new Git(repository)) {
			Status status = git.status().call();
			logGitStatus(status, logger);
			if (!status.getConflicting().isEmpty()) {
				throw new CoreException(StorageUiPlugin.createErrorStatus(
						"Нельзя захватить незакоммиченные изменения: в рабочем каталоге Git есть конфликты"));
			}

			Set<String> repositoryPaths = getChangedRepositoryPaths(repository, status);
			logger.detail("Файлов Git для анализа: " + repositoryPaths.size());
			return resolveLockObjects(repository, repositoryPaths, qualifiedNameFilePathConverter, logger);
		}
	}

	private static Set<String> getChangedRepositoryPaths(Repository repository, Status status) throws IOException {
		Set<String> result = new LinkedHashSet<String>();
		result.addAll(status.getAdded());
		result.addAll(status.getChanged());
		result.addAll(status.getModified());
		result.addAll(status.getRemoved());
		result.addAll(status.getMissing());
		result.addAll(status.getUntracked());

		Path repositoryRoot = repository.getWorkTree().toPath().toAbsolutePath().normalize();
		for (String untrackedFolder : status.getUntrackedFolders()) {
			Path folder = repositoryRoot.resolve(toSystemPath(untrackedFolder)).toAbsolutePath().normalize();
			if (!Files.isDirectory(folder)) {
				result.add(untrackedFolder);
				continue;
			}
			try (var stream = Files.walk(folder)) {
				stream.filter(Files::isRegularFile)
						.map(path -> toRepositoryPath(repositoryRoot.relativize(path.toAbsolutePath().normalize())))
						.forEach(result::add);
			}
		}
		return result;
	}

	private static Map<IProject, Map<QualifiedName, Boolean>> resolveLockObjects(Repository repository,
			Set<String> repositoryPaths, IQualifiedNameFilePathConverter qualifiedNameFilePathConverter,
			OperationLogger logger) {
		Map<IProject, Map<QualifiedName, Boolean>> result = new LinkedHashMap<IProject, Map<QualifiedName, Boolean>>();
		Path repositoryRoot = repository.getWorkTree().toPath().toAbsolutePath().normalize();
		for (String repositoryPath : repositoryPaths) {
			IProject project = getProject(repositoryRoot, repositoryPath);
			if (project == null) {
				logger.detail("Файл пропущен, не найден EDT-проект: " + repositoryPath);
				continue;
			}
			Settings settings = new Settings(project.getName());
			if (settings.getAddress().isBlank()) {
				logger.detail("Файл пропущен, адрес хранилища проекта не заполнен: " + repositoryPath);
				continue;
			}

			String projectRelativePath = getProjectRelativePath(project, repositoryRoot, repositoryPath);
			if (projectRelativePath == null || !V8FileBuilder.isV8File(projectRelativePath)) {
				logger.detail("Файл пропущен, это не V8-файл EDT: " + repositoryPath);
				continue;
			}

			QualifiedName fqn = getFqn(project, projectRelativePath, qualifiedNameFilePathConverter);
			if (fqn == null) {
				logger.detail("Файл пропущен, не удалось определить объект EDT: " + repositoryPath);
				continue;
			}

			Map<QualifiedName, Boolean> projectObjects = result.computeIfAbsent(project,
					key -> new LinkedHashMap<QualifiedName, Boolean>());
			putLockObject(projectObjects, fqn, projectRelativePath, settings);
		}
		logLockObjects(result, logger);
		return result;
	}

	private static IProject getProject(Path repositoryRoot, String repositoryPath) {
		Path absolutePath = repositoryRoot.resolve(toSystemPath(repositoryPath)).toAbsolutePath().normalize();
		IProject result = null;
		int resultNameCount = -1;
		for (IProject project : ResourcesPlugin.getWorkspace().getRoot().getProjects()) {
			if (!project.isAccessible() || project.getLocation() == null) {
				continue;
			}
			Path projectLocation = project.getLocation().toFile().toPath().toAbsolutePath().normalize();
			if (absolutePath.startsWith(projectLocation) && projectLocation.getNameCount() > resultNameCount) {
				result = project;
				resultNameCount = projectLocation.getNameCount();
			}
		}
		return result;
	}

	private static String getProjectRelativePath(IProject project, Path repositoryRoot, String repositoryPath) {
		if (project.getLocation() == null) {
			return null;
		}
		Path absolutePath = repositoryRoot.resolve(toSystemPath(repositoryPath)).toAbsolutePath().normalize();
		Path projectLocation = project.getLocation().toFile().toPath().toAbsolutePath().normalize();
		if (!absolutePath.startsWith(projectLocation)) {
			return null;
		}
		return toRepositoryPath(projectLocation.relativize(absolutePath));
	}

	private static QualifiedName getFqn(IProject project, String projectRelativePath,
			IQualifiedNameFilePathConverter qualifiedNameFilePathConverter) {
		IFile file = project.getFile(org.eclipse.core.runtime.Path.fromPortableString(projectRelativePath));
		QualifiedName result = qualifiedNameFilePathConverter.getFqn(file);
		if (result != null) {
			return result;
		}
		return qualifiedNameFilePathConverter.getFqn(project.getName() + "/" + projectRelativePath);
	}

	private static void putLockObject(Map<QualifiedName, Boolean> result, QualifiedName fqn, String sourceFile,
			Settings storageSettings) {
		int segmentCount = fqn.getSegmentCount();
		if (segmentCount == 0) {
			return;
		}

		String firstSegment = fqn.getFirstSegment();
		if ("Configuration".equals(firstSegment)) {
			result.put(fqn.skipLast(segmentCount - 1), Boolean.FALSE);
		} else if ("Subsystem".equals(firstSegment)) {
			int firstCount = 0;
			for (int i = 0; i < segmentCount; i = i + 2) {
				if ("Subsystem".equals(fqn.getSegment(i))) {
					firstCount = i + 2;
				} else {
					break;
				}
			}
			if (firstCount > 0) {
				result.put(fqn.skipLast(segmentCount - firstCount), Boolean.FALSE);
			}
		} else if ("ExternalDataSource".equals(firstSegment)) {
			result.put(fqn.skipLast(segmentCount - 2), Boolean.TRUE);
		} else if ("CalculationRegister".equals(firstSegment) && sourceFile.endsWith(".mdo")) {
			result.put(fqn.skipLast(segmentCount - 2), Boolean.TRUE);
		} else if (segmentCount >= 4
				&& ("Form".equals(fqn.getSegment(2)) || "Template".equals(fqn.getSegment(2)))) {
			result.put(fqn.skipLast(segmentCount - 4), Boolean.FALSE);
		} else if (storageSettings.getExportMDWithMDO()) {
			if (sourceFile.endsWith(".mdo")) {
				result.put(fqn.skipLast(segmentCount - 2), Boolean.TRUE);
			} else {
				result.putIfAbsent(fqn.skipLast(segmentCount - 2), Boolean.FALSE);
			}
		} else {
			result.put(fqn.skipLast(segmentCount - 2), Boolean.FALSE);
		}
	}

	private static void logGitStatus(Status status, OperationLogger logger) {
		logger.detail("Незакоммиченные изменения Git: added=" + status.getAdded().size()
				+ ", changed=" + status.getChanged().size()
				+ ", modified=" + status.getModified().size()
				+ ", removed=" + status.getRemoved().size()
				+ ", missing=" + status.getMissing().size()
				+ ", untracked=" + status.getUntracked().size()
				+ ", untrackedFolders=" + status.getUntrackedFolders().size()
				+ ", conflicting=" + status.getConflicting().size());
	}

	private static void logLockObjects(Map<IProject, Map<QualifiedName, Boolean>> objectsByProject,
			OperationLogger logger) {
		for (Map.Entry<IProject, Map<QualifiedName, Boolean>> projectEntry : objectsByProject.entrySet()) {
			for (Map.Entry<QualifiedName, Boolean> objectEntry : projectEntry.getValue().entrySet()) {
				logger.detail("Объект к захвату: проект=" + projectEntry.getKey().getName()
						+ ", объект=" + objectEntry.getKey()
						+ ", includeChildObjects=" + objectEntry.getValue());
			}
		}
	}

	private static Path toSystemPath(String path) {
		return Path.of(path.replace('/', File.separatorChar));
	}

	private static String toRepositoryPath(Path path) {
		return path.toString().replace(File.separatorChar, '/');
	}
}
