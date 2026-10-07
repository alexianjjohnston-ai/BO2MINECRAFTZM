package com.zombiecraft.block;

import com.zombiecraft.net.Payloads;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The map's own blocks. Their textures are neutral placeholders in the jar; the generated "Block Ops 2" resource pack (bo2/TexturePack)
 * replaces them with Black Ops II images. Ids must match tools/gen_blocks.py.
 */
public final class ModBlocks {
	private ModBlocks() {}

	public static final Map<String, Block> ALL = new LinkedHashMap<>();

	/** Boards nailed across a window: drawn as rough planks, but a full solid cell to walk into. Runs along the wall (axis x or z). */
	public static class BoardBlock extends Block {
		public static final EnumProperty<Direction.Axis> AXIS = BlockStateProperties.HORIZONTAL_AXIS;

		public BoardBlock(Properties p) {
			super(p);
			registerDefaultState(stateDefinition.any().setValue(AXIS, Direction.Axis.X));
		}

		@Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> b) { b.add(AXIS); }

		@Override protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) { return Shapes.block(); }
	}

	private static Block reg(String id, SoundType sound, int light, boolean see, boolean board) {
		ResourceKey<Block> key = ResourceKey.create(Registries.BLOCK, Payloads.id(id));
		BlockBehaviour.Properties p = BlockBehaviour.Properties.of().strength(2.0f, 6.0f).sound(sound).setId(key);
		if (light > 0) p = p.lightLevel(s -> light);
		if (see) p = p.noOcclusion();
		Block b = Registry.register(BuiltInRegistries.BLOCK, key, board ? new BoardBlock(p) : new Block(p));
		ResourceKey<Item> ik = ResourceKey.create(Registries.ITEM, Payloads.id(id));
		Registry.register(BuiltInRegistries.ITEM, ik, new BlockItem(b, new Item.Properties().setId(ik).useBlockDescriptionPrefix()));
		ALL.put(id, b);
		return b;
	}

	public static void register() {
		reg("cinder_block", SoundType.STONE, 0, false, false);
		reg("concrete_wall", SoundType.STONE, 0, false, false);
		reg("metal_panel", SoundType.METAL, 0, false, false);
		reg("asphalt", SoundType.STONE, 0, false, false);
		reg("depot_tile", SoundType.STONE, 0, false, false);
		reg("wood_floor", SoundType.WOOD, 0, false, false);
		reg("ground", SoundType.GRAVEL, 0, false, false);
		reg("grass", SoundType.GRASS, 0, false, false);
		reg("glass_brick", SoundType.GLASS, 0, false, false);
		reg("neon", SoundType.GLASS, 12, false, false);
		reg("barricade_board", SoundType.WOOD, 0, true, true);
	}
}
