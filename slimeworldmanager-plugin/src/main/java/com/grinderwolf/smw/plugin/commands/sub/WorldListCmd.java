package com.grinderwolf.smw.plugin.commands.sub;

import com.grinderwolf.smw.plugin.SMWPlugin;
import com.grinderwolf.smw.plugin.commands.CommandManager;
import com.grinderwolf.smw.plugin.config.ConfigManager;
import lombok.Getter;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Getter
public class WorldListCmd implements Subcommand {

    private static final int MAX_ITEMS_PER_PAGE = 5;

    private final String usage = "list [slime] [page]";
    private final String description = "List all worlds. To only list slime worlds, use the 'slime' argument.";
    private final String permission = "smw.worldlist";

    @Override
    public boolean onCommand(CommandSender sender, String[] args) {
        Map<String, Boolean> loadedWorlds = Bukkit.getWorlds().stream().collect(Collectors.toMap(World::getName,
                world -> SMWPlugin.getInstance().getNms().getSlimeWorld(world) != null));

        boolean onlySlime = args.length > 0 && args[0].equalsIgnoreCase("slime");

        if (onlySlime) {
            loadedWorlds.entrySet().removeIf((entry) -> !entry.getValue());
        }

        int page;

        if (args.length == 0 || (args.length == 1 && args[0].equalsIgnoreCase("slime"))) {
            page = 1;
        } else {
            String pageString = args[args.length - 1];

            try {
                page = Integer.parseInt(pageString);

                if (page < 1) {
                    throw new NumberFormatException();
                }
            } catch (NumberFormatException ex) {
                sender.sendMessage(CommandManager.PREFIX + ChatColor.RED + "'" + pageString + "' is not a valid number.");

                return true;
            }
        }

        List<String> worldsList = new ArrayList<>(loadedWorlds.keySet());

        try {
            ConfigurationSection config = ConfigManager.getFile("worlds").getConfigurationSection("worlds");

            if (config != null) {
                config.getKeys(false).stream().filter(world -> !loadedWorlds.containsKey(world)).forEach(worldsList::add);
            }
        } catch (IOException ex) {
            sender.sendMessage(CommandManager.PREFIX + ChatColor.RED + "Failed to read the worlds config file.");
            ex.printStackTrace();
        }

        int offset = (page - 1) * MAX_ITEMS_PER_PAGE;
        double d = worldsList.size() / (double) MAX_ITEMS_PER_PAGE;
        int maxPages = ((int) d) + ((d > (int) d) ? 1 : 0);

        if (offset >= worldsList.size()) {
            sender.sendMessage(CommandManager.PREFIX + ChatColor.RED + "There " + (maxPages == 1 ? "is" :
                    "are") + " only " + maxPages + " page" + (maxPages == 1 ? "" : "s") + "!");

            return true;
        }

        worldsList.sort(String::compareTo);
        sender.sendMessage(CommandManager.PREFIX + "World list " + ChatColor.YELLOW + "[" + page + "/" + maxPages + "]" + ChatColor.GRAY + ":");

        for (int i = offset; i < MAX_ITEMS_PER_PAGE && i < worldsList.size(); i++) {
            String world = worldsList.get(i);

            if (loadedWorlds.containsKey(world)) {
                sender.sendMessage(ChatColor.GRAY + " - " + ChatColor.GREEN + world + " " + (loadedWorlds.get(world)
                        ? "" : ChatColor.BLUE + ChatColor.ITALIC.toString() + ChatColor.UNDERLINE + "(not in SRF)"));
            } else {
                sender.sendMessage(ChatColor.GRAY + " - " + ChatColor.RED + world);
            }
        }

        return true;
    }
}
