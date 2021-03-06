package com.rs.entity.player;
/*
 * This file is part of RuneSource.
 *
 * RuneSource is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * RuneSource is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with RuneSource.  If not, see <http://www.gnu.org/licenses/>.
 */

import com.rs.Server;
import com.rs.Settings;
import com.rs.WorldHandler;
import com.rs.entity.Position;
import com.rs.entity.npc.Npc;
import com.rs.entity.action.*;
import com.rs.io.PlayerFileHandler;
import com.rs.net.ConnectionThrottle;
import com.rs.net.HostGateway;
import com.rs.net.StreamBuffer;
import com.rs.plugin.PluginEventDispatcher;
import com.rs.util.Misc;
import com.rs.util.Tickable;

import java.nio.channels.SelectionKey;
import java.util.LinkedList;
import java.util.List;

/**
 * Represents a logged-in player.
 *
 * @author blakeman8192
 * @author Pure_
 */
public final class Player extends Client implements Tickable {

    private static final int[] SIDEBAR_INTERFACE_IDS = {
            -1, 3917, 638, 3213, 1644, 5608, 1151, -1, 5065, 5715, 2449, 4445, 147, 6299
    };
    private final List<Player> players = new LinkedList<>();
    private final List<Npc> npcs = new LinkedList<>();
    PlayerAttributes attributes = new PlayerAttributes();
    private long username;
    private int currentWeaponInterfaceId = -2;
    private int currentInterfaceId = -1;
    // Various player update data.
    private String forceChatText;
    private PublicChat publicChat;
    private Animation animation;
    private Graphics graphics;
    private Hit primaryHit;
    private Hit secondaryHit;
    private AsyncMovement asyncMovement;
    private Position facingPosition;
    private Npc interactingNpc;

    /**
     * Creates a new Player.
     *
     * @param key the SelectionKey
     */
    public Player(SelectionKey key) {
        super(key);

        // Set default attributes
        attributes.init();
    }

    /**
     * Performs processing for this player.
     */
    public void tick() throws Exception {
        // If no is received packet for more than 5 seconds, disconnect.
        if (getTimeoutStopwatch().elapsed() > 5000) {
            System.out.println(this + " timed out.");
            disconnect();
            return;
        }
        getMovementHandler().tick();
    }

    /**
     * Resets the player after updating.
     */
    public void reset() {
        super.reset();
    }

    public void login(String username, String password) throws Exception {
        Settings settings = Server.getInstance().getSettings();
        int response = Misc.LOGIN_RESPONSE_OK;

        // Updating credentials
        getAttributes().setUsername(username);
        getAttributes().setPassword(settings.isHashingPasswords() ? Misc.hashSha256(password) : password);

        // Check if the player is already logged in.
        if (WorldHandler.getInstance().isPlayerOnline(username)) {
            response = Misc.LOGIN_RESPONSE_ACCOUNT_ONLINE;
        }

        // Load the player and send the login response.
        PlayerFileHandler.LoadResponse status = Server.getInstance().getPlayerFileHandler().load(this);
        boolean validCredentials = Misc.validatePassword(password) && Misc.validateUsername(getAttributes().getUsername());

        // Invalid username/password - we skip the check if the account is found because the validation may have changed since
        if ((status != PlayerFileHandler.LoadResponse.SUCCESS && !validCredentials)
                || status == PlayerFileHandler.LoadResponse.INVALID_CREDENTIALS) {
            response = Misc.LOGIN_RESPONSE_INVALID_CREDENTIALS;
            ConnectionThrottle.enter(getHost());
        }

        // Check if connection limit is exceeded
        if (HostGateway.count(getHost()) >= settings.getMaxConsPerHost() + 1) {
            response = Misc.LOGIN_RESPONSE_LOGIN_LIMIT_EXCEEDED;
        }

        // Check if login attempts exceeded
        if (ConnectionThrottle.throttled(getHost())) {
            response = Misc.LOGIN_RESPONSE_LOGIN_ATTEMPTS_EXCEEDED;
        }

        // Sending response
        StreamBuffer.WriteBuffer resp = StreamBuffer.createWriteBuffer(3);
        resp.writeByte(response);
        resp.writeByte(getAttributes().getPrivilege().toInt());
        resp.writeByte(0);
        send(resp.getBuffer());

        if (response != Misc.LOGIN_RESPONSE_OK) {
            disconnect();
            return;
        }

        // Initialising player session
        this.username = Misc.encodeBase37(attributes.getUsername());
        WorldHandler.getInstance().register(this);
        sendMapRegion();
        sendInventory();
        sendSkills();
        sendEquipment();
        sendWeaponInterface();
        getUpdateContext().setAppearanceUpdateRequired();

        // Send sidebar interfaces
        for (int i = 1; i < SIDEBAR_INTERFACE_IDS.length; i++) {
            sendSidebarInterface(i, SIDEBAR_INTERFACE_IDS[i]);
        }
        sendRunEnergy();
        sendResetAllButtonStates();
        sendMessage("Welcome to " + settings.getServerName() + "!");
        System.out.println(this + " has logged in.");
        PluginEventDispatcher.dispatchLogin(this, status == PlayerFileHandler.LoadResponse.NOT_FOUND);
    }

