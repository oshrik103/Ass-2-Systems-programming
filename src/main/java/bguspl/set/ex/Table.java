package bguspl.set.ex;

import bguspl.set.Env;

import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * This class contains the data that is visible to the player.
 *
 * @inv slotToCard[x] == y iff cardToSlot[y] == x
 */
public class Table {

    /**
     * The game environment object.
     */
    private final Env env;

    /**
     * Mapping between a slot and the card placed in it (null if none).
     */
    protected final Integer[] slotToCard; // card per slot (if any)

    /**
     * Mapping between a card and the slot it is in (null if none).
     */
    protected final Integer[] cardToSlot; // slot per card (if any)
    /**
     * The ids of the players that put tokens on each slot.
     */
    private LinkedList<Integer> [] tokensPerSlot;
    /**
     * The slots of the tokens that each player has on the table.
     */
    private LinkedList<Integer> [] tokensPerPlayer;

    /**
     * Constructor for testing.
     *
     * @param env        - the game environment objects.
     * @param slotToCard - mapping between a slot and the card placed in it (null if none).
     * @param cardToSlot - mapping between a card and the slot it is in (null if none).
     */
    public Table(Env env, Integer[] slotToCard, Integer[] cardToSlot) {
        this.env = env;
        this.slotToCard = slotToCard;
        this.cardToSlot = cardToSlot;
        //initialized the size to table size
        this.tokensPerSlot = new LinkedList[env.config.tableSize];
        for (int i = 0; i < env.config.tableSize; i++)
            tokensPerSlot[i] = new LinkedList<Integer>();
        this.tokensPerPlayer = new LinkedList[env.config.players];
        
        for (int i = 0; i < env.config.players; i++) {
            tokensPerPlayer[i] = new LinkedList<Integer>();
        }
    }
    

    /**
     * Constructor for actual usage.
     *
     * @param env - the game environment objects.
     */
    public Table(Env env) {

        this(env, new Integer[env.config.tableSize], new Integer[env.config.deckSize]);
    }

    /**
     * This method prints all possible legal sets of cards that are currently on the table.
     */
    public void hints() {
        List<Integer> deck = Arrays.stream(slotToCard).filter(Objects::nonNull).collect(Collectors.toList());
        env.util.findSets(deck, Integer.MAX_VALUE).forEach(set -> {
            StringBuilder sb = new StringBuilder().append("Hint: Set found: ");
            List<Integer> slots = Arrays.stream(set).mapToObj(card -> cardToSlot[card]).sorted().collect(Collectors.toList());
            int[][] features = env.util.cardsToFeatures(set);
            System.out.println(sb.append("slots: ").append(slots).append(" features: ").append(Arrays.deepToString(features)));
        });
    }

    /**
     * Count the number of cards currently on the table.
     *
     * @return - the number of cards on the table.
     */
    public int countCards() {
        int cards = 0;
        for (Integer card : slotToCard)
            if (card != null)
                ++cards;
        return cards;
    }

    /**
     * Places a card on the table in a grid slot.
     * @param card - the card id to place in the slot.
     * @param slot - the slot in which the card should be placed.
     *
     * @post - the card placed is on the table, in the assigned slot.
     */
    public synchronized void placeCard(int card, int slot) {
        try {
            Thread.sleep(env.config.tableDelayMillis);
        } catch (InterruptedException ignored) {}

        cardToSlot[card] = slot;
        slotToCard[slot] = card;

        // TODO implement
        env.ui.placeCard(card, slot);
    }

    /**
     * Removes a card from a grid slot on the table.
     * @param slot - the slot from which to remove the card.
     */
    public synchronized void removeCard(int slot) {
        try {
            Thread.sleep(env.config.tableDelayMillis);
        } catch (InterruptedException ignored) {}
        //removing all the tokens used by the players on this slot
        tokensPerSlot[slot].clear();
        for (int id=0;id<env.config.players;id++) {
            if(tokensPerPlayer[id].remove((Integer)slot));
                env.ui.removeToken(id, slot);
        }
        cardToSlot[slotToCard[slot]] = null;
        slotToCard[slot] = null;
        
        env.ui.removeCard(slot);
    }

    /**
     * Places a player token on a grid slot.
     * @param player - the player the token belongs to.
     * @param slot   - the slot on which to place the token.
     */
    public synchronized void placeToken(int player, int slot) {
        tokensPerSlot[slot].add(player);
        tokensPerPlayer[player].add(slot);
        env.ui.placeToken(player, slot);
    }

    /**
     * Removes a token of a player from a grid slot.
     * @param player - the player the token belongs to.
     * @param slot   - the slot from which to remove the token.
     * @return       - true iff a token was successfully removed.
     */
    public synchronized boolean removeToken(int player, int slot) {   
        if( tokensPerSlot[slot].remove((Integer)player))
        {
            tokensPerPlayer[player].remove((Integer)slot);
            env.ui.removeToken(player, slot);
            return true;
        }
        
        return false;
    }
//-------------------------------------------------------------------- added by me ------------------------------------------------------------
    /**
     * Returns the slots list of tokens for the specific player.
     * @param id - the  id of the player.
     * @return - the list of tokens used by this player.
     */
    public synchronized LinkedList<Integer> getTokensByPlayer(int id){
        return tokensPerPlayer[id];
    }
    /**
     * Returns the number of tokens used by the specific player.
     * @param id - the  id of the player.
     * @return - the number of tokens used by this player.
     */
    public synchronized int getTokensNumberByPlayer(int id){
        return tokensPerPlayer[id].size();
    }
    /**
     * remove all tokens of a specific player from the table.
     * @param id
     */
    public synchronized void removeAllTokensByPlayer(int id){
        for (int i = 0; i < tokensPerPlayer[id].size(); i++) {
            removeToken(id, tokensPerPlayer[id].get(i));
        }
    }
    /*
     * Returns the list of players used tokens on the specific slot.
     */
    public synchronized LinkedList<Integer> getTokensPerSlot(int slot) {
        return tokensPerSlot[slot];
    }
}
