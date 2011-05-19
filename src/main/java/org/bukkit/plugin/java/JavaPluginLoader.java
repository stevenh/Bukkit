package org.bukkit.plugin.java;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.FileOutputStream;
import java.lang.reflect.Constructor;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.ArrayList;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.Iterator;
import java.util.jar.Attributes;
import java.util.jar.Attributes.Name;
import java.util.jar.Manifest;
import java.util.StringTokenizer;
import java.util.Enumeration;

import org.bukkit.Server;
import org.bukkit.event.CustomEventListener;
import org.bukkit.event.Event;
import org.bukkit.event.Listener;
import org.bukkit.event.block.*;
import org.bukkit.event.painting.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.*;
import org.bukkit.event.server.*;
import org.bukkit.event.vehicle.*;
import org.bukkit.event.world.*;
import org.bukkit.event.weather.*;
import org.bukkit.plugin.*;
import org.yaml.snakeyaml.error.YAMLException;


/**
 * Represents a Java plugin loader, allowing plugins in the form of .jar
 */
public final class JavaPluginLoader implements PluginLoader {
    private final Server server;
    private final Pattern[] fileFilters = new Pattern[] {
        Pattern.compile("\\.jar$"),
    };
    private final PluginClassLoader pluginLoader;

    public JavaPluginLoader(Server instance) {
        server = instance;
        pluginLoader = new PluginClassLoader();
    }

    public Plugin loadPlugin(File file) throws InvalidPluginException, InvalidDescriptionException, UnknownDependencyException {
        return loadPlugin(file, true);
    }

    public Plugin loadPlugin(File file, boolean checkDependencies) throws InvalidPluginException, InvalidDescriptionException, UnknownDependencyException {
        JavaPlugin result = null;
        PluginDescriptionFile description = getPluginDescription(file);
        File dataFolder = new File(file.getParentFile(), description.getName());
        File oldDataFolder = getDataFolder(file);

        // Found old data folder
        if (dataFolder.equals(oldDataFolder)) {
            // They are equal -- nothing needs to be done!
        } else if (dataFolder.isDirectory() && oldDataFolder.isDirectory()) {
            server.getLogger().log(Level.INFO, String.format(
                "While loading %s (%s) found old-data folder: %s next to the new one: %s",
                description.getName(),
                file,
                oldDataFolder,
                dataFolder
            ));
        } else if (oldDataFolder.isDirectory() && !dataFolder.exists()) {
            if (!oldDataFolder.renameTo(dataFolder)) {
                throw new InvalidPluginException(new Exception("Unable to rename old data folder: '" + oldDataFolder + "' to: '" + dataFolder + "'"));
            }
            server.getLogger().log(Level.INFO, String.format(
                "While loading %s (%s) renamed data folder: '%s' to '%s'",
                description.getName(),
                file,
                oldDataFolder,
                dataFolder
            ));
        }

        if (dataFolder.exists() && !dataFolder.isDirectory()) {
            throw new InvalidPluginException(new Exception(String.format(
                "Projected datafolder: '%s' for %s (%s) exists and is not a directory",
                dataFolder,
                description.getName(),
                file
            )));
        }

        if (checkDependencies) {
            PluginManager pm = server.getPluginManager();
            for (String pluginName : description.getDepend()) {
                if (null == pm.getPlugin(pluginName)) {
                    throw new UnknownDependencyException(pluginName);
                }
            }
        }

        try {

            ClassLoader loader = pluginLoader.getLoader(description.getName(), file.toURI().toURL());
            Class<?> jarClass = Class.forName(description.getMain(), true, loader);
            Class<? extends JavaPlugin> plugin = jarClass.asSubclass(JavaPlugin.class);
            Constructor<? extends JavaPlugin> constructor = plugin.getConstructor();
            result = constructor.newInstance();

            result.initialize(this, server, description, dataFolder, file, loader);
        } catch (Throwable ex) {
            throw new InvalidPluginException(ex);
        }
        return (Plugin)result;
    }

