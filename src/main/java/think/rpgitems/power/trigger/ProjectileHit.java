package think.rpgitems.power.trigger;

import org.bukkit.entity.Player;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.inventory.ItemStack;
import think.rpgitems.power.PowerProjectileHit;
import think.rpgitems.power.PowerResult;

class ProjectileHit extends Trigger<ProjectileHitEvent, PowerProjectileHit, Void, Void> {
    public ProjectileHit() {
        super(ProjectileHitEvent.class, PowerProjectileHit.class, Void.class, Void.class, "PROJECTILE_HIT");
    }

    @Override
    public PowerResult<Void> run(PowerProjectileHit power, Player player, ItemStack i, ProjectileHitEvent event) {
        return power.projectileHit(player, i, event);
    }
}
