package think.rpgitems.power.trigger;

import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import think.rpgitems.power.PowerLeftClick;
import think.rpgitems.power.PowerResult;

class LeftClick extends Trigger<PlayerInteractEvent, PowerLeftClick, Void, Void> {
    public LeftClick() {
        super(PlayerInteractEvent.class, PowerLeftClick.class, Void.class, Void.class, "LEFT_CLICK");
    }

    @Override
    public PowerResult<Void> run(PowerLeftClick power, Player player, ItemStack i, PlayerInteractEvent event) {
        return power.leftClick(player, i, event);
    }
}
