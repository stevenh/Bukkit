package org.bukkit.plugin;

import java.io.File;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.Iterator;
import java.util.logging.Level;
import java.util.regex.Matcher;
import org.bukkit.Server;
import java.util.regex.Pattern;

import org.bukkit.event.Event;
import org.bukkit.event.Event.Priority;
import org.bukkit.event.Listener;

import org.bukkit.util.FileUtil;

/**
 * Handles all plugin management from the Server
 */
public final class SimplePluginManager implements PluginManager {
    private final Server server;
    private final Map<Pattern, PluginLoader> fileAssociations = new HashMap<Pattern, PluginLoader>();
    private final List<Plugin> plugins = new ArrayList<Plugin>();
    private final Map<String, Plugin> lookupNames = new HashMap<String, Plugin>();
    private final Map<Event.Type, SortedSet<RegisteredListener>> listeners = new EnumMap<Event.Type, SortedSet<RegisteredListener>>(Event.Type.class);
    private static File updateDirectory = null;
    private final Comparator<RegisteredListener> comparer = new Comparator<RegisteredListener>() {
        public int compare(RegisteredListener i, RegisteredListener j) {
            int result = i.getPriority().compareTo(j.getPriority());

            if ((result == 0) && (i != j)) {
                result = 1;
            }

            return result;
        }
    };

    public SimplePluginManager(Server instance) {
        server = instance;
    }

    /**
     * Registers the specified plugin loader
     *
     * @param loader Class name of the PluginLoader to register
     * @throws IllegalArgumentException Thrown when the given Class is not a valid PluginLoader
     */
    public void registerInterface(Class<? extends PluginLoader> loader) throws IllegalArgumentException {
        PluginLoader instance;

        if (PluginLoader.class.isAssignableFrom(loader)) {
            Constructor<? extends PluginLoader> constructor;

            try {
                constructor = loader.getConstructor(Server.class);
                instance = constructor.newInstance(server);
            } catch (NoSuchMethodException ex) {
                String className = loader.getName();

                throw new IllegalArgumentException(String.format("Class %s does not have a public %s(Server) constructor", className, className), ex);
            } catch (Exception ex) {
                throw new IllegalArgumentException(String.format("Unexpected exception %s while attempting to construct a new instance of %s", ex.getClass().getName(), loader.getName()), ex);
            }
        } else {
            throw new IllegalArgumentException(String.format("Class %s does not implement interface PluginLoader", loader.getName()));
        }

        Pattern[] patterns = instance.getPluginFileFilters();

        synchronized (this) {
            for (Pattern pattern : patterns) {
                fileAssociations.put(pattern, instance);
            }
        }
    }

    /**
     * Loads the plugins contained within the specified directory
     *
     * @param directory Directory to check for plugins
     * @return A list of all plugins loaded
     */
    public Plugin[] loadPlugins(File directory) {
        List<Plugin> result = new ArrayList<Plugin>();

        if (!server.getUpdateFolder().equals("")) {
            updateDirectory = new File(directory, server.getUpdateFolder());
        }

        PluginSorter pluginSorter = new PluginSorter(directory);
        for (File file : directory.listFiles()) {
            if (file.isFile()) {
                updatePlugin(file); // Ensure its up to date first as this may change dependencies
                PluginLoader pluginLoader = pluginLoader(file);
                if (null != pluginLoader) {
                    try {
                        pluginSorter.addPlugin(pluginLoader.getPluginDescription(file), file);
                    } catch (InvalidPluginException ex) {
                        server.getLogger().log(Level.SEVERE, "Could not load '" + file.getPath() + "' in folder '" + directory.getPath() + "': ", ex.getCause());
                    } catch (InvalidDescriptionException ex) {
                        server.getLogger().log(Level.SEVERE, "Could not load '" + file.getPath() + "' in folder '" + directory.getPath() + "': " + ex.getMessage(), ex);
                    }
                }
            }
        }

        try {
            for (PluginNode pluginVertex : pluginSorter.orderedPlugins()) {
                Plugin plugin = null;
                File file = pluginVertex.getFile();
                String pluginName = pluginVertex.getName();
                try {
                    // Note: We rely on loadPlugin rechecking dependencies to ensure plugins that depend
                    // on previously failed load plugins give the right error
                    plugin = loadPlugin(file, false);
                } catch (UnknownDependencyException ex) {
                    server.getLogger().log(Level.SEVERE, "Failed to load plugin '" + pluginVertex.getName() + "' from '" + file.getPath() + "' in folder '" + directory.getPath() + "': " + ex.getMessage(), ex);
                } catch (InvalidPluginException ex) {
                    server.getLogger().log(Level.SEVERE, "Failed to load plugin '" + pluginVertex.getName() + "' from '" + file.getPath() + "' in folder '" + directory.getPath() + "': ", ex.getCause());
                } catch (InvalidDescriptionException ex) {
                    server.getLogger().log(Level.SEVERE, "Failed to load plugin '" + pluginVertex.getName() + "' from '" + file.getPath() + "' in folder '" + directory.getPath() + "': " + ex.getMessage(), ex);
                }

                if (plugin != null) {
                    result.add(plugin);
                }
            }
        } catch (CyclicDependencyException e) {
            server.getLogger().log(Level.SEVERE, "Could not load plugins in folder '" + directory.getPath() + "': " + e.getMessage(), e);
        }

        return result.toArray(new Plugin[result.size()]);
    }

