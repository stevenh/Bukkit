package org.bukkit.plugin.java;

import java.net.URL;
import java.net.URLClassLoader;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import java.util.HashSet;
import java.util.Collection;
import java.util.Iterator;
import java.util.Arrays;

/**
 * A ClassLoader for plugins, to allow shared classes across multiple plugins
 */
public class PluginClassLoader extends URLClassLoader {
    private final Map<String, BasePluginClassLoader> loaders = new HashMap<String, BasePluginClassLoader>();

    public PluginClassLoader() {
        super(new URL[0], PluginClassLoader.class.getClassLoader());
    }

    public ClassLoader getLoader(String pluginName, URL url) {
        BasePluginClassLoader loader = loaders.get(pluginName);
        if (null == loader) {
            loader = new BasePluginClassLoader(new URL[] { url }, this);
            loaders.put(pluginName, loader);
        }
        return loader;
    }

    public boolean hasLoader(String pluginName) {
        return loaders.containsKey(pluginName);
    }

    public void removeLoader(String pluginName) {
        loaders.remove(pluginName);
    }

    /**
    * We override the parent-first behavior established by
    * java.lang.Classloader.
    */
    @Override
    protected Class loadClass(String name, boolean resolve) throws ClassNotFoundException {
        return loadClass(name, resolve, null);
    }

    protected Class loadClass(String name, boolean resolve, BasePluginClassLoader child) throws ClassNotFoundException {
        // First, check if the class has already been loaded
        Class c = findLoadedClass(name);

        // if not loaded, search the local (child) resources
        if (null == c && null != child) {
            try {
                c = child.loadClass(name, resolve, true);
            } catch(ClassNotFoundException e) {
                // Ignore
            }
        }

        if (null == c) {
            // TODO: only look at declared dependency loaders
            for (Map.Entry<String,BasePluginClassLoader> entry : loaders.entrySet()) {
                BasePluginClassLoader loader = entry.getValue();
                if (child.equals(loader)) {
                    continue;
                }

                try {
                    c = loader.loadClass(name, resolve, true);
                    break;
                } catch(ClassNotFoundException e) {
                    // Ignore
                }
            }
        }

        // if we could not find it, delegate to parent
        // Note that we don't attempt to catch any ClassNotFoundException
        if (null == c) {
            ClassLoader parent = getParent();
            c = (null != parent) ? parent.loadClass(name) : getSystemClassLoader().loadClass(name);
        }

        if (resolve) {
            resolveClass(c);
        }

        return c;
    }

    public class BasePluginClassLoader extends URLClassLoader {
        public BasePluginClassLoader(final URL[] urls, final ClassLoader parent) {
            super(urls, parent);
        }

        /**
        * We override the parent-first behavior established by
        * java.lang.Classloader.
        */
        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            return loadClass(name, resolve, false);
        }

        protected Class<?> loadClass(String name, boolean resolve, boolean localOnly) throws ClassNotFoundException {
            // Always check if the class has already been loaded to prevent duplicate class definition's
            Class c = findLoadedClass(name);

            if (null == c) {
                c = (localOnly) ? findClass(name) : ((PluginClassLoader) getParent()).loadClass(name, resolve, this);
            }

            if (resolve) {
                resolveClass(c);
            }
            return c;
        }
    }
}