    public void logout() throws Exception {
        WorldHandler.getInstance().unregister(this);
        setConnectionStage(ConnectionStage.LOGGED_OUT);
        PluginEventDispatcher.dispatchLogout(this);
        System.out.println(this + " has logged out.");

        if (getSlot() != -1) {
            Server.getInstance().getPlayerFileHandler().save(this);
        }
    }

    @Override
    public String toString() {
        return getAttributes().getUsername() == null ? "Client(" + getHost() + ")" : "Player(" + getAttributes().getUsername() + "@" + getHost() + ")";
    }

    /**
     * Teleports the player to the desired position.
     *
     * @param position the position
     */
    public void teleport(Position position) {
        getMovementHandler().reset();
        getPosition().setAs(position);
        setResetMovementQueue(true);
        setNeedsPlacement(true);
        sendMapRegion();
    }

    /**
     * Gets the player's Position.
     */
    public Position getPosition() {
        return getAttributes().getPosition();
    }

    /**
     * Sets the player's Position. <b>Please use this method with caution</b>,
     * as reference conflicts may lead this player to move when they shouldn't.
     * Consider using position.setAs(other) instead of this method if you wish
     * to set the current players <b>coordinates</b> (not actual position
     * reference) to that of another position.
     *
     * @param position the new Position
     */
    public void setPosition(Position position) {
        getAttributes().setPosition(position);
    }

    public PlayerUpdateContext getUpdateContext() {
        return (PlayerUpdateContext)super.getUpdateContext();
    }

    public List<Player> getPlayers() {
        return players;
    }

    public List<Npc> getNpcs() {
        return npcs;
    }

    public PlayerAttributes getAttributes() {
        return attributes;
    }

    public void setAttributes(PlayerAttributes attributes) {
        this.attributes = attributes;
    }

    public float getRunEnergyIncrement() {
        return 0.6f;
    }

    public float getRunEnergyDecrement() {
        return 0.6f;
    }

    public Graphics getGraphics() {
        return graphics;
    }

    public Animation getAnimation() {
        return animation;
    }

    public void setAnimation(Animation animation) {
        this.animation = animation;
    }

    public void setGraphics(Graphics graphics) {
        this.graphics = graphics;
    }

    public String getForceChatText() {
        return forceChatText;
    }

    public void setForceChatText(String forceChatText) {
        this.forceChatText = forceChatText;
    }

    public void startAnimation(Animation animation) {
        setAnimation(animation);
        getUpdateContext().setAnimationUpdateRequired();
    }

    public void startAnimation(int animationId, int delay) {
        startAnimation(new Animation(animationId, delay));
    }

    public void startAnimation(int animationId) {
        startAnimation(animationId, 0);
    }

    public void startGraphic(Graphics graphics) {
        setGraphics(graphics);
        getUpdateContext().setGraphicsUpdateRequired();
    }

    public void startGraphic(int graphicId, int delay) {
        startGraphic(new Graphics(graphicId, delay));
    }

    public void startGraphic(int graphicId) {
        startGraphic(graphicId, 0);
    }

    public void setPublicChat(PublicChat publicChat) {
        this.publicChat = publicChat;
    }

    public PublicChat getPublicChat() {
        return publicChat;
    }

    public Hit getPrimaryHit() {
        return primaryHit;
    }

    public void setPrimaryHit(Hit primaryHit) {
        this.primaryHit = primaryHit;
    }

    public Hit getSecondaryHit() {
        return secondaryHit;
    }

    public void setSecondaryHit(Hit secondaryHit) {
        this.secondaryHit = secondaryHit;
    }

    public Position getFacingPosition() {
        return facingPosition;
    }

    public void setFacingPosition(Position facingPosition) {
        this.facingPosition = facingPosition;
    }

    public Npc getInteractingNpc() {
        return interactingNpc;
    }

    public void setInteractingNpc(Npc interactingNpc) {
        this.interactingNpc = interactingNpc;
    }

    public AsyncMovement getAsyncMovement() {
        return asyncMovement;
    }

    public void setAsyncMovement(AsyncMovement asyncMovement) {
        this.asyncMovement = asyncMovement;
    }

    public int getCurrentWeaponInterfaceId() {
        return currentWeaponInterfaceId;
    }

    public void setCurrentWeaponInterfaceId(int currentWeaponInterfaceId) {
        this.currentWeaponInterfaceId = currentWeaponInterfaceId;
    }

    public int getCurrentInterfaceId() {
        return currentInterfaceId;
    }

    public void setCurrentInterfaceId(int currentInterfaceId) {
        this.currentInterfaceId = currentInterfaceId;
    }

    /**
     * @return The username of the player as a RS2 name hash.
     */
    public long getUsername() {
        return username;
    }

    /**
     * @return If the other player should be updated for this player.
     */
    boolean updatableForPlayer(Player other) {
        return other.getPosition().isViewableFrom(getPosition()) && !needsPlacement()
                && getConnectionStage() == ConnectionStage.LOGGED_IN;
    }

    public enum Privilege {
        REGULAR,
        MODERATOR,
        ADMINISTRATOR;

        public int toInt() {
            return ordinal();
        }
    }
}
