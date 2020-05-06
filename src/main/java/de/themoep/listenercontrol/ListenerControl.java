package de.themoep.listenercontrol;

/*
 * ListenerControl
 * Copyright (c) 2020 Max Lee aka Phoenix616 (mail@moep.tv)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

import com.google.common.collect.Multimap;
import com.google.common.collect.MultimapBuilder;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredListener;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Level;

public final class ListenerControl extends JavaPlugin implements Listener {

    private final Multimap<String, EventListenerInfo> deactivatedEvents = MultimapBuilder.hashKeys().arrayListValues().build();
    private final Map<RegisteredListener, EventListenerInfo> unregisteredListeners = new LinkedHashMap<>();

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
        loadConfig();
        getCommand("listenercontrol").setExecutor(this);
    }

    public void loadConfig() {
        saveDefaultConfig();
        reloadConfig();

        for (Map.Entry<RegisteredListener, EventListenerInfo> entry : unregisteredListeners.entrySet()) {
            try {
                RegisteredListener listener = entry.getKey();
                EventListenerInfo info = entry.getValue();
                info.handlerList.register(listener);
                info.handlerList.bake();
                getLogger().log(Level.INFO, "Re-registered " + info.event.getName() + " " + info.priority + " listener of " + listener.getPlugin().getName());
            } catch (IllegalStateException e) {
                getLogger().log(Level.WARNING, e.getMessage());
            }
        }
        unregisteredListeners.clear();
        deactivatedEvents.clear();
        ConfigurationSection section = getConfig().getConfigurationSection("deactivated-events");
        if (section != null) {
            for (String plugin : section.getKeys(false)) {
                for (EventPriority priority : EventPriority.values()) {
                    for (String eventName : section.getStringList(priority.name().toLowerCase() + "." + plugin)) {
                        Class<?> eClass = null;
                        try {
                            eClass = Class.forName("org.bukkit.event." + eventName);
                        } catch (ClassNotFoundException e) {
                            try {
                                eClass = Class.forName(eventName);
                            } catch (ClassNotFoundException ignored) {
                            }
                        }
                        if (eClass != null) {
                            if (Event.class.isAssignableFrom(eClass)) {
                                try {
                                    Method getHandlerList = eClass.getMethod("getHandlerList");
                                    try {
                                        HandlerList handlerList = (HandlerList) getHandlerList.invoke(null);
                                        Plugin p = getServer().getPluginManager().getPlugin(plugin);
                                        if (p != null) {
                                            unregister(p, handlerList, priority, (Class<? extends Event>) eClass);
                                        }
                                        deactivatedEvents.put(plugin, new EventListenerInfo(handlerList, priority, (Class<? extends Event>) eClass));
                                    } catch (IllegalAccessException | InvocationTargetException | ClassCastException e) {
                                        getLogger().log(Level.SEVERE, "Could not get HandlerList of event " + eClass + "!", e);
                                    }
                                } catch (NoSuchMethodException e) {
                                    getLogger().log(Level.WARNING, "Event " + eClass + " can not be listened for! (it does not have a static getHandlerList method)");
                                }
                            } else {
                                getLogger().log(Level.WARNING, "Class " + eClass + " does not extend org.bukkit.event.Event!");
                            }
                        } else {
                            getLogger().log(Level.WARNING, "Class " + eventName + " not found!");
                        }
                    }
                }
            }
        }
    }

    private void unregister(Plugin plugin, HandlerList handlerList, EventPriority priority, Class<? extends Event> eClass) {
        for (RegisteredListener listener : handlerList.getRegisteredListeners()) {
            if (listener.getPlugin() == plugin && listener.getPriority() == priority) {
                handlerList.unregister(listener);
                unregisteredListeners.put(listener, new EventListenerInfo(handlerList, priority, eClass));
                getLogger().log(Level.INFO, "Unregistered " + eClass.getName() + " " + priority + " listener of " + plugin.getName());
            }
        }
    }

    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length > 0) {
            if ("reload".equalsIgnoreCase(args[0]) && sender.hasPermission("listenercontrol.command.reload")) {
                loadConfig();
                sender.sendMessage(ChatColor.YELLOW + "Config reloaded!");
                return true;
            }
        }
        return false;
    }

    @EventHandler
    public void onPluginEnable(PluginEnableEvent e) {
        for (EventListenerInfo info : deactivatedEvents.get(e.getPlugin().getName())) {
            unregister(e.getPlugin(), info.handlerList, info.priority, info.event);
        }
    }

    @EventHandler
    public void onPluginEnable(PluginDisableEvent e) {
        if (deactivatedEvents.containsKey(e.getPlugin().getName())) {
            unregisteredListeners.keySet().removeIf(l -> l.getPlugin() == e.getPlugin());
        }
    }

    private class EventListenerInfo {
        private final HandlerList handlerList;
        private final EventPriority priority;
        private final Class<? extends Event> event;

        EventListenerInfo(HandlerList handlerList, EventPriority priority, Class<? extends Event> event) {
            this.handlerList = handlerList;
            this.priority = priority;
            this.event = event;
        }
    }
}
