package com.zombiecraft.entity;

import com.zombiecraft.net.Payloads;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.monster.Zombie;

public final class ZcEntities {
	private ZcEntities() {}

	public static EntityType<Zombie> ZOMBIE;

	public static void register() {
		ResourceKey<EntityType<?>> key = ResourceKey.create(Registries.ENTITY_TYPE, Payloads.id("zombie"));
		ZOMBIE = Registry.register(BuiltInRegistries.ENTITY_TYPE, key,
				EntityType.Builder.<Zombie>of((type, level) -> new ZcZombie(type, level), MobCategory.MONSTER)
						.sized(0.6f, 1.95f).clientTrackingRange(10).build(key));
		FabricDefaultAttributeRegistry.register(ZOMBIE, ZcZombie.createZcAttributes());
	}
}
