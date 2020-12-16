package net.runelite.client.plugins.RuneMiner;

import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Point;
import net.runelite.api.*;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldArea;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ConfigButtonClicked;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetInfo;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.plugins.*;
import net.runelite.client.plugins.Plugin;
import net.runelite.api.MenuEntry;
import net.runelite.client.plugins.*;
import net.runelite.client.plugins.iutils.BankUtils;
import net.runelite.client.plugins.iutils.CalculationUtils;
import net.runelite.client.plugins.iutils.InterfaceUtils;
import net.runelite.client.plugins.iutils.InventoryUtils;
import net.runelite.client.plugins.iutils.MenuUtils;
import net.runelite.client.plugins.iutils.MouseUtils;
import net.runelite.client.plugins.iutils.NPCUtils;
import net.runelite.client.plugins.iutils.ObjectUtils;
import net.runelite.client.plugins.iutils.PlayerUtils;
import net.runelite.client.plugins.iutils.WalkUtils;
import net.runelite.client.plugins.iutils.KeyboardUtils;
import net.runelite.client.plugins.iutils.iUtils;



import net.runelite.client.ui.overlay.OverlayManager;
import org.pf4j.Extension;

import javax.inject.Inject;
import javax.swing.*;
import java.awt.*;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

import static net.runelite.client.plugins.RuneMiner.RuneMinerState.*;
import static net.runelite.client.plugins.iutils.iUtils.iterating;

@Extension
@PluginDependency(iUtils.class)
@PluginDescriptor(
	name = "Tsillabak RuneMiner",
	enabledByDefault = false,
	description = "Mines rune essence",
	tags = {"rune, maker, crafting, Tsillabak"},
	type = PluginType.SKILLING
)
@Slf4j
public class RuneMinerPlugin extends Plugin {
	@Inject
	private Client client;

	@Inject
	private RuneMinerConfiguration config;

	@Inject
	private iUtils utils;

	@Inject
	private MouseUtils mouse;

	@Inject
	private PlayerUtils playerUtils;

	@Inject
	private InventoryUtils inventory;

	@Inject
	private InterfaceUtils interfaceUtils;

	@Inject
	private CalculationUtils calc;

	@Inject
	private MenuUtils menu;

	@Inject
	private ObjectUtils object;

	@Inject
	private BankUtils bank;

	@Inject
	private NPCUtils npc;

	@Inject
	private KeyboardUtils key;

	@Inject
	private WalkUtils walk;

	@Inject
	private ConfigManager configManager;

	@Inject
	PluginManager pluginManager;

	@Inject
	OverlayManager overlayManager;

	@Inject
	private RuneMinerOverlay overlay;


	RuneMinerState state;
	GameObject targetObject;
	NPC targetNPC;
	MenuEntry targetMenu;
	WorldPoint skillLocation;
	Instant botTimer;
	LocalPoint beforeLoc;
	Player player;


	WorldArea VARROCK = new WorldArea(new WorldPoint(3256, 3250, 0), new WorldPoint(3254, 3420, 0));


	int timeout = 0;
	long sleepLength;
	boolean startRuneMiner;
	private final Set<Integer> itemIds = new HashSet<>();
	private final Set<Integer> requiredIds = new HashSet<>();
	public static Set<Integer> PORTAL = Set.of(NpcID.PORTAL_3088, NpcID.PORTAL_3086);
	public static Set<Integer> OBJ = Set.of(ObjectID.RUNE_ESSENCE_34773);
	public static final int V_EAST_BANK = 12853;
	public static final int ESSENCE_MINE = 11595;
	Rectangle clickBounds;

	@Provides
	RuneMinerConfiguration provideConfig(ConfigManager configManager) {
		return configManager.getConfig(RuneMinerConfiguration.class);
	}

	private
	void resetVals() {
		overlayManager.remove(overlay);
		state = null;
		timeout = 0;
		botTimer = null;
		skillLocation = null;
		startRuneMiner = false;
		requiredIds.clear();
	}

	@Subscribe
	private
	void onConfigButtonPressed(ConfigButtonClicked configButtonClicked) {
		if (!configButtonClicked.getGroup().equalsIgnoreCase("RuneMiner")) {
			return;
		}
		log.info("button {} pressed!", configButtonClicked.getKey());
		if (configButtonClicked.getKey().equals("startButton")) {
			if (!startRuneMiner) {
				startRuneMiner = true;
				state = null;
				targetMenu = null;
				botTimer = Instant.now();
				setLocation();
				overlayManager.add(overlay);
			} else {
				resetVals();
			}
		}
	}

