package id.veyrion.roleplay;

import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.Sound;

/** Daftar ras yang bisa didapat dari gacha. */
public enum Race {
    ELF("elf", "Elf", NamedTextColor.GREEN, Material.BOW, Sound.BLOCK_AMETHYST_BLOCK_CHIME),
    BEAST("beast", "Beast", NamedTextColor.RED, Material.BONE, Sound.ENTITY_WOLF_HOWL),
    HUMAN("human", "Human", NamedTextColor.AQUA, Material.IRON_SWORD, Sound.ENTITY_PLAYER_LEVELUP),
    DRAGON("dragon", "Dragon", NamedTextColor.DARK_PURPLE, Material.DRAGON_HEAD, Sound.ENTITY_ENDER_DRAGON_GROWL);

    public final String id;
    public final String display;
    public final NamedTextColor color;
    public final Material icon;
    public final Sound sound;

    Race(String id, String display, NamedTextColor color, Material icon, Sound sound) {
        this.id = id;
        this.display = display;
        this.color = color;
        this.icon = icon;
        this.sound = sound;
    }
}
