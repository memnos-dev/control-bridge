package dev.memnos.controlbridge;

import net.citizensnpcs.api.persistence.Persist;
import net.citizensnpcs.api.trait.Trait;
import net.citizensnpcs.api.trait.TraitName;

/**
 * Persists the controller-side NPC id on a Citizens NPC. Survives restarts,
 * so the id<->NPC mapping can be rebuilt by scanning the registry. Names are
 * display only and are never used as identity.
 */
@TraitName("npcidentity")
public final class IdentityTrait extends Trait {

    @Persist
    private String npcId = "";

    public IdentityTrait() {
        super("npcidentity");
    }

    public String getNpcId() {
        return npcId;
    }

    public void setNpcId(String npcId) {
        this.npcId = npcId;
    }
}