	@Override
	protected
	void shutDown() {
		// runs on plugin shutdown
		overlayManager.remove(overlay);
		log.info("Plugin stopped");
		startRuneMiner = false;
	}

	@Subscribe
	private
	void onConfigChanged(ConfigChanged event) {
		if (!event.getGroup().equals("plankmaker")) {
			return;
		}
		startRuneMiner = false;
	}

	public
	void setLocation() {
		if (client != null && client.getLocalPlayer() != null && client.getGameState().equals(GameState.LOGGED_IN)) {
			skillLocation = client.getLocalPlayer().getWorldLocation();
			beforeLoc = client.getLocalPlayer().getLocalLocation();
		} else {
			log.debug("Tried to start bot before being logged in");
			skillLocation = null;
			resetVals();
		}
	}

	private
	long sleepDelay() {
		sleepLength = calc.randomDelay(config.sleepWeightedDistribution(), config.sleepMin(), config.sleepMax(), config.sleepDeviation(), config.sleepTarget());
		return sleepLength;
	}

	private
	int tickDelay() {
		int tickLength = (int) calc.randomDelay(config.tickDelayWeightedDistribution(), config.tickDelayMin(), config.tickDelayMax(), config.tickDelayDeviation(), config.tickDelayTarget());
		log.debug("tick delay for {} ticks", tickLength);
		return tickLength;
	}

	private
	void openBank() {
		GameObject bankTarget = object.findNearestGameObject(10583);
		if (bankTarget != null) {
			targetMenu = new MenuEntry("", "", bankTarget.getId(),
					bank.getBankMenuOpcode(bankTarget.getId()), bankTarget.getSceneMinLocation().getX(),
					bankTarget.getSceneMinLocation().getY(), false);
			menu.setEntry(targetMenu);
			mouse.delayMouseClick(bankTarget.getConvexHull().getBounds(), sleepDelay());
			utils.sendGameMessage("bank clicked");
		}
	}

	private
	void teleportMage() {
		targetNPC = npc.findNearestNpc(2886);
		if (npc != null) {
			targetMenu = new MenuEntry("", "",
					targetNPC.getIndex(), MenuOpcode.NPC_FOURTH_OPTION.getId(), 0, 0, false);
			menu.setEntry(targetMenu);
			mouse.delayMouseClick(targetNPC.getConvexHull().getBounds(), sleepDelay());
		}
	}


	private
	void mineEssence() {
		targetObject = object.findNearestGameObject(34773);
		if (targetObject != null) {
			targetMenu = new MenuEntry("Mine", "<col=ffff>Essence", targetObject.getId(), 3, targetObject.getSceneMinLocation().getX(), targetObject.getSceneMinLocation().getY(), false);
			menu.setEntry(targetMenu);
			mouse.delayMouseClick(targetObject.getConvexHull().getBounds(), sleepDelay());
		}
	}
	private
	void clickPortal() {
		targetObject = object.findNearestGameObject(34825, 34779);
		if (targetObject != null) {
			targetMenu = new MenuEntry("Mine", "<col=ffff>Essence", targetObject.getId(), 3, targetObject.getSceneMinLocation().getX(), targetObject.getSceneMinLocation().getY(), false);
			menu.setEntry(targetMenu);
			mouse.delayMouseClick(targetObject.getConvexHull().getBounds(), sleepDelay());
		}
		else
		{
			if (targetObject != null){
				targetNPC = npc.findNearestNpc(3088,3086);
				targetMenu = new MenuEntry("", "",
						targetNPC.getIndex(), MenuOpcode.NPC_FIRST_OPTION.getId(), 0, 0, false);
				menu.setEntry(targetMenu);
				mouse.delayMouseClick(targetNPC.getConvexHull().getBounds(), sleepDelay());
			}
		}
	}
		private
		void clickNPCPortal() {
		if (targetNPC != null){
			targetNPC = npc.findNearestNpc(3088,3086);
			targetMenu = new MenuEntry("", "",
					targetNPC.getIndex(), MenuOpcode.NPC_FIRST_OPTION.getId(), 0, 0, false);
			menu.setEntry(targetMenu);
			mouse.delayMouseClick(targetNPC.getConvexHull().getBounds(), sleepDelay());

		}
		else {
			if (targetNPC == null) {
				targetObject = object.findNearestGameObject(34825, 34779);
				targetMenu = new MenuEntry("Mine", "<col=ffff>Essence", targetObject.getId(), 3, targetObject.getSceneMinLocation().getX(), targetObject.getSceneMinLocation().getY(), false);
				menu.setEntry(targetMenu);
				mouse.delayMouseClick(targetObject.getConvexHull().getBounds(), sleepDelay());

			}
		}
	}