    public PluginDescriptionFile getPluginDescription(File file) throws InvalidPluginException, InvalidDescriptionException {
        if (!file.exists()) {
            throw new InvalidPluginException(new FileNotFoundException(String.format("%s does not exist", file.getPath())));
        }

        try {
            JarFile jar = new JarFile(file);
            JarEntry entry = jar.getJarEntry("plugin.yml");

            if (entry == null) {
                throw new InvalidPluginException(new FileNotFoundException("Jar does not contain plugin.yml"));
            }

            InputStream stream = jar.getInputStream(entry);
            PluginDescriptionFile description = new PluginDescriptionFile(stream);

            stream.close();
            jar.close();

            return description;

        } catch (IOException ex) {
            throw new InvalidPluginException(ex);
        } catch (YAMLException ex) {
            throw new InvalidPluginException(ex);
        }
    }

    private void fixJarClassPath(PluginDescriptionFile description, File file) throws InvalidPluginException, IOException {
        JarFile jar = new JarFile(file);
        File oldFile = null;
        File tmpFile = null;
        try {
            Manifest manifest = jar.getManifest();
            Attributes attribs = manifest.getMainAttributes();
            String cp = attribs.getValue(Name.CLASS_PATH);

            if (null == cp || 0 == cp.trim().length()) {
                return; // No Class-Path so its valid
            }

            // Look for local entries
            StringTokenizer st = new StringTokenizer(cp.trim());
            ArrayList<String> cpElements = new ArrayList();
            StringBuffer validEntries = new StringBuffer();
            StringBuffer invalidEntries = new StringBuffer();
            String invalidSeperator = "";
            String validSeperator = "";
            while (st.hasMoreTokens()) {
                String path = st.nextToken();
                if (-1 == path.indexOf("/")) { // Note: This should not be platform dependent
                    invalidEntries.append(invalidSeperator).append(path);
                    invalidSeperator = " ";
                } else {
                    validEntries.append(validSeperator).append(path);
                    validSeperator = " ";
                }
            }

            if (0 == invalidEntries.length()) {
                return; // Class-Path was valid
            }

            // Class-Path contained invalid entries, try to fix it
            // Report the error to the log / console
            ArrayList<String> authors = description.getAuthors();
            Logger logger = server.getLogger();
            logger.log(Level.SEVERE, String.format("Nag author(s): %s of '%s' about the following:", (0 < authors.size()) ? authors.toString() : "<NoAuthorGiven>", description.getName()));
            logger.log(Level.SEVERE, "Invalid Class-Path in manifest (It contains plugin's)");
            logger.log(Level.SEVERE, "Class-Path is: '" + invalidEntries.toString() + "'");
            logger.log(Level.SEVERE, "Class-Path should be: '" + validEntries.toString() + "'");

            // Fix the plugin by replacing the current jar with one created from a copy with the invalid Class-Path entires removed
            attribs.put(Name.CLASS_PATH, validEntries.toString());
            tmpFile = File.createTempFile("plugin", ".tmpjar", file.getParentFile());
            JarOutputStream tempJar = new JarOutputStream(new FileOutputStream(tmpFile), manifest);
            byte[] buffer = new byte[1024];
            int bytesRead;

            for (Enumeration entries = jar.entries(); entries.hasMoreElements();) {
                JarEntry entry = (JarEntry)entries.nextElement();
                if (entry.getName().equalsIgnoreCase("META-INF/MANIFEST.MF")) {
                    continue;
                }
                InputStream entryStream = jar.getInputStream(entry);
                tempJar.putNextEntry(entry);

                while (-1 != (bytesRead = entryStream.read(buffer))) {
                    tempJar.write(buffer, 0, bytesRead);
                }
            }

            jar.close();
            tempJar.close();

            // Replace the old jar with the fixed one
            oldFile = File.createTempFile(file.getName(), ".old", file.getParentFile());
            oldFile.delete();
            if (!file.renameTo(oldFile)) {
                throw new IOException("Failed to fix class path (initial rename failed)");
            }

            if (!tmpFile.renameTo(file)) {
                oldFile.renameTo(file); // Might not work but at least try
                throw new IOException("Failed to fix class path (fixed rename failed)");
            }
        } finally {
            // Cleanup
            if (null != jar) {
                jar.close();
            }
            if (null != oldFile) {
                oldFile.delete();
            }
            if (null != tmpFile) {
                tmpFile.delete();
            }
        }
    }

    private File getDataFolder(File file) {
        File dataFolder = null;

        String filename = file.getName();
        int index = file.getName().lastIndexOf(".");

        if (index != -1) {
            String name = filename.substring(0, index);

            dataFolder = new File(file.getParentFile(), name);
        } else {
            // This is if there is no extension, which should not happen
            // Using _ to prevent name collision

            dataFolder = new File(file.getParentFile(), filename + "_");
        }

        return dataFolder;
    }

