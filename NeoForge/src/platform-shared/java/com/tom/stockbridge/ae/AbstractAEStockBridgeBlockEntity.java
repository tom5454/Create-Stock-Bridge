package com.tom.stockbridge.ae;

import java.util.ArrayList;
import java.util.List;

import net.createmod.catnip.data.Pair;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

import com.simibubi.create.content.logistics.packager.InventorySummary;

import com.google.common.collect.ImmutableSet;

import com.tom.stockbridge.ae.menu.AEStockBridgeMenu;
import com.tom.stockbridge.block.entity.AbstractStockBridgeBlockEntity;

import appeng.api.config.Actionable;
import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.GridFlags;
import appeng.api.networking.GridHelper;
import appeng.api.networking.IGridNodeListener;
import appeng.api.networking.IManagedGridNode;
import appeng.api.networking.crafting.ICraftingLink;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.networking.crafting.ICraftingRequester;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.IStorageMounts;
import appeng.api.storage.IStorageProvider;
import appeng.api.storage.MEStorage;
import appeng.api.util.AECableType;
import appeng.helpers.IPriorityHost;
import appeng.helpers.MultiCraftingTracker;
import appeng.hooks.ticking.TickHandler;
import appeng.me.helpers.BlockEntityNodeListener;
import appeng.me.helpers.IGridConnectedBlockEntity;
import appeng.me.helpers.MachineSource;
import appeng.menu.ISubMenu;
import appeng.menu.MenuOpener;
import appeng.menu.locator.MenuLocators;
import it.unimi.dsi.fastutil.objects.Object2LongMap;

