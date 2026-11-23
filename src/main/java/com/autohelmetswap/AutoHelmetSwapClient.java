package com.autohelmetswap;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.Predicate;

/**
 * Auto Helmet Swap (client-side only).
 *
 * Every keybind swaps the worn head item with one of two assigned items.
 * The needed item is taken into the hotbar, put on the head and the
 * displaced helmet goes back to the inventory slot the item came from.
 * The player is stopped while the swap runs.
 */
public class AutoHelmetSwapClient implements ClientModInitializer {
	public static final String MOD_ID = "auto_helmet_swap";

	public static final String SUPERMAN_NAME = "Сила Супермена";
	public static final String JACK_NAME = "Голова Джека";

	/** Menu slot id of the head armor slot in the player inventory menu. */
	private static final int HEAD_MENU_SLOT = 5;

	private record Item(String label, Predicate<ItemStack> matcher) {
	}

	private record Click(int slot, int button) {
	}

	private static final Item SUPERMAN = new Item(SUPERMAN_NAME, s -> hasName(s, SUPERMAN_NAME));
	private static final Item JACK = new Item(JACK_NAME, s -> hasName(s, JACK_NAME));
	private static final Item NETHERITE = new Item("Netherite Helmet", s -> s.is(Items.NETHERITE_HELMET));

	private KeyMapping keySupermanJack;
	private KeyMapping keyNetheriteSuperman;
	private KeyMapping keyNetheriteJack;

	private final Deque<Click> pending = new ArrayDeque<>();
	private int freezeTicks = 0;
	private boolean frozen = false;

	@Override
	public void onInitializeClient() {
		KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MOD_ID, "main"));

		keySupermanJack = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.auto_helmet_swap.superman_jack", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_J, category));
		keyNetheriteSuperman = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.auto_helmet_swap.netherite_superman", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_K, category));
		keyNetheriteJack = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.auto_helmet_swap.netherite_jack", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_L, category));

		ClientTickEvents.START_CLIENT_TICK.register(this::onTick);
	}

	private void onTick(Minecraft mc) {
		LocalPlayer player = mc.player;
		if (player == null || mc.gameMode == null) {
			pending.clear();
			frozen = false;
			freezeTicks = 0;
			// still drain key clicks so they don't pile up
			keySupermanJack.consumeClick();
			keyNetheriteSuperman.consumeClick();
			keyNetheriteJack.consumeClick();
			return;
		}

		// A swap is running: keep the player stopped and send one click per tick.
		if (frozen) {
			stopPlayer(player);
			// drain presses made during the swap
			keySupermanJack.consumeClick();
			keyNetheriteSuperman.consumeClick();
			keyNetheriteJack.consumeClick();

			if (mc.gui.screen() != null) { // something opened - abort safely
				pending.clear();
			}

			Click click = pending.poll();
			if (click != null) {
				mc.gameMode.handleContainerInput(player.inventoryMenu.containerId, click.slot(), click.button(),
						ContainerInput.SWAP, player);
			} else if (freezeTicks > 0) {
				freezeTicks--;
			} else {
				frozen = false;
				KeyMapping.setAll(); // hand movement keys back to the real keyboard state
			}
			return;
		}

		boolean a = false, b = false, c = false;
		while (keySupermanJack.consumeClick()) a = true;
		while (keyNetheriteSuperman.consumeClick()) b = true;
		while (keyNetheriteJack.consumeClick()) c = true;

		if (mc.gui.screen() != null) {
			return;
		}

		if (a) {
			startSwap(mc, player, SUPERMAN, JACK);
		} else if (b) {
			startSwap(mc, player, NETHERITE, SUPERMAN);
		} else if (c) {
			startSwap(mc, player, NETHERITE, JACK);
		}
	}

	private void startSwap(Minecraft mc, LocalPlayer player, Item first, Item second) {
		Inventory inv = player.getInventory();
		ItemStack head = player.getItemBySlot(EquipmentSlot.HEAD);

		// Decide which item we want on the head now.
		Item wanted;
		if (first.matcher().test(head)) {
			wanted = second;
		} else if (second.matcher().test(head)) {
			wanted = first;
		} else {
			wanted = null; // neither is worn: take whichever one is available
		}

		int slot = -1;
		Item found = null;
		if (wanted != null) {
			slot = findSlot(inv, wanted.matcher());
			found = wanted;
		} else {
			slot = findSlot(inv, first.matcher());
			found = first;
			if (slot < 0) {
				slot = findSlot(inv, second.matcher());
				found = second;
			}
		}

		if (slot < 0) {
			Item missing = wanted != null ? wanted : first;
			player.sendOverlayMessage(Component.translatable("auto_helmet_swap.not_found", missing.label()));
			return;
		}

		pending.clear();
		if (slot < 9) {
			// Item is already in the hotbar: swap it straight with the head slot.
			pending.add(new Click(HEAD_MENU_SLOT, slot));
		} else {
			// Item is in the main inventory: pull it into the selected hotbar slot,
			// put it on, then send the old helmet back to the original inventory slot.
			int hotbar = inv.getSelectedSlot();
			pending.add(new Click(slot, hotbar));
			pending.add(new Click(HEAD_MENU_SLOT, hotbar));
			pending.add(new Click(slot, hotbar));
		}

		frozen = true;
		freezeTicks = 1;
		stopPlayer(player);
	}

	/** Returns the player inventory index (0-35) of the first matching stack, hotbar first. */
	private static int findSlot(Inventory inv, Predicate<ItemStack> matcher) {
		for (int i = 0; i < 36; i++) {
			ItemStack s = inv.getItem(i);
			if (!s.isEmpty() && matcher.test(s)) {
				return i;
			}
		}
		return -1;
	}

	private static void stopPlayer(LocalPlayer player) {
		KeyMapping.releaseAll();
		player.setSprinting(false);
		Vec3 v = player.getDeltaMovement();
		player.setDeltaMovement(0.0, v.y, 0.0);
	}

	private static boolean hasName(ItemStack stack, String name) {
		if (stack.isEmpty()) {
			return false;
		}
		Component custom = stack.get(DataComponents.CUSTOM_NAME);
		if (custom != null && name.equals(custom.getString().trim())) {
			return true;
		}
		Component item = stack.get(DataComponents.ITEM_NAME);
		return item != null && name.equals(item.getString().trim());
	}
}
