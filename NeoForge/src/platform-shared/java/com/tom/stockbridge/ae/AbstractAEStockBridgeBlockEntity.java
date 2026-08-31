package com.tom.stockbridge.ae;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

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

import com.simibubi.create.content.logistics.BigItemStack;
import com.simibubi.create.content.logistics.packager.InventorySummary;
import com.simibubi.create.content.logistics.packager.PackagingRequest;

import com.google.common.collect.ImmutableSet;

import com.tom.stockbridge.ae.menu.AEStockBridgeMenu;
import com.tom.stockbridge.block.entity.AbstractStockBridgeBlockEntity;

import appeng.api.config.Actionable;
import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.GridFlags;
import appeng.api.networking.GridHelper;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNodeListener;
import appeng.api.networking.IManagedGridNode;
import appeng.api.networking.IStackWatcher;
import appeng.api.networking.crafting.ICraftingLink;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.networking.crafting.ICraftingRequester;
import appeng.api.networking.crafting.ICraftingService;
import appeng.api.networking.security.IActionSource;
import appeng.api.networking.storage.IStorageWatcherNode;
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
IGridConnectedBlockEntity, IPriorityHost, IStorageProvider, ICraftingProvider, ICraftingRequester, IStorageWatcherNode {
	protected final IManagedGridNode mainNode;
	protected final MEStorage inventory;
	private int priority = 100;
	private boolean wasOnline;
	private boolean setChangedQueued;
	protected MultiCraftingTracker craftingTracker;
	protected InventorySummary items;
	protected final IActionSource actionSource;
	private KeyCounter itemRequests = new KeyCounter();
	public record CraftingTask(AEKey what, long amount, int orderId) {}

	protected static final class CommittedRequest {
		private final long bridgeRequestId;
		private final PackagingRequest request;
		private final int initialCount;

		CommittedRequest(long bridgeRequestId, PackagingRequest request) {
			this.bridgeRequestId = bridgeRequestId;
			this.request = request;
			this.initialCount = request.getCount();
		}

		public long bridgeRequestId() { return bridgeRequestId; }
		public PackagingRequest request() { return request; }
		public int initialCount() { return initialCount; }
	}

	public record CommittedRequestSnapshot(
			long bridgeRequestId,
			ItemStack item,
			int remaining,
			String address,
			int orderId,
			long bufferedForItem,
			long craftingForItem) {
	}

	protected final Map<AEKey, Deque<CommittedRequest>> pendingPackageRequests = new HashMap<>();
	private long nextBridgeRequestId = 1L;
	private final Deque<CraftingTask> craftQueue = new ArrayDeque<>();
	private final AEKey[] craftSlotItem = new AEKey[8];
	private final long[] craftSlotAmount = new long[8];
	private final int[] craftSlotOrderId = new int[8];
	private final Map<ICraftingLink, CraftingTask> activeCraftTasks = new IdentityHashMap<>();
	protected final Map<AEKey, Long> fulfillmentDueTick = new HashMap<>();
	protected final Map<AEKey, Long> lastRequestedTick = new HashMap<>();

	protected static final int FULFILLMENT_COALESCE_TICKS = 5;

	public AbstractAEStockBridgeBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
		super(type, pos, state);
		this.mainNode = this.createMainNode().setVisualRepresentation(AERegistration.BRIDGE_BLOCK.asItem())
				.setInWorldNode(true).setTagName("proxy").setFlags(GridFlags.REQUIRE_CHANNEL).setIdlePowerUsage(4d);
		this.inventory = new BridgeStorage();
		this.craftingTracker = new MultiCraftingTracker(this, 8);
		this.getMainNode().addService(IStorageProvider.class, this);
		this.getMainNode().addService(ICraftingRequester.class, this);
		this.getMainNode().addService(ICraftingProvider.class, this);
		this.getMainNode().addService(IStorageWatcherNode.class, this);
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

		cleanupFinishedCraftLinks();
		tickCrafting();

		if (!level.isClientSide && level.getGameTime() % 20 == Math.abs(worldPosition.hashCode()) % 20) {
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

	protected CommittedRequest rememberPendingPackageRequest(AEKey what, PackagingRequest request) {
		Deque<CommittedRequest> requests =
				pendingPackageRequests.computeIfAbsent(what, k -> new ArrayDeque<>());

		for (CommittedRequest existing : requests)
			if (existing.request() == request)
				return existing;

		CommittedRequest committed = new CommittedRequest(nextBridgeRequestId++, request);
		requests.addLast(committed);
		touchRequest(what);
		onCommittedRequestSetChanged(what);
		return committed;
	}

	protected long getPendingDemand(AEKey what) {
		Deque<CommittedRequest> requests = pendingPackageRequests.get(what);
		if (requests == null)
			return 0;

		long total = 0;
		for (CommittedRequest committed : requests)
			total += committed.request().getCount();
		return total;
	}

	protected CommittedRequest getFirstCommittedRequest(AEKey what) {
		Deque<CommittedRequest> requests = pendingPackageRequests.get(what);
		return requests == null ? null : requests.peekFirst();
	}

	protected void onCommittedRequestSetChanged(AEKey what) {
	}

	public boolean cancelCommittedRequest(long bridgeRequestId) {
		// TODO: future hook for cancelling requests via UI.
		for (var entry : new ArrayList<>(pendingPackageRequests.entrySet())) {
			AEKey what = entry.getKey();
			Deque<CommittedRequest> requests = entry.getValue();

			var it = requests.iterator();
			while (it.hasNext()) {
				CommittedRequest committed = it.next();
				if (committed.bridgeRequestId() != bridgeRequestId)
					continue;

				it.remove();
				if (requests.isEmpty()) {
					pendingPackageRequests.remove(what);
					fulfillmentDueTick.remove(what);
				}

				onCommittedRequestSetChanged(what);
				setChanged();
				notifyUpdate();
				return true;
			}
		}
		return false;
	}

	public List<CommittedRequestSnapshot> getCommittedRequestSnapshots() {
		// TODO: future hook for cancelling requests via UI.
		List<CommittedRequestSnapshot> result = new ArrayList<>();

		for (var entry : pendingPackageRequests.entrySet()) {
			if (!(entry.getKey() instanceof AEItemKey item))
				continue;

			long buffered = getCommittedBufferedAmount(item);
			long crafting = getCommittedCraftAmount(item);

			for (CommittedRequest committed : entry.getValue()) {
				PackagingRequest request = committed.request();
				if (request.isEmpty())
					continue;

				result.add(new CommittedRequestSnapshot(
						committed.bridgeRequestId(),
						request.item().copy(),
						request.getCount(),
						request.address(),
						request.orderId(),
						buffered,
						crafting));
			}
		}

		return result;
	}

	protected long getCommittedBufferedAmount(AEItemKey what) {
		return 0;
	}

	protected long getCommittedCraftAmount(AEKey what) {
		long committed = 0;
		for (CraftingTask task : craftQueue)
			if (what.equals(task.what()))
				committed += task.amount();
		for (int i = 0; i < craftSlotItem.length; i++)
			if (what.equals(craftSlotItem[i]))
				committed += craftSlotAmount[i];
		for (CraftingTask task : activeCraftTasks.values())
			if (what.equals(task.what()))
				committed += task.amount();
		return committed;
	}

	protected boolean hasPendingPackageRequest(AEKey what) {
		Deque<CommittedRequest> requests = pendingPackageRequests.get(what);
		if (requests == null)
			return false;

		for (CommittedRequest committed : requests)
			if (!committed.request().isEmpty())
				return true;

		return false;
	}

	protected void onCraftOutputAccepted(ICraftingLink link, AEKey what, long amount) {
		if (amount <= 0)
			return;

		CraftingTask active = activeCraftTasks.get(link);
		if (active != null && what.equals(active.what())) {
			long remaining = Math.max(0, active.amount() - amount);
			if (remaining == 0)
				activeCraftTasks.remove(link);
			else
				activeCraftTasks.put(link, new CraftingTask(active.what(), remaining, active.orderId()));
		}
		onCraftCommitmentChanged(what);
	}

	protected void onCraftCommitmentChanged(AEKey what) {
	}


	protected void markPendingFulfillmentDirty(AEKey what) {
		if (level == null || !hasPendingPackageRequest(what))
			return;

		fulfillmentDueTick.put(what, level.getGameTime() + FULFILLMENT_COALESCE_TICKS);
	}

	protected boolean isFulfillmentDue(AEKey what) {
		if (level == null)
			return false;
		Long due = fulfillmentDueTick.get(what);
		return due != null && level.getGameTime() >= due;
	}

	protected void clearFulfillmentDue(AEKey what) {
		fulfillmentDueTick.remove(what);
	}

	protected void removeCompletedPendingRequests(AEKey what) {
		Deque<CommittedRequest> requests = pendingPackageRequests.get(what);
		if (requests == null)
			return;

		boolean changed = requests.removeIf(committed -> committed.request().isEmpty());
		if (requests.isEmpty()) {
			pendingPackageRequests.remove(what);
			fulfillmentDueTick.remove(what);
		}
		if (changed)
			onCommittedRequestSetChanged(what);
	}


	private void cleanupFinishedCraftLinks() {
		var it = activeCraftTasks.entrySet().iterator();
		while (it.hasNext()) {
			var entry = it.next();
			ICraftingLink link = entry.getKey();
			if (link.isDone() || link.isCanceled()) {
				AEKey what = entry.getValue().what();
				it.remove();
				onCraftCommitmentChanged(what);
			}
		}
	}



	@Override
	public void mountInventories(IStorageMounts mounts) {
		if (this.getMainNode().isOnline()) {
			mounts.mount(this.inventory, this.priority);
		}
	}

	private class BridgeStorage implements MEStorage {

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
					if (item.stack.isEmpty())continue;
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
		IGrid grid = mainNode.getGrid();
		if (grid == null) return InventorySummary.EMPTY;

		InventorySummary sum = new InventorySummary();

		grid.getStorageService().getCachedInventory().forEach(e -> {
			var item = e.getKey();
			var amount = e.getLongValue();
			if (item instanceof AEItemKey i) {
				sum.add(i.toStack(), (int) amount);
			}
		});
		grid.getCraftingService().getCraftables(k -> k instanceof AEItemKey).forEach(k -> {
			AEItemKey key = (AEItemKey) k;
			ItemStack stack = key.toStack();
			if (sum.getCountOf(stack) <= 0) {
				sum.add(stack, BigItemStack.INF);
			}
		});

		return sum;
	}

	@Override
	public List<IPatternDetails> getAvailablePatterns() {
		if (items != null) {
			List<IPatternDetails> l = new ArrayList<>();
			for (var item : items.getStacks()) {
				if (item.stack.isEmpty())continue;
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
		if (link.isDone() || link.isCanceled()) {
			CraftingTask task = activeCraftTasks.remove(link);
			if (task != null)
				onCraftCommitmentChanged(task.what());
		}
	}

	@Override
	public void updateWatcher(IStackWatcher p0) {
		p0.setWatchAll(true);
	}

	@Override
	public void onStackChange(AEKey what, long amount) {
		onNetworkInventoryChanged(what);
	}

	protected void onNetworkInventoryChanged(AEKey what) {
	}

	public void requestCraft(AEKey what, long amount, int orderId) {
		if (amount <= 0)
			return;
		craftQueue.add(new CraftingTask(what, amount, orderId));
		onCraftCommitmentChanged(what);
	}

	protected void touchRequest(AEKey what) {
		lastRequestedTick.put(what, level.getGameTime());
	}

	private void tickCrafting() {
		IGrid grid = mainNode.getGrid();
		if (grid == null)
			return;
		ICraftingService cs = grid.getCraftingService();

		for (int slot = 0; slot < craftSlotItem.length; slot++) {
			if (craftSlotItem[slot] == null) {
				if (craftQueue.isEmpty())
					continue;
				CraftingTask next = craftQueue.poll();
				craftSlotItem[slot] = next.what();
				craftSlotAmount[slot] = next.amount();
				craftSlotOrderId[slot] = next.orderId();
			}

			boolean submitted = craftingTracker.handleCrafting(
					slot, craftSlotItem[slot], craftSlotAmount[slot],
					level, cs, actionSource);

			if (submitted) {
				AEKey what = craftSlotItem[slot];
				long amount = craftSlotAmount[slot];
				int orderId = craftSlotOrderId[slot];

				for (ICraftingLink link : craftingTracker.getRequestedJobs()) {
					if (!activeCraftTasks.containsKey(link)) {
						activeCraftTasks.put(link, new CraftingTask(what, amount, orderId));
						break;
					}
				}

				craftSlotItem[slot] = null;
				craftSlotAmount[slot] = 0L;
				craftSlotOrderId[slot] = 0;
				onCraftCommitmentChanged(what);
			}
		}
	}

}
