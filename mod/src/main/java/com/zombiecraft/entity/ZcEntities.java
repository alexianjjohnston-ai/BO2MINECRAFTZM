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
	public static EntityType<ZcBox> BOX;
	public static EntityType<ZcProp> PROP;
	/** A thrown grenade: the same model-drawing entity as PROP, but it moves, so its position is sent every tick. */
	public static EntityType<ZcProp> GRENADE;

	public static void register() {
		ResourceKey<EntityType<?>> key = ResourceKey.create(Registries.ENTITY_TYPE, Payloads.id("zombie"));
		ZOMBIE = Registry.register(BuiltInRegistries.ENTITY_TYPE, key,
				EntityType.Builder.<Zombie>of((type, level) -> new ZcZombie(type, level), MobCategory.MONSTER)
						.sized(0.6f, 1.95f).clientTrackingRange(10).build(key));
		FabricDefaultAttributeRegistry.register(ZOMBIE, ZcZombie.createZcAttributes());
		ResourceKey<EntityType<?>> boxKey = ResourceKey.create(Registries.ENTITY_TYPE, Payloads.id("mystery_box"));
		BOX = Registry.register(BuiltInRegistries.ENTITY_TYPE, boxKey,
				EntityType.Builder.<ZcBox>of(ZcBox::new, MobCategory.MISC).sized(2.2f, 0.9f).clientTrackingRange(10).updateInterval(20).noSave().build(boxKey));
		ResourceKey<EntityType<?>> propKey = ResourceKey.create(Registries.ENTITY_TYPE, Payloads.id("prop"));
		PROP = Registry.register(BuiltInRegistries.ENTITY_TYPE, propKey,
				EntityType.Builder.<ZcProp>of(ZcProp::new, MobCategory.MISC).sized(1.0f, 1.0f).clientTrackingRange(10).updateInterval(20).noSave().build(propKey));
		ResourceKey<EntityType<?>> grenadeKey = ResourceKey.create(Registries.ENTITY_TYPE, Payloads.id("grenade"));
		GRENADE = Registry.register(BuiltInRegistries.ENTITY_TYPE, grenadeKey,
				EntityType.Builder.<ZcProp>of(ZcProp::new, MobCategory.MISC).sized(0.2f, 0.2f).clientTrackingRange(10).updateInterval(1).noSave().build(grenadeKey));
	}
}