    public Pattern[] getPluginFileFilters() {
        return fileFilters;
    }

    public EventExecutor createExecutor(Event.Type type, Listener listener) {
        // TODO: remove multiple Listener type and hence casts

        switch (type) {
        // Player Events

        case PLAYER_JOIN:
            return new EventExecutor() {
                public void execute(Listener listener, Event event) {
                    ((PlayerListener) listener).onPlayerJoin((PlayerJoinEvent) event);
                }
            };

        case PLAYER_QUIT:
            return new EventExecutor() {
                public void execute(Listener listener, Event event) {
                    ((PlayerListener) listener).onPlayerQuit((PlayerQuitEvent) event);
                }
            };

        case PLAYER_RESPAWN:
            return new EventExecutor() {
                public void execute(Listener listener, Event event) {
                    ((PlayerListener) listener).onPlayerRespawn((PlayerRespawnEvent) event);
                }
            };

        case PLAYER_KICK:
            return new EventExecutor() {
                public void execute(Listener listener, Event event) {
                    ((PlayerListener) listener).onPlayerKick((PlayerKickEvent) event);
                }
            };

        case PLAYER_COMMAND_PREPROCESS:
            return new EventExecutor() {
                public void execute(Listener listener, Event event) {
                    ((PlayerListener) listener).onPlayerCommandPreprocess((PlayerCommandPreprocessEvent) event);
                }
            };

        case PLAYER_CHAT:
            return new EventExecutor() {
                public void execute(Listener listener, Event event) {
                    ((PlayerListener) listener).onPlayerChat((PlayerChatEvent) event);
                }
            };

        case PLAYER_MOVE:
            return new EventExecutor() {
                public void execute(Listener listener, Event event) {
                    ((PlayerListener) listener).onPlayerMove((PlayerMoveEvent) event);
                }
            };

        case PLAYER_TELEPORT:
            return new EventExecutor() {
                public void execute(Listener listener, Event event) {
                    ((PlayerListener) listener).onPlayerTeleport((PlayerTeleportEvent) event);
                }
            };

        case PLAYER_INTERACT:
            return new EventExecutor() {
                public void execute(Listener listener, Event event) {
                    ((PlayerListener) listener).onPlayerInteract((PlayerInteractEvent) event);
                }
            };

        case PLAYER_INTERACT_ENTITY:
            return new EventExecutor() {
                public void execute(Listener listener, Event event) {
                    ((PlayerListener) listener).onPlayerInteractEntity((PlayerInteractEntityEvent) event);
                }
            };

        case PLAYER_LOGIN:
            return new EventExecutor() {
                public void execute(Listener listener, Event event) {
                    ((PlayerListener) listener).onPlayerLogin((PlayerLoginEvent) event);
                }
            };

        case PLAYER_PRELOGIN:
            return new EventExecutor() {
                public void execute(Listener listener, Event event) {
                    ((PlayerListener) listener).onPlayerPreLogin((PlayerPreLoginEvent) event);
                }
            };

        case PLAYER_EGG_THROW:
            return new EventExecutor() {
                public void execute(Listener listener, Event event) {
                    ((PlayerListener) listener).onPlayerEggThrow((PlayerEggThrowEvent) event);
                }
            };

        case PLAYER_ANIMATION:
            return new EventExecutor() {
                public void execute(Listener listener, Event event) {
                    ((PlayerListener) listener).onPlayerAnimation((PlayerAnimationEvent) event);
                }
            };

        case INVENTORY_OPEN:
            return new EventExecutor() {
                public void execute(Listener listener, Event event) {
                    ((PlayerListener) listener).onInventoryOpen((PlayerInventoryEvent) event);
                }
            };

        case PLAYER_ITEM_HELD:
            return new EventExecutor() {
                public void execute(Listener listener, Event event) {
                    ((PlayerListener) listener).onItemHeldChange((PlayerItemHeldEvent) event);
                }
            };

        case PLAYER_DROP_ITEM:
            return new EventExecutor() {
                public void execute(Listener listener, Event event) {
                    ((PlayerListener) listener).onPlayerDropItem((PlayerDropItemEvent) event);
                }
            };

        case PLAYER_PICKUP_ITEM:
            return new EventExecutor() {
                public void execute(Listener listener, Event event) {
                    ((PlayerListener) listener).onPlayerPickupItem((PlayerPickupItemEvent) event);
                }
            };

        case PLAYER_TOGGLE_SNEAK:
            return new EventExecutor() {
                public void execute(Listener listener, Event event) {
                    ((PlayerListener) listener).onPlayerToggleSneak((PlayerToggleSneakEvent) event);
                }
            };

        case PLAYER_BUCKET_EMPTY:
            return new EventExecutor() {
                public void execute(Listener listener, Event event) {
                    ((PlayerListener) listener).onPlayerBucketEmpty((PlayerBucketEmptyEvent) event);
                }
            };

        case PLAYER_BUCKET_FILL:
            return new EventExecutor() {
                public void execute(Listener listener, Event event) {
                    ((PlayerListener) listener).onPlayerBucketFill((PlayerBucketFillEvent) event);
                }
            };

        case PLAYER_BED_ENTER:
            return new EventExecutor() {
                public void execute(Listener listener, Event event) {
                    ((PlayerListener) listener).onPlayerBedEnter((PlayerBedEnterEvent) event);
                }
            };

        case PLAYER_BED_LEAVE:
            return new EventExecutor() {
                public void execute(Listener listener, Event event) {
                    ((PlayerListener) listener).onPlayerBedLeave((PlayerBedLeaveEvent) event);
                }
            };

        // Block Events
        case BLOCK_PHYSICS:
            return new EventExecutor() {
                public void execute(Listener listener, Event event) {
                    ((BlockListener) listener).onBlockPhysics((BlockPhysicsEvent) event);
                }
            };

        case BLOCK_CANBUILD:
            return new EventExecutor() {
                public void execute(Listener listener, Event event) {
                    ((BlockListener) listener).onBlockCanBuild((BlockCanBuildEvent) event);
                }
            };

        case BLOCK_PLACE:
            return new EventExecutor() {
                public void execute(Listener listener, Event event) {
                    ((BlockListener) listener).onBlockPlace((BlockPlaceEvent) event);
                }
            };

        case BLOCK_DAMAGE:
            return new EventExecutor() {
                public void execute(Listener listener, Event event) {
                    ((BlockListener) listener).onBlockDamage((BlockDamageEvent) event);
                }
            };

        case BLOCK_FROMTO:
            return new EventExecutor() {
                public void execute(Listener listener, Event event) {
                    ((BlockListener) listener).onBlockFromTo((BlockFromToEvent) event);
                }
            };

        case LEAVES_DECAY:
            return new EventExecutor() {
                public void execute(Listener listener, Event event) {
                    ((BlockListener) listener).onLeavesDecay((LeavesDecayEvent) event);
                }
            };

        case SIGN_CHANGE:
            return new EventExecutor() {
                public void execute(Listener listener, Event event) {
                    ((BlockListener) listener).onSignChange((SignChangeEvent) event);
                }
            };

        case BLOCK_IGNITE:
            return new EventExecutor() {
                public void execute(Listener listener, Event event) {
                    ((BlockListener) listener).onBlockIgnite((BlockIgniteEvent) event);
                }
            };

        case REDSTONE_CHANGE:
            return new EventExecutor() {
                public void execute(Listener listener, Event event) {
                    ((BlockListener) listener).onBlockRedstoneChange((BlockRedstoneEvent) event);
                }
            };

        case BLOCK_BURN:
            return new EventExecutor() {
                public void execute(Listener listener, Event event) {
                    ((BlockListener) listener).onBlockBurn((BlockBurnEvent) event);
                }
            };

        case BLOCK_BREAK:
            return new EventExecutor() {
                public void execute(Listener listener, Event event) {
                    ((BlockListener) listener).onBlockBreak((BlockBreakEvent) event);
                }
            };

        case SNOW_FORM:
            return new EventExecutor() {
                public void execute(Listener listener, Event event) {
                    ((BlockListener) listener).onSnowForm((SnowFormEvent) event);
                }
            };

        case BLOCK_DISPENSE:
            return new EventExecutor() {
                public void execute(Listener listener, Event event) {
                    ((BlockListener) listener).onBlockDispense((BlockDispenseEvent) event);
                }
            };

        // Server Events
        case PLUGIN_ENABLE:
            return new EventExecutor() {
                public void execute(Listener listener, Event event) {
                    ((ServerListener) listener).onPluginEnable((PluginEnableEvent) event);
                }
            };

        case PLUGIN_DISABLE:
            return new EventExecutor() {
                public void execute(Listener listener, Event event) {
                    ((ServerListener) listener).onPluginDisable((PluginDisableEvent) event);
                }
            };

        case SERVER_COMMAND:
            return new EventExecutor() {
                public void execute(Listener listener, Event event) {
                    ((ServerListener) listener).onServerCommand((ServerCommandEvent) event);
                }
            };

        // World Events
        case CHUNK_LOAD:
            return new EventExecutor() {
                public void execute(Listener listener, Event event) {
                    ((WorldListener) listener).onChunkLoad((ChunkLoadEvent) event);
                }
            };

        case CHUNK_UNLOAD:
            return new EventExecutor() {
                public void execute(Listener listener, Event event) {
                    ((WorldListener) listener).onChunkUnload((ChunkUnloadEvent) event);
                }
            };

        case SPAWN_CHANGE:
            return new EventExecutor() {
                public void execute(Listener listener, Event event) {
                    ((WorldListener) listener).onSpawnChange((SpawnChangeEvent) event);
                }
            };

        case WORLD_SAVE:
            return new EventExecutor() {
                public void execute(Listener listener, Event event) {
                    ((WorldListener) listener).onWorldSave((WorldSaveEvent) event);
                }
            };

        case WORLD_LOAD:
            return new EventExecutor() {
                public void execute(Listener listener, Event event) {
                    ((WorldListener) listener).onWorldLoad((WorldLoadEvent) event);
                }
            };

        // Painting Events
        case PAINTING_PLACE:
            return new EventExecutor() {
                public void execute(Listener listener, Event event) {
                    ((EntityListener) listener).onPaintingPlace((PaintingPlaceEvent) event);
                }
            };

        case PAINTING_BREAK:
            return new EventExecutor() {
                public void execute(Listener listener, Event event) {
                    ((EntityListener) listener).onPaintingBreak((PaintingBreakEvent) event);
                }
            };

        // Entity Events
        case ENTITY_DAMAGE:
            return new EventExecutor() {
                public void execute(Listener listener, Event event) {
                    ((EntityListener) listener).onEntityDamage((EntityDamageEvent) event);
                }
            };

        case ENTITY_DEATH:
            return new EventExecutor() {
                public void execute(Listener listener, Event event) {
                    ((EntityListener) listener).onEntityDeath((EntityDeathEvent) event);
                }
            };

        case ENTITY_COMBUST:
            return new EventExecutor() {
                public void execute(Listener listener, Event event) {
                    ((EntityListener) listener).onEntityCombust((EntityCombustEvent) event);
                }
            };

        case ENTITY_EXPLODE:
            return new EventExecutor() {
                public void execute(Listener listener, Event event) {
                    ((EntityListener) listener).onEntityExplode((EntityExplodeEvent) event);
                }
            };

        case EXPLOSION_PRIME:
            return new EventExecutor() {
                public void execute(Listener listener, Event event) {
                    ((EntityListener) listener).onExplosionPrime((ExplosionPrimeEvent) event);
                }
            };

        case ENTITY_TARGET:
            return new EventExecutor() {
                public void execute(Listener listener, Event event) {
                    ((EntityListener) listener).onEntityTarget((EntityTargetEvent) event);
                }
            };

        case ENTITY_INTERACT:
            return new EventExecutor() {
                public void execute(Listener listener, Event event) {
                    ((EntityListener) listener).onEntityInteract((EntityInteractEvent) event);
                }
            };

        case CREATURE_SPAWN:
            return new EventExecutor() {
                public void execute(Listener listener, Event event) {
                    ((EntityListener) listener).onCreatureSpawn((CreatureSpawnEvent) event);
                }
            };

        case PIG_ZAP:
            return new EventExecutor() {
                public void execute(Listener listener, Event event) {
                    ((EntityListener) listener).onPigZap((PigZapEvent) event);
                }
            };

        case CREEPER_POWER:
            return new EventExecutor() {
                public void execute(Listener listener, Event event) {
                    ((EntityListener) listener).onCreeperPower((CreeperPowerEvent) event);
                }
            };

        // Vehicle Events
        case VEHICLE_CREATE:
            return new EventExecutor() {
                public void execute(Listener listener, Event event) {
                    ((VehicleListener) listener).onVehicleCreate((VehicleCreateEvent) event);
                }
            };

        case VEHICLE_DAMAGE:
            return new EventExecutor() {
                public void execute(Listener listener, Event event) {
                    ((VehicleListener) listener).onVehicleDamage((VehicleDamageEvent) event);
                }
            };

        case VEHICLE_DESTROY:
            return new EventExecutor() {
                public void execute(Listener listener, Event event) {
                    ((VehicleListener) listener).onVehicleDestroy((VehicleDestroyEvent) event);
                }
            };

        case VEHICLE_COLLISION_BLOCK:
            return new EventExecutor() {
                public void execute(Listener listener, Event event) {
                    ((VehicleListener) listener).onVehicleBlockCollision((VehicleBlockCollisionEvent) event);
                }
            };

        case VEHICLE_COLLISION_ENTITY:
            return new EventExecutor() {
                public void execute(Listener listener, Event event) {
                    ((VehicleListener) listener).onVehicleEntityCollision((VehicleEntityCollisionEvent) event);
                }
            };

        case VEHICLE_ENTER:
            return new EventExecutor() {
                public void execute(Listener listener, Event event) {
                    ((VehicleListener) listener).onVehicleEnter((VehicleEnterEvent) event);
                }
            };

        case VEHICLE_EXIT:
            return new EventExecutor() {
                public void execute(Listener listener, Event event) {
                    ((VehicleListener) listener).onVehicleExit((VehicleExitEvent) event);
                }
            };

        case VEHICLE_MOVE:
            return new EventExecutor() {
                public void execute(Listener listener, Event event) {
                    ((VehicleListener) listener).onVehicleMove((VehicleMoveEvent) event);
                }
            };

        case VEHICLE_UPDATE:
            return new EventExecutor() {
                public void execute(Listener listener, Event event) {
                    ((VehicleListener) listener).onVehicleUpdate((VehicleUpdateEvent) event);
                }
            };

        // Weather Events
        case WEATHER_CHANGE:
            return new EventExecutor() {
                public void execute(Listener listener, Event event) {
                    ((WeatherListener) listener).onWeatherChange((WeatherChangeEvent) event);
                }
            };

        case THUNDER_CHANGE:
            return new EventExecutor() {
                public void execute(Listener listener, Event event) {
                    ((WeatherListener) listener).onThunderChange((ThunderChangeEvent) event);
                }
            };

        case LIGHTNING_STRIKE:
            return new EventExecutor() {
                public void execute(Listener listener, Event event) {
                    ((WeatherListener) listener).onLightningStrike((LightningStrikeEvent) event);
                }
            };

        // Custom Events
        case CUSTOM_EVENT:
            return new EventExecutor() {
                public void execute(Listener listener, Event event) {
                    ((CustomEventListener) listener).onCustomEvent(event);
                }
            };
        }

        throw new IllegalArgumentException("Event " + type + " is not supported");
    }

