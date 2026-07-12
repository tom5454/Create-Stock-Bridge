package com.tom.stockbridge.ae;

import com.simibubi.create.content.equipment.wrench.IWrenchable;
import net.createmod.catnip.data.Iterate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition.Builder;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.BlockHitResult;

import com.simibubi.create.foundation.block.IBE;

public class AEStockBridgeBlock extends Block implements IBE<AEStockBridgeBlockEntity>, IWrenchable {
	public static final BooleanProperty POWERED = BlockStateProperties.POWERED;

	public AEStockBridgeBlock(Properties pProperties) {
		super(pProperties);
		registerDefaultState(defaultBlockState().setValue(POWERED, false));
	}

	@Override
	public BlockState getStateForPlacement(BlockPlaceContext context) {
		BlockPos pos = context.getClickedPos();
		BlockState placed = super.getStateForPlacement(context);
		if (placed == null)
			return null;
		return placed.setValue(POWERED, getPower(context.getLevel(), pos, null) > 0);
	}

	@Override
	public Class<AEStockBridgeBlockEntity> getBlockEntityClass() {
		return AEStockBridgeBlockEntity.class;
	}

	@Override
	public BlockEntityType<? extends AEStockBridgeBlockEntity> getBlockEntityType() {
		return AERegistration.BRIDGE_TILE.get();
	}

	@Override
	public void onRemove(BlockState pState, Level pLevel, BlockPos pPos, BlockState pNewState, boolean pMovedByPiston) {
		IBE.onRemove(pState, pLevel, pPos, pNewState);
	}

	@Override
	protected InteractionResult useWithoutItem(BlockState p_60503_, Level pLevel, BlockPos pPos, Player pPlayer,
			BlockHitResult p_60508_) {
		if (!pLevel.isClientSide) {
			if (pLevel.getBlockEntity(pPos) instanceof AEStockBridgeBlockEntity e)
				e.openConfigMenu(pPlayer);
		}
		return InteractionResult.SUCCESS;
	}

	@Override
	public void neighborChanged(BlockState state, Level worldIn, BlockPos pos, Block blockIn, BlockPos fromPos,
			boolean isMoving) {
		if (worldIn.isClientSide)
			return;

		withBlockEntityDo(worldIn, pos, link -> link.neighborChanged());
	}

	@Override
	protected void createBlockStateDefinition(Builder<Block, BlockState> builder) {
		super.createBlockStateDefinition(builder.add(POWERED));
	}

	public static int getPower(Level level, BlockPos pos, BlockPos exclude) {
		int power = 0;
		for (Direction d : Iterate.directions) {
			BlockPos from = pos.relative(d);
			if (exclude != null && exclude.equals(from))continue;
			power = Math.max(power, level.getSignal(from, d));
		}
		return power;
	}
}
