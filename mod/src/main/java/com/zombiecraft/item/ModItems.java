package com.zombiecraft.item;

import com.zombiecraft.net.Payloads;
import com.zombiecraft.sheet.Rows.WeaponDef;
import com.zombiecraft.sheet.Sheets;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.LinkedHashMap;
import java.util.Map;

/** One gun item per row of weapons.json. Firing is handled by the server from the player's input, not by the item. */
public final class ModItems {
	private ModItems() {}

	public static final Map<String, Item> GUNS = new LinkedHashMap<>();

	public static class GunItem extends Item {
		public final String weaponId;

		public GunItem(Properties props, String weaponId) {
			super(props);
			this.weaponId = weaponId;
		}
	}

	/** Plain props drawn with a BO2 mesh when the local cache has one (the box's teddy bear and the Mystery Box itself). */
	public static final Map<String, Item> PROPS = new LinkedHashMap<>();

	public static void register() {
		for (String id : new String[] {"teddy", "mystery_box"}) {
			ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, Payloads.id(id));
			PROPS.put(id, Registry.register(BuiltInRegistries.ITEM, key, new Item(new Item.Properties().setId(key).stacksTo(1))));
		}
		for (WeaponDef w : Sheets.WEAPONS) {
			if (w.upgrade()) continue;
			ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, Payloads.id(w.id()));
			Item item = Registry.register(BuiltInRegistries.ITEM, key, new GunItem(new Item.Properties().setId(key).stacksTo(1), w.id()));
			GUNS.put(w.id(), item);
		}
	}

	public static ItemStack stack(String weaponId, boolean pap) {
		ItemStack s = new ItemStack(GUNS.get(weaponId));
		if (pap) {
			s.set(DataComponents.CUSTOM_NAME, Component.literal(Sheets.weapon(Sheets.weapon(weaponId).papId()).name()));
			s.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
		}
		return s;
	}

	/** Key into bo2_models.json for the model this stack is drawn with, or null. */
	public static String modelKeyOf(ItemStack s) {
		if (s.getItem() instanceof GunItem g) return g.weaponId;
		return s.getItem() == PROPS.get("teddy") ? "teddy" : s.getItem() == PROPS.get("mystery_box") ? "mystery_box" : null;
	}

	public static String weaponOf(ItemStack s) {
		return s.getItem() instanceof GunItem g ? g.weaponId : null;
	}
}