public abstract class AbstractAEStockBridgeBlockEntity extends AbstractStockBridgeBlockEntity implements
IGridConnectedBlockEntity, IPriorityHost, IStorageProvider, ICraftingProvider, ICraftingRequester {
	protected final IManagedGridNode mainNode;
	protected final MEStorage inventory;
	private int priority = 100;
	private boolean wasOnline;
	private boolean setChangedQueued;
	protected MultiCraftingTracker craftingTracker;
	protected InventorySummary items;
	protected final IActionSource actionSource;
	private KeyCounter itemRequests = new KeyCounter();

	public AbstractAEStockBridgeBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
		super(type, pos, state);
		this.mainNode = this.createMainNode().setVisualRepresentation(AERegistration.BRIDGE_BLOCK.asItem())
				.setInWorldNode(true).setTagName("proxy").setFlags(GridFlags.REQUIRE_CHANNEL).setIdlePowerUsage(4d);
		this.inventory = new BridgeStorge();
		this.craftingTracker = new MultiCraftingTracker(this, 8);
		this.getMainNode().addService(IStorageProvider.class, this);
		this.getMainNode().addService(ICraftingRequester.class, this);
		this.getMainNode().addService(ICraftingProvider.class, this);
		this.actionSource = new MachineSource(mainNode::getNode);
		this.onGridConnectableSidesChanged();
	}

	protected IManagedGridNode createMainNode() {
		return GridHelper.createManagedNode(this, BlockEntityNodeListener.INSTANCE);
	}

	@Override
	protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
		super.write(tag, registries, clientPacket);
		if(!clientPacket) {
			this.getMainNode().saveToNBT(tag);
			tag.putInt("priority", this.getPriority());
			this.craftingTracker.writeToNBT(tag);

			ListTag list = new ListTag();
			for (final Object2LongMap.Entry<AEKey> input : itemRequests) {
				CompoundTag t = input.getKey().toTagGeneric(registries);
				t.putLong("cnt", input.getLongValue());
				list.add(t);
			}
			tag.put("requests", list);
		}
	}

	@Override
	protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
		super.read(tag, registries, clientPacket);
		if(!clientPacket) {
			this.getMainNode().loadFromNBT(tag);
			this.priority = tag.getInt("priority");
			this.craftingTracker.readFromNBT(tag);

			itemRequests.clear();
			ListTag list = tag.getList("requests", Tag.TAG_COMPOUND);
			for(int i = 0; i < list.size(); ++i) {
				var t = list.getCompound(i);
				var key = AEKey.fromTagGeneric(registries, t);
				long value = t.getLong("cnt");
				itemRequests.add(key, value);
			}
		}
	}

	@Override
	public final IManagedGridNode getMainNode() {
		return this.mainNode;
	}

	@Override
	public AECableType getCableConnectionType(final Direction dir) {
		return AECableType.SMART;
	}

	@Override
	public void onChunkUnloaded() {
		super.onChunkUnloaded();
		this.getMainNode().destroy();
	}

	public void onReady() {
		this.getMainNode().create(this.getLevel(), this.getBlockPos());
	}

	protected final void onGridConnectableSidesChanged() {
		this.getMainNode().setExposedOnSides(this.getGridConnectableSides(null));
	}

	@Override
	public void invalidate() {
		super.invalidate();
		this.getMainNode().destroy();
	}

	@Override
	public void clearRemoved() {
		super.clearRemoved();
		this.scheduleInit();
	}

	@Override
	public void saveChanges() {
		if (this.level == null) {
			return;
		}
		if (this.level.isClientSide) {
			this.setChanged();
		} else {
			this.level.blockEntityChanged(this.worldPosition);
			if (!this.setChangedQueued) {
				TickHandler.instance().addCallable((LevelAccessor) null, this::setChangedAtEndOfTick);
				this.setChangedQueued = true;
			}
		}
	}

	protected void scheduleInit() {
		GridHelper.onFirstTick(this, AbstractAEStockBridgeBlockEntity::onReady);
	}

	private Object setChangedAtEndOfTick(final Level level) {
		this.setChanged();
		this.setChangedQueued = false;
		return null;
	}

	@Override
	public int getPriority() {
		return this.priority;
	}

	@Override
	public int getPatternPriority() {
		return getPriority();
	}

	@Override
	public void setPriority(final int newValue) {
		this.priority = newValue;
		setChanged();
		this.remountStorage();
	}

	private void remountStorage() {
		IStorageProvider.requestUpdate(this.getMainNode());
		ICraftingProvider.requestUpdate(this.getMainNode());
	}

	@Override
	public void onMainNodeStateChanged(final IGridNodeListener.State reason) {
		final boolean currentOnline = this.getMainNode().isOnline();
		if (this.wasOnline != currentOnline) {
			this.wasOnline = currentOnline;
			this.remountStorage();
			setChanged();
		}
	}

	@Override
	public void tick() {
		super.tick();
		if (!level.isClientSide && level.getGameTime() % 20 == Math.abs(worldPosition.hashCode() % 20)) {
			items = behaviour.getItems();
			remountStorage();//TODO improve speed

			List<Pair<AEKey, Integer>> toRemove = new ArrayList<>();
			for (final Object2LongMap.Entry<AEKey> input : itemRequests) {
				int req = behaviour.requestItem(((AEItemKey) input.getKey()).toStack(), (int) input.getLongValue());
				toRemove.add(Pair.of(input.getKey(), req));
			}
			toRemove.forEach(p -> itemRequests.remove(p.getFirst(), p.getSecond()));
			if (!toRemove.isEmpty()) {
				sendPulseNextSync();
				notifyUpdate();
			}
			itemRequests.removeZeros();
		}
	}

	@Override
	public void mountInventories(IStorageMounts mounts) {
		if (this.getMainNode().isOnline()) {
			mounts.mount(this.inventory, this.priority);
		}
	}

	private class BridgeStorge implements MEStorage {

		@Override
		public Component getDescription() {
			return AERegistration.BRIDGE_BLOCK.asItem().getDescription();
		}

		@Override
		public long extract(AEKey what, long amount, Actionable mode, IActionSource source) {
			if (items != null && what instanceof AERemoteItemKey is) {
				int count = items.getCountOf(is.getReadOnlyStack());
				long max = Math.min(count, amount);
				if (mode == Actionable.MODULATE) {
					items.erase(is.toStack((int) max));
				}
				return max;
			}
			return 0L;
		}

		@Override
		public long insert(AEKey what, long amount, Actionable mode, IActionSource source) {
			return what.getType() == RemoteItemKeyType.INSTANCE ? amount : 0L;
		}

		@Override
		public void getAvailableStacks(KeyCounter out) {
			if (items != null) {
				for (var item : items.getStacks()) {
					out.add(AERemoteItemKey.of(item.stack), item.count);
				}
			}
		}

		@Override
		public boolean isPreferredStorageFor(AEKey what, IActionSource source) {
			return what.getType() == RemoteItemKeyType.INSTANCE;
		}
	}

	@Override
	public ItemStack getMainMenuIcon() {
		return AERegistration.BRIDGE_BLOCK.asStack();
	}

	@Override
	public void returnToMainMenu(Player player, ISubMenu arg1) {
		MenuOpener.returnTo(AEStockBridgeMenu.TYPE, player, MenuLocators.forBlockEntity(this));
	}

	public void openConfigMenu(final Player player) {
		MenuOpener.open(AEStockBridgeMenu.TYPE, player, MenuLocators.forBlockEntity(this));
	}

	@Override
	public InventorySummary fetchSummaryFromPackager() {
		if (getPackager() == null)return InventorySummary.EMPTY;

		InventorySummary sum = new InventorySummary();

		mainNode.getGrid().getStorageService().getCachedInventory().forEach(e -> {
			var item = e.getKey();
			var amount = e.getLongValue();
			if (item instanceof AEItemKey i) {
				sum.add(i.toStack(), (int) amount);
			} else if (item instanceof AERemoteItemKey i) {
				// TODO exclude loop
			}
		});
		//mainNode.getGrid().getCraftingService().getCraftables(k -> k instanceof AEItemKey);

		return sum;
	}

	@Override
	public List<IPatternDetails> getAvailablePatterns() {
		if (items != null) {
			List<IPatternDetails> l = new ArrayList<>();
			for (var item : items.getStacks()) {
				l.add(VirtualPattern.of(AEItemKey.of(item.stack)));
			}
			return l;
		}
		return List.of();
	}

	@Override
	public boolean pushPattern(IPatternDetails pattern, KeyCounter[] items) {
		if (pattern instanceof VirtualPattern p) {
			long cnt = 0;
			for (KeyCounter keyCounter : items) {
				for (final Object2LongMap.Entry<AEKey> input : keyCounter) {
					cnt += input.getLongValue();
				}
			}
			itemRequests.add(p.getResult(), cnt);
			return true;
		}
		return false;
	}

	@Override
	public boolean isBusy() {
		return false;
	}

	@Override
	public ImmutableSet<ICraftingLink> getRequestedJobs() {
		return this.craftingTracker.getRequestedJobs();
	}

	@Override
	public void jobStateChange(ICraftingLink link) {
		this.craftingTracker.jobStateChange(link);
	}
}