    private PluginLoader pluginLoader(File file) {
        for (Pattern filter : fileAssociations.keySet()) {
            String name = file.getName();
            Matcher match = filter.matcher(name);

            if (match.find()) {
                return fileAssociations.get(filter);
            }
        }

        return null;
    }

    private boolean updatePlugin(File file) {
        File updateFile = null;

        if (file.isFile() && updateDirectory != null && updateDirectory.isDirectory() && (updateFile = new File(updateDirectory, file.getName())).isFile()) {
            if (FileUtil.copy(updateFile, file)) {
                updateFile.delete();
                return true;
            }
        }

        return false;
    }

    /**
     * Loads the plugin in the specified file
     *
     * File must be valid according to the current enabled Plugin interfaces
     *
     * @param file File containing the plugin to load
     * @return The Plugin loaded, or null if it was invalid
     * @throws InvalidPluginException Thrown when the specified file is not a valid plugin
     * @throws InvalidDescriptionException Thrown when the specified file contains an invalid description
     */
    public Plugin loadPlugin(File file) throws InvalidPluginException, InvalidDescriptionException, UnknownDependencyException {
        return loadPlugin(file, true);
    }

    /**
     * Loads the plugin in the specified file
     *
     * File must be valid according to the current enabled Plugin interfaces
     *
     * @param file File containing the plugin to load
     * @param updatePlugin plugin update will be applied before loading if one is available
     * @return The Plugin loaded, or null if it was invalid
     * @throws InvalidPluginException Thrown when the specified file is not a valid plugin
     * @throws InvalidDescriptionException Thrown when the specified file contains an invalid description
     */
    private synchronized Plugin loadPlugin(File file, boolean updatePlugin) throws InvalidPluginException, InvalidDescriptionException, UnknownDependencyException {
        if (updatePlugin) {
            updatePlugin(file);
        }

        PluginLoader pluginLoader = pluginLoader(file);
        if (null == pluginLoader) {
            return null;
        }

        PluginDescriptionFile description = pluginLoader.getPluginDescription(file);
        for (String dependencyName : description.getDepend()) {
            Plugin dependency = getPlugin(dependencyName);
            if (null == dependency) {
                // Missing dependency
                throw new UnknownDependencyException(dependencyName);

            } else if (!dependency.isEnabled()) {
                // Enable the dependency plugin and all its dependencies
                if (!enablePlugin(dependency, true)) {
                    return null; // Dependency failed to load
                }
            }
        }

        Plugin result = pluginLoader.loadPlugin(file);
        if (result != null) {
            plugins.add(result);
            lookupNames.put(result.getDescription().getName(), result);
        }

        return result;
    }

    /**
     * Checks if the given plugin is loaded and returns it when applicable
     *
     * Please note that the name of the plugin is case-sensitive
     *
     * @param name Name of the plugin to check
     * @return Plugin if it exists, otherwise null
     */
    public synchronized Plugin getPlugin(String name) {
        return lookupNames.get(name);
    }

    public synchronized Plugin[] getPlugins() {
        return plugins.toArray(new Plugin[0]);
    }

    /**
     * Checks if the given plugin is enabled or not
     *
     * Please note that the name of the plugin is case-sensitive.
     *
     * @param name Name of the plugin to check
     * @return true if the plugin is enabled, otherwise false
     */
    public boolean isPluginEnabled(String name) {
        Plugin plugin = getPlugin(name);

        return isPluginEnabled(plugin);
    }

    /**
     * Checks if the given plugin is enabled or not
     *
     * @param plugin Plugin to check
     * @return true if the plugin is enabled, otherwise false
     */
    public boolean isPluginEnabled(Plugin plugin) {
        if ((plugin != null) && (plugins.contains(plugin))) {
            return plugin.isEnabled();
        } else {
            return false;
        }
    }

    public void enablePlugin(final Plugin plugin) {
        enablePlugin(plugin, true);
    }

