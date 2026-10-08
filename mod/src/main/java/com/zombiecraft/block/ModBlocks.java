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

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.phys.shapes.EntityCollisionContext;

import java.io.InputStreamReader;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The map's own blocks. Their textures are neutral placeholders in the jar; the generated "Block Ops 2" resource pack (bo2/TexturePack)
 * replaces them with Black Ops II images. Ids must match tools/gen_blocks.py.
 */
public final class ModBlocks {
	private ModBlocks() {}

	public static final Map<String, Block> ALL = new LinkedHashMap<>();
	/** The boards and the players-only window clip: shots pass through both (Barrier.shotClip). */
	public static Block BOARD, WINDOW_CLIP;

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

	/** Invisible blocks: a window's clip (stops players only, so zombies climb through and shots pass) and a closed door's collision (stops everyone). */
	public static class ClipBlock extends Block {
		private final boolean playersOnly;

		public ClipBlock(Properties p, boolean playersOnly) { super(p); this.playersOnly = playersOnly; }

		@Override protected RenderShape getRenderShape(BlockState state) { return RenderShape.INVISIBLE; }

		@Override protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
			if (!playersOnly) return Shapes.block();
			return ctx instanceof EntityCollisionContext e && e.getEntity() instanceof Player ? Shapes.block() : Shapes.empty();
		}

		/** Windows stay un-targetable (bullets and the repair prompt use the sight line); doors must be targetable to be bought. */
		@Override protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) { return playersOnly ? Shapes.empty() : Shapes.block(); }
	}

	/** Furniture and props with a model made of several boxes (resources/decor.json, from tools/gen_decor.py). Faces a horizontal direction. */
	public static class DecorBlock extends Block {
		public static final EnumProperty<Direction> FACING = BlockStateProperties.HORIZONTAL_FACING;
		private final VoxelShape[] shapes = new VoxelShape[4];
		private final boolean collide;

		public DecorBlock(Properties p, double[] b, boolean collide) {
			super(p);
			this.collide = collide;
			registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
			for (Direction d : Direction.Plane.HORIZONTAL) {
				double[] r = rotate(b, d);
				shapes[d.get2DDataValue()] = Block.box(r[0], r[1], r[2], r[3], r[4], r[5]);
			}
		}

		/** The north-facing box turned to face d (blockstate y = 0, 90, 180, 270 clockwise from above). */
		private static double[] rotate(double[] b, Direction d) {
			return switch (d) {
				case EAST -> new double[] {16 - b[5], b[1], b[0], 16 - b[2], b[4], b[3]};
				case SOUTH -> new double[] {16 - b[3], b[1], 16 - b[5], 16 - b[0], b[4], 16 - b[2]};
				case WEST -> new double[] {b[2], b[1], 16 - b[3], b[5], b[4], 16 - b[0]};
				default -> b;
			};
		}

		@Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> b) { b.add(FACING); }

		@Override protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) { return shapes[state.getValue(FACING).get2DDataValue()]; }

		@Override protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) { return collide ? getShape(state, level, pos, ctx) : Shapes.empty(); }
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
		reg("light_panel", SoundType.GLASS, 12, false, false);
		for (String id : new String[] {"plaster_wall", "concrete_floor", "yellow_trim", "green_panel", "red_brick", "door_metal", "door_wood", "road_bus", "road_stop"})
			reg(id, SoundType.STONE, 0, false, false);
		BOARD = reg("barricade_board", SoundType.WOOD, 0, true, true);
		for (String id : new String[] {"window_clip", "door_clip"}) {
			ResourceKey<Block> key = ResourceKey.create(Registries.BLOCK, Payloads.id(id));
			Block clip = Registry.register(BuiltInRegistries.BLOCK, key, new ClipBlock(BlockBehaviour.Properties.of().strength(-1f, 3600000f).noOcclusion().noLootTable().setId(key), id.equals("window_clip")));
			if (id.equals("window_clip")) WINDOW_CLIP = clip;
		}
		try (var in = ModBlocks.class.getResourceAsStream("/assets/zombiecraft/decor.json")) {
			JsonObject all = JsonParser.parseReader(new InputStreamReader(in)).getAsJsonObject();
			for (var e : all.entrySet()) {
				JsonObject o = e.getValue().getAsJsonObject();
				var arr = o.getAsJsonArray("box");
				double[] box = new double[6];
				for (int i = 0; i < 6; i++) box[i] = arr.get(i).getAsDouble();
				int light = o.get("light").getAsInt();
				ResourceKey<Block> key = ResourceKey.create(Registries.BLOCK, Payloads.id(e.getKey()));
				BlockBehaviour.Properties p = BlockBehaviour.Properties.of().strength(2.0f, 6.0f).noOcclusion().setId(key);
				if (light > 0) p = p.lightLevel(s -> light);
				Block b = Registry.register(BuiltInRegistries.BLOCK, key, new DecorBlock(p, box, o.get("collide").getAsBoolean()));
				ResourceKey<Item> ik = ResourceKey.create(Registries.ITEM, Payloads.id(e.getKey()));
				Registry.register(BuiltInRegistries.ITEM, ik, new BlockItem(b, new Item.Properties().setId(ik).useBlockDescriptionPrefix()));
				ALL.put(e.getKey(), b);
			}
		} catch (java.io.IOException ex) {
			throw new IllegalStateException("decor.json missing", ex);
		}
	}
}
