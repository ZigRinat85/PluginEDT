package dev.zigr.dt.team.ui.storage;

import java.io.IOException;

import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.equinox.security.storage.ISecurePreferences;
import org.eclipse.equinox.security.storage.SecurePreferencesFactory;
import org.eclipse.equinox.security.storage.StorageException;
import org.osgi.service.prefs.BackingStoreException;
import org.osgi.service.prefs.Preferences;

public class Settings {
	public static final String DEFAULT_COMMIT_COMMENT_TEMPLATE = "EDT: {branch}, project {project}, changed files {changedFiles}";

	private Preferences preferences;
	private ISecurePreferences secPreferences;

	public Settings(String projectName) {
		// метод ISecurePreferences.node принимает только символы ASCII между 32 и 126
		// закодируем в цифры имя проекта, т.к. оно может быть на кириллице
		String encodeProjectName = ""; 
		for (int i = 0; i < projectName.length(); i++) {
			encodeProjectName = encodeProjectName + projectName.codePointAt(i);
		}
		preferences = InstanceScope.INSTANCE.getNode(StorageUiPlugin.PLUGIN_ID)
				.node("Settings").node(projectName);
		
		secPreferences = SecurePreferencesFactory.getDefault().node(StorageUiPlugin.PLUGIN_ID)
				.node("Settings").node(encodeProjectName);
	}

	public String getAddress() {
		return preferences.get("address", "");
	}

	public void setAddress(String address) {
		preferences.put("address", address);
	}

	public String getUser() {
		return preferences.get("user", "");
	}

	public void setUser(String user) {
		preferences.put("user", user);
	}

	public String getPassword() {
		try {
			return secPreferences.get("pw", "");
		} catch (StorageException e) {
			StorageUiPlugin.logError(e.getMessage(), e);
			return "";
		}
	}

	public void setPassword(String pw) throws StorageException {
		secPreferences.put("pw", pw, true);
	}

	public boolean getExportMDWithMDO() {
		return preferences.get("exportMDWithMDO", "").equals("true");
	}

	public void setExportMDWithMDO(boolean exportMDWithMDO) {
		preferences.put("exportMDWithMDO", exportMDWithMDO ? "true" : "false");
		
	}

	public boolean getPushIfConfigurationChanged() {
		return preferences.get("pushIfConfigurationChanged", "").equals("true");
	}

	public void setPushIfConfigurationChanged(boolean pushIfConfigurationChanged) {
		preferences.put("pushIfConfigurationChanged", pushIfConfigurationChanged ? "true" : "false");
		
	}

	public String getCommitCommentTemplate() {
		String result = preferences.get("commitCommentTemplate", DEFAULT_COMMIT_COMMENT_TEMPLATE);
		if (result.isBlank()) {
			return DEFAULT_COMMIT_COMMENT_TEMPLATE;
		}
		return result;
	}

	public void setCommitCommentTemplate(String commitCommentTemplate) {
		preferences.put("commitCommentTemplate", commitCommentTemplate);
	}

	public void flush() throws BackingStoreException, IOException {
		preferences.flush();
		secPreferences.flush();
	}
}