    private boolean enablePlugin(final Plugin plugin, boolean followDependencies) {
        if (!plugin.isEnabled()) {
            if (followDependencies) {
                for (String dependencyName : plugin.getDescription().getDepend()) {
                    Plugin dependency = getPlugin(dependencyName);
                    if (null == dependency ||!enablePlugin(dependency, true)) {
                        // Failed to enable an dependency plugin abort
                        return false;
                    }
                }
            }

            try {
                plugin.getPluginLoader().enablePlugin(plugin);
            } catch (Throwable ex) {
                server.getLogger().log(Level.SEVERE, "Error occurred (in the plugin loader) while enabling " + plugin.getDescription().getFullName() + " (Is it up to date?): " + ex.getMessage(), ex);
                return false;
            }
        }

        return true;
    }

    public void disablePlugins() {
        for (Plugin plugin: getPlugins()) {
            disablePlugin(plugin, false);
        }
    }

    public void disablePlugin(final Plugin plugin) {
        disablePlugin(plugin, true);
    }

    private void disablePlugin(final Plugin plugin, boolean followDependencies) {
        if (plugin.isEnabled()) {
            if (followDependencies) {
                for (Plugin dependency : pluginDependents(plugin)) {
                    disablePlugin(dependency, true);
                }
            }

            try {
                plugin.getPluginLoader().disablePlugin(plugin);
            } catch (Throwable ex) {
                server.getLogger().log(Level.SEVERE, "Error occurred (in the plugin loader) while disabling " + plugin.getDescription().getFullName() + " (Is it up to date?): " + ex.getMessage(), ex);
            }

            // Forced disable
            server.getScheduler().cancelTasks(plugin);
            server.getServicesManager().unregisterAll(plugin);
        }
    }

    private List<Plugin> pluginDependents(Plugin plugin) {
        String pluginName = plugin.getDescription().getName();
        List<Plugin> dependents = new ArrayList<Plugin>();
        for (Plugin potentialDependent : plugins) {
            if (potentialDependent.getDescription().getDepend().contains(pluginName)) {
                dependents.add(plugin);
            }
        }

        return dependents;
    }

    public void clearPlugins() {
        synchronized (this) {
            disablePlugins();
            plugins.clear();
            lookupNames.clear();
            listeners.clear();
            fileAssociations.clear();
        }
    }

    /**
     * Calls a player related event with the given details
     *
     * @param type Type of player related event to call
     * @param event Event details
     */
    public synchronized void callEvent(Event event) {
        SortedSet<RegisteredListener> eventListeners = listeners.get(event.getType());

        if (eventListeners != null) {
            for (RegisteredListener registration : eventListeners) {
                try {
                    registration.callEvent(event);
                } catch (AuthorNagException ex) {
                    Plugin plugin = registration.getPlugin();

                    if (plugin.isNaggable()) {
                        plugin.setNaggable(false);

                        String author = "<NoAuthorGiven>";

                        if (plugin.getDescription().getAuthors().size() > 0) {
                            author = plugin.getDescription().getAuthors().get(0);
                        }
                        server.getLogger().log(Level.SEVERE, String.format(
                            "Nag author: '%s' of '%s' about the following: %s",
                            author,
                            plugin.getDescription().getName(),
                            ex.getMessage()
                        ));
                    }
                } catch (Throwable ex) {
                    server.getLogger().log(Level.SEVERE, "Could not pass event " + event.getType() + " to " + registration.getPlugin().getDescription().getName(), ex);
                }
            }
        }
    }

    /**
     * Registers the given event to the specified listener
     *
     * @param type EventType to register
     * @param listener PlayerListener to register
     * @param priority Priority of this event
     * @param plugin Plugin to register
     */
    public void registerEvent(Event.Type type, Listener listener, Priority priority, Plugin plugin) {
        if (!plugin.isEnabled()) {
            throw new IllegalPluginAccessException("Plugin attempted to register " + type + " while not enabled");
        }

        getEventListeners(type).add(new RegisteredListener(listener, priority, plugin, type));
    }

    /**
     * Registers the given event to the specified listener using a directly passed EventExecutor
     *
     * @param type EventType to register
     * @param listener PlayerListener to register
     * @param executor EventExecutor to register
     * @param priority Priority of this event
     * @param plugin Plugin to register
     */
    public void registerEvent(Event.Type type, Listener listener, EventExecutor executor, Priority priority, Plugin plugin) {
        if (!plugin.isEnabled()) {
            throw new IllegalPluginAccessException("Plugin attempted to register " + type + " while not enabled");
        }

        getEventListeners(type).add(new RegisteredListener(listener, executor, priority, plugin));
    }

    /**
     * Returns a SortedSet of RegisteredListener for the specified event type creating a new queue if needed
     *
     * @param type EventType to lookup
     * @return SortedSet<RegisteredListener> the looked up or create queue matching the requested type
     */
    private SortedSet<RegisteredListener> getEventListeners(Event.Type type) {
        SortedSet<RegisteredListener> eventListeners = listeners.get(type);

        if (eventListeners != null) {
            return eventListeners;
        }

        eventListeners = new TreeSet<RegisteredListener>(comparer);
        listeners.put(type, eventListeners);
        return eventListeners;
    }

