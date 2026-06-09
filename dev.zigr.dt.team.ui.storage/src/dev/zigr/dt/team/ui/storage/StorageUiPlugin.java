package dev.zigr.dt.team.ui.storage;

import com._1c.g5.v8.dt.export.ExportRuntimeModule;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.ui.plugin.AbstractUIPlugin;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;

import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Module;

/**
 * The activator class controls the plug-in life cycle
 */
public class StorageUiPlugin extends AbstractUIPlugin {

	// The plug-in ID
	public static final String PLUGIN_ID = "dev.zigr.dt.team.ui.storage"; //$NON-NLS-1$

	// The shared instance
	private static StorageUiPlugin plugin;

	private Injector injector;
	
	@Override
	public void start(BundleContext context) throws Exception {
		super.start(context);
		plugin = this;
	}

	@Override
	public void stop(BundleContext context) throws Exception {
		plugin = null;
		super.stop(context);
	}

	/**
	 * Returns the shared instance
	 *
	 * @return the shared instance
	 */
	public static StorageUiPlugin getDefault() {
		return plugin;
	}

	/**
	 * Returns Guice injector for this plugin.
	 *
	 * @return Guice injector for this plugin, never {@code null}
	 */
	public synchronized Injector getInjector()
	{
		if (injector == null)
			return injector = createInjector();
		return injector;
	}

	private Injector createInjector()
	{
		try
		{
			List<Module> modules = new ArrayList<Module>();
			modules.add(new ExternalDependenciesModule(this));
			modules.add(new ExportRuntimeModule());
			modules.add(createImportRuntimeModule());
			return Guice.createInjector(modules);
		}
		catch (Exception e)
		{
			throw new RuntimeException("Failed to create injector for " + getBundle().getSymbolicName(), e);
		}
	}

	private Module createImportRuntimeModule() throws Exception {
		Bundle importBundle = Platform.getBundle("com._1c.g5.v8.dt.import");
		if (importBundle == null) {
			throw new IllegalStateException("Bundle com._1c.g5.v8.dt.import is not found");
		}
		Class<?> moduleClass = importBundle.loadClass("com._1c.g5.v8.dt.internal.import_.ImportRuntimeModule");
		Constructor<?> constructor = moduleClass.getConstructor(org.eclipse.core.runtime.Plugin.class);
		return (Module) constructor.newInstance(this);
	}

	public static void log(IStatus status) {
		StorageUiPlugin instance = getDefault();
		if (instance != null) {
			instance.getLog().log(status);
		}
	}

	public static void logError(String message) {
		log(createErrorStatus(message));
	}

	public static void logError(String message, Throwable throwable) {
		log(createErrorStatus(message, throwable));
	}

	public static void logInfo(String message) {
		StorageUiPlugin instance = getDefault();
		if (instance != null) {
			log(createInfoStatus(message));
		}

	}

	public static IStatus createErrorStatus(String message) {
		return createErrorStatus(message, (Throwable) null);
	}

	public static IStatus createErrorStatus(String message, Throwable throwable) {
		return createErrorStatus(message, 0, throwable);
	}

	public static IStatus createErrorStatus(String message, int code, Throwable throwable) {
		return new Status(4, PLUGIN_ID, code, message, throwable);
	}

	public static IStatus createInfoStatus(String message) {
		return new Status(1, PLUGIN_ID, 0, message, (Throwable) null);
	}

}
