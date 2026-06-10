package dev.zigr.dt.team.ui.storage;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Map.Entry;
import java.util.Properties;

import org.eclipse.xtext.naming.QualifiedName;

final class StorageLockStateStore {

	private static final StorageLockStateStore INSTANCE = new StorageLockStateStore();
	private static final String LOCKED = "locked";

	private final Properties locks = new Properties();
	private boolean loaded;

	static StorageLockStateStore getInstance() {
		return INSTANCE;
	}

	private StorageLockStateStore() {
	}

	synchronized boolean isLocked(String projectName, QualifiedName objectName) {
		ensureLoaded();
		return LOCKED.equals(locks.getProperty(key(projectName, objectName)));
	}

	synchronized void markLocked(String projectName, Collection<QualifiedName> objectNames) {
		ensureLoaded();
		for (QualifiedName objectName : objectNames) {
			locks.setProperty(key(projectName, objectName), LOCKED);
		}
		save();
	}

	synchronized void markUnlocked(String projectName, Collection<QualifiedName> objectNames) {
		ensureLoaded();
		for (QualifiedName objectName : objectNames) {
			locks.remove(key(projectName, objectName));
		}
		save();
	}

	synchronized void reload() {
		loaded = false;
		locks.clear();
		ensureLoaded();
	}

	synchronized int countLocked(String projectName) {
		ensureLoaded();
		String prefix = projectName + "|";
		int result = 0;
		for (Entry<Object, Object> entry : locks.entrySet()) {
			if (entry.getKey().toString().startsWith(prefix)
					&& LOCKED.equals(entry.getValue())) {
				result++;
			}
		}
		return result;
	}

	synchronized int countLocked(String projectName, Collection<QualifiedName> objectNames) {
		ensureLoaded();
		int result = 0;
		for (QualifiedName objectName : objectNames) {
			if (LOCKED.equals(locks.getProperty(key(projectName, objectName)))) {
				result++;
			}
		}
		return result;
	}

	synchronized Path stateFile() {
		return getStateFile();
	}

	private void ensureLoaded() {
		if (loaded) {
			return;
		}
		Path file = getStateFile();
		if (Files.isRegularFile(file)) {
			try (InputStream input = Files.newInputStream(file)) {
				locks.load(input);
			} catch (IOException e) {
				StorageUiPlugin.logError(e.getMessage(), e);
			}
		}
		loaded = true;
	}

	private void save() {
		Path file = getStateFile();
		try {
			Files.createDirectories(file.getParent());
			try (OutputStream output = Files.newOutputStream(file)) {
				locks.store(output, "Configuration repository local lock state");
			}
		} catch (IOException e) {
			StorageUiPlugin.logError(e.getMessage(), e);
		}
	}

	private Path getStateFile() {
		if (StorageUiPlugin.getDefault() != null) {
			return StorageUiPlugin.getDefault().getStateLocation().toFile().toPath().resolve("storage-lock-state.properties");
		}
		return Path.of(System.getProperty("java.io.tmpdir"), "storage-lock-state.properties");
	}

	private String key(String projectName, QualifiedName objectName) {
		return projectName + "|" + objectName.toString();
	}
}