    private class PluginNode {
        private final PluginDescriptionFile description;
        private final File file;
        private final int initialIndex;

        public PluginNode(PluginDescriptionFile description, File file, int initialIndex) {
            this.description = description;
            this.file = file;
            this.initialIndex = initialIndex;
        }

        public File getFile() {
            return file;
        }

        public int getInitialIndex() {
            return initialIndex;
        }

        public List<String> getDepend() {
            return description.getDepend();
        }

        public List<String> getSoftDepend() {
            return description.getSoftDepend();
        }

        public String getName() {
            return description.getName();
        }

        public String toString() {
            return getName();
        }
    }

    private class PluginSorter {
        private ArrayList<PluginNode> pluginList;
        private HashMap<String,PluginNode> pluginHash;
        private PluginNode sortedPlugins[];
        private int matrix[][];
        private int numPlugins;
        private final File directory;

        public PluginSorter(File directory) {
            this.directory = directory;
            pluginList = new ArrayList<PluginNode>();
            pluginHash = new HashMap<String,PluginNode>();
        }

        public PluginNode addPlugin(PluginDescriptionFile description, File file) {
            PluginNode plugin = new PluginNode(description, file, pluginList.size());
            pluginList.add(plugin);
            pluginHash.put(plugin.getName(), plugin);
            return plugin;
        }

        public PluginNode[] orderedPlugins() throws CyclicDependencyException {
            addDependencies();

            while (numPlugins > 0) {
                int currentPlugin = noSuccessors();
                if (-1 == currentPlugin) {
                    throw new CyclicDependencyException();
                }
                sortedPlugins[numPlugins - 1] = pluginList.get(currentPlugin);
                deletePlugin(currentPlugin);
            }

            return sortedPlugins;
        }

        private void addDependencies() {
            // Initialise our Topological sorting matrix
            numPlugins = pluginList.size();
            sortedPlugins = new PluginNode[numPlugins];
            matrix = new int[numPlugins][numPlugins];
            for (int i = 0; i < numPlugins; i++) {
                for (int k = 0; k < numPlugins; k++) {
                    matrix[i][k] = 0;
                }
            }

            // Remove plugins with missing hard dependencies
            for (PluginNode plugin: pluginList) {
                for (String dependencyName : plugin.getDepend()) {
                    PluginNode dependency = pluginHash.get(dependencyName);
                    if (null == dependency) {
                        // Missing dependency, cant load this plugin so remove it
                        server.getLogger().log(Level.SEVERE, "Could not load '" + plugin.getFile().getPath() + "' in folder '" + directory.getPath() + "': Missing dependency '" + dependencyName + "'");
                        pluginList.remove(plugin.getInitialIndex());
                    }
                }
            }

            // Add all dependencies
            for (PluginNode plugin: pluginList) {
                // Hard Dependencies
                int idx = plugin.getInitialIndex();
                for (String dependencyName : plugin.getDepend()) {
                    matrix[pluginHash.get(dependencyName).getInitialIndex()][idx] = 1;
                }
                // Soft Dependencies
                for (String dependencyName : plugin.getSoftDepend()) {
                    PluginNode dependency = pluginHash.get(dependencyName);
                    if (null != dependency) {
                        // Dependency was found add it
                        matrix[dependency.getInitialIndex()][idx] = 1;
                    }
                }
            }
        }

        private int noSuccessors() {
            boolean isEdge;
            for (int row = 0; row < numPlugins; row++) {
                isEdge = false;
                for (int col = 0; col < numPlugins; col++) {
                    if (0 < matrix[row][col]) {
                        isEdge = true;
                        break;
                    }
                }
                if (!isEdge) {
                    return row;
                }
            }
            return -1; // cycle detected
        }

        private void deletePlugin(int delPlugin) {
            if (delPlugin != numPlugins - 1) {
                for (int j = delPlugin; j < numPlugins - 1; j++) {
                    pluginList.set(j, pluginList.get(j + 1));
                }

                for (int row = delPlugin; row < numPlugins - 1; row++) {
                    moveRowUp(row, numPlugins);
                }

                for (int col = delPlugin; col < numPlugins - 1; col++) {
                    moveColLeft(col, numPlugins - 1);
                }
            }
            numPlugins--;
        }

        private void moveRowUp(int row, int length) {
            for (int col = 0; col < length; col++) {
                matrix[row][col] = matrix[row + 1][col];
            }
        }

        private void moveColLeft(int col, int length) {
            for (int row = 0; row < length; row++) {
                matrix[row][col] = matrix[row][col + 1];
            }
        }
    }
}
