package com.tom.stockbridge.ae;

import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.HashMap;
import java.util.Map;

import org.apache.commons.lang3.mutable.MutableBoolean;

import net.createmod.catnip.data.Pair;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;

import com.simibubi.create.api.packager.InventoryIdentifier;
import com.simibubi.create.content.logistics.packager.IdentifiedInventory;
import com.simibubi.create.content.logistics.packager.PackagerBlockEntity;
import com.simibubi.create.content.logistics.packager.PackagingRequest;
import com.simibubi.create.content.logistics.stockTicker.PackageOrderWithCrafts;

import com.tom.stockbridge.util.StockBridgeInventory;

import appeng.api.config.Actionable;
import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.ICraftingLink;
import appeng.api.networking.crafting.ICraftingService;
import appeng.api.networking.energy.IEnergyService;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.storage.MEStorage;
import appeng.api.storage.StorageHelper;

public class AEStockBridgeBlockEntity extends AbstractAEStockBridgeBlockEntity {
	private StockBridgeInventory inv = new StockBridgeInventory(this);
	private final Map<AEItemKey, Long> requestBuffer = new HashMap<>();

	public AEStockBridgeBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
		super(type, pos, state);
	}

	@Override
	public void tick() {
		super.tick();
		if (!level.isClientSide) {
			drainPendingPackageRequests();
			final IGrid grid = this.getMainNode().getGrid();
			if (grid != null) {
				final MEStorage networkInv = grid.getStorageService().getInventory();
				final IEnergyService energySrc = grid.getEnergyService();
				if (!inv.isInsertEmpty()) {
					for (int slot = 0;slot < inv.insertW.getSlots();slot++) {
						ItemStack is = inv.insertW.getStackInSlot(slot);
						if (!is.isEmpty()) {
							AEItemKey what = AEItemKey.of(is);
							final int inserted = (int) StorageHelper.poweredInsert(energySrc, networkInv, what,
									is.getCount(), actionSource);
							if (inserted > 0) {
								inv.insertW.extractItem(slot, inserted, false);
							}
						}
					}
				}
				returnUnclaimedRequestBuffer(energySrc, networkInv);
				long now = level.getGameTime();
				sweepOrphans(inv.extractW, now, energySrc, networkInv);
			}
		}
	}

	private void returnUnclaimedRequestBuffer(IEnergyService energySrc, MEStorage networkInv) {
		for (AEItemKey item : new ArrayList<>(requestBuffer.keySet())) {
			if (hasPendingPackageRequest(item))
				continue;
			long amount = getRequestBuffered(item);
			if (amount <= 0)
				continue;
			long inserted = StorageHelper.poweredInsert(
					energySrc, networkInv, item, amount, actionSource);
			if (inserted > 0)
				extractRequestBuffered(item, inserted);
		}
	}

	private void sweepOrphans(IItemHandler buffer, long now, IEnergyService energySrc, MEStorage networkInv) {
		for (int i = 0; i < buffer.getSlots(); i++) {
			ItemStack stack = buffer.getStackInSlot(i);
			if (!stack.isEmpty()) {
				AEItemKey what = AEItemKey.of(stack);
				long lastSeen = lastRequestedTick.getOrDefault(what, 0L);

				// Never reclaim inventory that still backs a live Create request or AE2 craft.
				if (!hasPendingPackageRequest(what) && now - lastSeen > 100) {
					int inserted = (int) StorageHelper.poweredInsert(energySrc, networkInv, what, stack.getCount(), actionSource);
					if (inserted > 0) {
						buffer.extractItem(i, inserted, false);
					}
				}
			}
		}
	}

	private boolean hasRealAEPattern(IGrid grid, AEKey what) {
		ICraftingService cs = grid.getCraftingService();
		if (!cs.isCraftable(what)) return false;

		var patterns = cs.getCraftingFor(what);
		if (patterns == null || patterns.isEmpty()) return false;

		for (IPatternDetails pattern : patterns) {
			if (!(pattern instanceof VirtualPattern)) {
				return true;
			}
		}
		return false;
	}

	@Override
	public Pair<PackagerBlockEntity, PackagingRequest> processRequest(ItemStack stack, int amount, String address,
			int linkIndex, MutableBoolean finalLink, int orderId, PackageOrderWithCrafts context) {
		final IGrid grid = this.getMainNode().getGrid();
		if (grid == null) return null;
		PackagerBlockEntity packager = getPackager();
		if (packager == null)
			return null;

		AEItemKey what = AEItemKey.of(stack);
		MEStorage networkInv = grid.getStorageService().getInventory();
		IEnergyService energySrc = grid.getEnergyService();
		touchRequest(what);

		long dedicated = getRequestBuffered(what) + countInExtractBuffer(what);
		long availableAE = StorageHelper.poweredExtraction(
				energySrc, networkInv, what, Math.max(0, amount - dedicated),
				actionSource, Actionable.SIMULATE);
		boolean craftable = hasRealAEPattern(grid, what);

		if (dedicated + availableAE <= 0 && !craftable)
			return null;

		PackagingRequest request = PackagingRequest.create(
				stack, amount, address, linkIndex, finalLink, 0, orderId, context);
		rememberPendingPackageRequest(what, request);
		reconcileRequestTarget(what, orderId);
		return Pair.of(packager, request);
	}

	private void reconcileRequestTarget(AEItemKey what, int orderId) {
		IGrid grid = this.getMainNode().getGrid();
		if (grid == null)
			return;

		long demand = getPendingDemand(what);

		MEStorage networkInv = grid.getStorageService().getInventory();
		IEnergyService energySrc = grid.getEnergyService();

		if (demand <= 0) {
			long reserved = getRequestBuffered(what);
			if (reserved > 0) {
				long returned = StorageHelper.poweredInsert(
						energySrc, networkInv, what, reserved, actionSource);
				if (returned > 0)
					extractRequestBuffered(what, returned);
			}
			return;
		}

		long buffered = getRequestBuffered(what) + countInExtractBuffer(what);

		long requestBuffered = getRequestBuffered(what);
		long maxPrivateBuffer = Math.max(0, demand - countInExtractBuffer(what));
		if (requestBuffered > maxPrivateBuffer) {
			long excess = requestBuffered - maxPrivateBuffer;
			long returned = StorageHelper.poweredInsert(
					energySrc, networkInv, what, excess, actionSource);
			if (returned > 0)
				extractRequestBuffered(what, returned);
		}

		buffered = getRequestBuffered(what) + countInExtractBuffer(what);

		long reserveNeed = Math.max(0, demand - buffered);
		if (reserveNeed > 0) {
			long extracted = StorageHelper.poweredExtraction(
					energySrc, networkInv, what, reserveNeed, actionSource);
			if (extracted > 0) {
				insertRequestBuffered(what, extracted);
				buffered += extracted;
			}
		}

		long craftNeed = demand - buffered - getCommittedCraftAmount(what);
		if (craftNeed > 0 && hasRealAEPattern(grid, what))
			requestCraft(what, craftNeed, orderId);
	}

	@Override
	public long insertCraftedItems(ICraftingLink link, AEKey what, long amount, Actionable action) {
		if (!(what instanceof AEItemKey item))
			return 0;

		IGrid grid = this.getMainNode().getGrid();
		if (grid == null)
			return 0;

		MEStorage networkInv = grid.getStorageService().getInventory();

		long demand = getPendingDemand(item);
		long buffered = getRequestBuffered(item) + countInExtractBuffer(item);
		long stillNeeded = Math.max(0, demand - buffered);

		long toBuffer = Math.min(amount, stillNeeded);
		long excess = amount - toBuffer;

		if (action == Actionable.SIMULATE) {
			long excessAccepted = excess <= 0
					? 0
					: networkInv.insert(item, excess, Actionable.SIMULATE, actionSource);
			return toBuffer + excessAccepted;
		}

		long accepted = 0;

		if (toBuffer > 0)
			accepted += insertRequestBuffered(item, toBuffer);

		if (excess > 0)
			accepted += networkInv.insert(item, excess, Actionable.MODULATE, actionSource);

		if (accepted > 0) {
			onCraftOutputAccepted(link, item, accepted);
			touchRequest(item);
			notifyUpdate();
		}

		return accepted;
	}

	@Override
	protected void onNetworkInventoryChanged(AEKey what) {
		if (!(what instanceof AEItemKey item) || !hasPendingPackageRequest(item))
			return;

		CommittedRequest first = getFirstCommittedRequest(item);
		int orderId = first == null ? 0 : first.request().orderId();

		reconcileRequestTarget(item, orderId);
	}

	@Override
	protected void onCraftCommitmentChanged(AEKey what) {
		if (!(what instanceof AEItemKey item) || !hasPendingPackageRequest(item))
			return;
		CommittedRequest first = getFirstCommittedRequest(item);
		int orderId = first == null ? 0 : first.request().orderId();
		reconcileRequestTarget(item, orderId);
	}


	@Override
	protected void onCommittedRequestSetChanged(AEKey what) {
		if (!(what instanceof AEItemKey item))
			return;
		CommittedRequest first = getFirstCommittedRequest(item);
		int orderId = first == null ? 0 : first.request().orderId();
		reconcileRequestTarget(item, orderId);
	}

	@Override
	protected long getCommittedBufferedAmount(AEItemKey what) {
		return getRequestBuffered(what) + countInExtractBuffer(what);
	}
	private long getRequestBuffered(AEItemKey item) {
		return requestBuffer.getOrDefault(item, 0L);
	}

	private long insertRequestBuffered(AEItemKey item, long amount) {
		if (amount <= 0)
			return 0;
		requestBuffer.merge(item, amount, Long::sum);
		markPendingFulfillmentDirty(item);
		setChanged();
		return amount;
	}

	private long extractRequestBuffered(AEItemKey item, long amount) {
		if (amount <= 0)
			return 0;
		long current = requestBuffer.getOrDefault(item, 0L);
		long extracted = Math.min(current, amount);
		long left = current - extracted;
		if (left <= 0)
			requestBuffer.remove(item);
		else
			requestBuffer.put(item, left);
		if (extracted > 0)
			setChanged();
		return extracted;
	}

	private int countInExtractBuffer(AEItemKey item) {
		int total = 0;
		ItemStack template = item.toStack();

		for (int i = 0; i < inv.extractW.getSlots(); i++) {
			ItemStack stack = inv.extractW.getStackInSlot(i);
			if (!stack.isEmpty() && ItemStack.isSameItemSameComponents(stack, template))
				total += stack.getCount();
		}

		return total;
	}

	private void drainPendingPackageRequests() {
		if (pendingPackageRequests.isEmpty())
			return;

		PackagerBlockEntity packager = getPackager();
		if (packager == null)
			return;

		var keys = new ArrayList<>(pendingPackageRequests.keySet());
		for (AEKey key : keys) {
			if (!(key instanceof AEItemKey item))
				continue;

			removeCompletedPendingRequests(item);
			if (!hasPendingPackageRequest(item))
				continue;

			if (!isFulfillmentDue(item))
				continue;

			clearFulfillmentDue(item);

			Deque<CommittedRequest> requests = pendingPackageRequests.get(item);
			if (requests == null || requests.isEmpty())
				continue;

			while (!requests.isEmpty()) {
				CommittedRequest committed = requests.peekFirst();
				PackagingRequest request = committed.request();
				if (request.isEmpty()) {
					requests.removeFirst();
					onCommittedRequestSetChanged(item);
					continue;
				}

				int initialCount = committed.initialCount();
				if (request.getCount() == initialCount
						&& request.packageCounter().intValue() > 0)
					request.packageCounter().setValue(0);

				List<PackagingRequest> queued = new ArrayList<>();
				queued.add(request);

				int before = request.getCount();
				for (int i = 0; i < 100 && !queued.isEmpty(); i++)
					packager.attemptToSend(queued);

				int after = request.getCount();
				if (request.isEmpty()) {
					requests.removeFirst();
					onCommittedRequestSetChanged(item);
					continue;
				}

				if (after >= before)
					break;

				reconcileRequestTarget(item, request.orderId());
				break;
			}

			if (requests.isEmpty()) {
				pendingPackageRequests.remove(item);
				clearFulfillmentDue(item);
			} else {
				reconcileRequestTarget(item, requests.peekFirst().request().orderId());
			}

			packager.triggerStockCheck();
			packager.notifyUpdate();
		}
	}


	@Override
	public IdentifiedInventory getInvId() {
		return new IdentifiedInventory(new InventoryIdentifier.Single(worldPosition), inv);
	}

	@Override
	public void pull(PackagingRequest packagingRequest) {
		AEItemKey what = AEItemKey.of(packagingRequest.item());
		touchRequest(what);

		ItemStack remainder = ItemHandlerHelper.insertItemStacked(
				inv.extractW,
				packagingRequest.item().copyWithCount(packagingRequest.getCount()),
				true);
		int maxSpace = packagingRequest.getCount() - remainder.getCount();
		if (maxSpace <= 0)
			return;

		long reserved = extractRequestBuffered(what, maxSpace);
		if (reserved <= 0)
			return;

		ItemStack notInserted = ItemHandlerHelper.insertItemStacked(inv.extractW,packagingRequest.item().copyWithCount((int) reserved),false);
		if (!notInserted.isEmpty())
			insertRequestBuffered(what, notInserted.getCount());

		long moved = reserved - notInserted.getCount();
		if (moved > 0) {
			sendPulseNextSync();
			notifyUpdate();
		}
	}

	@Override
	protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
		super.write(tag, registries, clientPacket);
		if (!clientPacket) {
			tag.put("inv", inv.write(registries));
			ListTag list = new ListTag();
			for (var entry : requestBuffer.entrySet()) {
				CompoundTag item = entry.getKey().toTagGeneric(registries);
				item.putLong("cnt", entry.getValue());
				list.add(item);
			}
			tag.put("requestBuffer", list);
		}
	}

	@Override
	protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
		super.read(tag, registries, clientPacket);
		if (!clientPacket) {
			inv.read(tag.getCompound("inv"), registries);
			requestBuffer.clear();
			ListTag list = tag.getList("requestBuffer", Tag.TAG_COMPOUND);
			for (int i = 0; i < list.size(); i++) {
				CompoundTag item = list.getCompound(i);
				AEKey key = AEKey.fromTagGeneric(registries, item);
				if (key instanceof AEItemKey itemKey && item.getLong("cnt") > 0)
					requestBuffer.put(itemKey, item.getLong("cnt"));
			}
		}
	}

	public StockBridgeInventory getInv() {
		return inv;
	}

	@Override
	protected boolean hasInventorySpace() {
		for (int i = 0;i<inv.extractW.getSlots();i++) {
			if (inv.extractW.getStackInSlot(i).isEmpty())
				return true;
		}
		return false;
	}
}