    public void enablePlugin(final Plugin plugin) {
        if (!(plugin instanceof JavaPlugin)) {
            throw new IllegalArgumentException("Plugin is not associated with this PluginLoader");
        }

        if (!plugin.isEnabled()) {
            JavaPlugin jPlugin = (JavaPlugin) plugin;

            String pluginName = jPlugin.getDescription().getName();

            try {
                jPlugin.setEnabled(true);
            } catch (Throwable ex) {
                server.getLogger().log(Level.SEVERE, "Error occurred while enabling " + plugin.getDescription().getFullName() + " (Is it up to date?): " + ex.getMessage(), ex);
            }

            // Perhaps abort here, rather than continue going, but as it stands,
            // an abort is not possible the way it's currently written
            server.getPluginManager().callEvent(new PluginEnableEvent(plugin));
        }
    }

    public void disablePlugin(Plugin plugin) {
        if (!(plugin instanceof JavaPlugin)) {
            throw new IllegalArgumentException("Plugin is not associated with this PluginLoader");
        }

        if (plugin.isEnabled()) {
            JavaPlugin jPlugin = (JavaPlugin) plugin;
            ClassLoader cloader = jPlugin.getClassLoader();

            try {
                jPlugin.setEnabled(false);
            } catch (Throwable ex) {
                server.getLogger().log(Level.SEVERE, "Error occurred while disabling " + plugin.getDescription().getFullName() + " (Is it up to date?): " + ex.getMessage(), ex);
            }

            server.getPluginManager().callEvent(new PluginDisableEvent(plugin));

            pluginLoader.removeLoader(jPlugin.getDescription().getName());
        }
    }
}
