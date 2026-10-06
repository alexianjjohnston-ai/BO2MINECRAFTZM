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

	public static void register() {
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

	public static String weaponOf(ItemStack s) {
		return s.getItem() instanceof GunItem g ? g.weaponId : null;
	}
}