	private
	RuneMinerState getBankState() {
		if (!bank.isOpen() && !bank.isDepositBoxOpen()) {
			return FIND_BANK;
		}
		if (!inventory.isEmpty()) {
			return DEPOSIT_ITEMS;
		}
		if (!inventory.isFull()) {
			return WALK_TO_MINE;
		}
		if (inventory.containsItem(ItemID.RUNE_ESSENCE))
		{
			return CLICKING_PORTAL;
		}
		if (inventory.isFull())
		{
			return CLICKING_PORTAL;
		}
		if (inventory.isFull() && client.getLocalPlayer().getWorldLocation().getRegionID() != V_EAST_BANK) {
			utils.sendGameMessage("should mine ess ");
			return FIND_BANK;
		}
		return IDLE;
	}


	public
	RuneMinerState getState() {

		if (timeout > 0) {
			playerUtils.handleRun(20, 30);
			return TIMEOUT;
		}
		if (iterating) {
			return ITERATING;
		}
		if (playerUtils.isMoving(beforeLoc)) {
			playerUtils.handleRun(20, 30);
			return MOVING;
		}
		if (player.getWorldArea().intersectsWith(VARROCK) && !inventory.isFull()) {

			return WALK_TO_MINE;
		}

		if (player.getWorldLocation().equals(new WorldPoint(3253, 3401, 0))) {
			return CLICK_AUBURY;
		}
		if (inventory.isEmpty() && client.getLocalPlayer().getWorldLocation().getRegionID() != V_EAST_BANK) {
			return MINE_ESSENCE;
		}

		if (inventory.isFull() && client.getLocalPlayer().getWorldLocation().getRegionID() != V_EAST_BANK) {
			utils.sendGameMessage("should portal touch ");
			return CLICKING_NPC_PORTAL;
		} else

		if (inventory.isFull() && client.getLocalPlayer().getWorldLocation().getRegionID() != V_EAST_BANK) {
			utils.sendGameMessage("should portal touch ");
			return CLICKING_PORTAL;
		}
		if (inventory.isFull())
			return getBankState();{

		}
		if (bank.isOpen()) {
			return getBankState();
		}
		if (player.getWorldArea().intersectsWith(VARROCK) && !inventory.isFull()) {
			openBank();
		}

		return IDLE;
	}

	@Subscribe
	private
	void onGameTick(GameTick tick) {
		if (!startRuneMiner) {
			return;
		}
		player = client.getLocalPlayer();
		if (client != null && player != null && skillLocation != null) {
			if (!client.isResized()) {
				utils.sendGameMessage("Client must be set to resizable");
				startRuneMiner = false;
				return;
			}
			state = getState();
			beforeLoc = player.getLocalLocation();
			switch (state) {
				case TIMEOUT:
					playerUtils.handleRun(30, 20);
					timeout--;
					break;
				case WALK_TO_MINE:
					walk.sceneWalk(new WorldPoint(3253, 3401, 0), 0, 0);
					timeout = tickDelay();
					break;
				case CLICK_AUBURY:
					teleportMage();
					timeout = tickDelay();
					break;
				case MINE_ESSENCE:
					mineEssence();
					timeout = tickDelay();
					break;
				case CLICKING_PORTAL:
				clickPortal();
					break;
				case CLICKING_NPC_PORTAL:
					clickNPCPortal();
					break;
				case ANIMATING:
					timeout = 1;
					break;
				case MOVING:
					playerUtils.handleRun(30, 20);
					timeout = tickDelay();
					break;
				case FIND_BANK:
					openBank();
					timeout = tickDelay();
					break;
				case DEPOSIT_ITEMS:
					depositItems();
					timeout = tickDelay();
					break;
				case IDLE:

					break;


			}

		}
	}

	private void depositItems() {
		if (inventory.isFull() && bank.isOpen())
			bank.depositAll();
	}
	@Subscribe
	private void onGameStateChanged (GameStateChanged event)
	{
		if (!startRuneMiner) {
			return;
		}
		if (event.getGameState() == GameState.LOGGED_IN) {
			state = IDLE;
			timeout = 2;
		}
	}
}

















