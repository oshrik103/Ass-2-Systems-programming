package bguspl.set.ex;

import bguspl.set.Config;
import bguspl.set.Env;

import java.util.Arrays;
import java.util.Calendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

// added by Oshri
import java.util.Iterator;

/**
 * This class manages the dealer's threads and data
 */
public class Dealer implements Runnable {

    /**
     * The game environment object.
     */
    private final Env env;

    /**
     * Game entities.
     */
    private final Table table;
    private final Player[] players;

    /**
     * The list of card ids that are left in the dealer's deck.
     */
    private final List<Integer> deck;

    /**
     * True iff game should be terminated.
     */
    private volatile boolean terminate;

    /**
     * The time when the dealer needs to reshuffle the deck due to turn timeout.
     */
    
     private long reshuffleTime = Long.MAX_VALUE; // max value to avoid reshuffling the deck before the game starts

    // ------------------------------------------ added by Oshri and Ofir ------------------------------------------
    /**
     * The time to sleep when the function sleepUntilWokenOrTimeout() is called and the last sleep was not interrupted
     */
    
     private final int fullSleepTime = 1000;


    /**
     * The time that has left to sleep when the function sleepUntilWokenOrTimeout() is called and the last sleep was interrupted
     */
    
     private long partialSleepTime = fullSleepTime;
    
    /**
     * a hash map that contains the time when the player should be unfrozen, the key is the player's id
     */
      
    private Map<Integer, Long> playerUnfreezeTimeMap; 
    
    /*
    * the time to sleep when the timer is red
    */
    private long redTimerSleepTime = 100;

    /*
     * the dealer's thread
     */
    private Thread dealerThread;
    /*
     * keep the last time the second was updated
     */
    private long lastSecondUpdate=-1;
    /**
     * Array of player threads
     */
    private final Thread[] playerThreads;

    // -----------------------------------------------------------------------------------------------------

    public Dealer(Env env, Table table, Player[] players) {
        this.terminate = false;
        this.env = env;
        this.table = table;
        this.players = players;
        this.playerUnfreezeTimeMap = new HashMap<>();
        this.playerThreads = new Thread[env.config.players]; 
        this.deck = IntStream.range(0, env.config.deckSize).boxed().collect(Collectors.toList());
    }

    /**
     * The dealer thread starts here (main loop for the dealer thread).
     */
    @Override
    public void run() {
        env.logger.info("thread " + Thread.currentThread().getName() + " starting.");
        // start the players threads
        dealerThread=Thread.currentThread();
        for (Player player : players) 
        {
            Thread playerThread = new Thread(player);
            playerThreads[player.id] = playerThread;
            playerThread.start();
        }
        
        while (!shouldFinish()) {
            placeCardsOnTable();
            if(reshuffleTime == Long.MAX_VALUE)
            {
                reshuffleTime = System.currentTimeMillis() + env.config.turnTimeoutMillis;
                updateTimerDisplay(true);
            }
            timerLoop();
            removeAllCardsFromTable();
        }
        announceWinners();
        terminate(); 
        env.logger.info("thread " + Thread.currentThread().getName() + " terminated.");
    }

    /**
     * The inner loop of the dealer thread that runs as long as the countdown did not time out.
     */
    private void timerLoop() {
        while (!terminate && System.currentTimeMillis() < reshuffleTime) {
            sleepUntilWokenOrTimeout();
            removeCardsFromTable();
            if(!terminate)
                placeCardsOnTable();
        }
    }

    /**
     * Called when the game should be terminated.
     */

     public void terminate() {
        terminate = true;
        for (int i = env.config.players - 1; i >= 0; i--) {
            Player player = players[i];
            player.terminate();
            try {
                playerThreads[i].interrupt();
                playerThreads[i].join();

            } catch (InterruptedException ignored) {}

        }
        
    }

    /**
     * Check if the game should be terminated or the game end conditions are met.
     *
     * @return true iff the game should be finished.
     */
    private boolean shouldFinish() {
        return terminate || env.util.findSets(deck, 1).size() == 0;
    }

    /**
     * Checks if cards should be removed from the table and removes them.
     */
    private void removeCardsFromTable() 
    {
        // check if there is a player that pressed three tokens         
        Integer playerToCheckSet = findFirstPlayerToCheckSet();
        if (playerToCheckSet != null)
        {
            int[] cardsToCheck = tokensToCards(playerToCheckSet);
            //holds the players that should be updated after the cards are removed from the table
            Set<Integer> playerToUpdate=new HashSet<Integer>();
            // true if the cards that returned are not null and the set is legal
            if (cardsToCheck != null && env.util.testSet(cardsToCheck)) 
            {
                // deleteing the cards from the table
                for (int i = 0; i < cardsToCheck.length; i++) 
                {
                    Integer slotToDelete = table.cardToSlot[cardsToCheck[i]];
                    if(slotToDelete != null)
                    {
                        LinkedList<Integer> playersToReset;
                        synchronized (table) 
                        {
                            playersToReset = table.getTokensPerSlot(slotToDelete);
                            for (int player: playersToReset) 
                            {
                                if(player != playerToCheckSet)
                                    playerToUpdate.add(player);    
                            }
                            table.removeCard(slotToDelete);
                        }
                        
                    }
                }
                //all players that have tokens on the slots that were removed should be updated
                for (int player: playerToUpdate) 
                {
                    players[player].setTimeOfSetComplition(-1); //intrupt with the other players tokens
                    synchronized (players[player]) 
                    {
                        players[player].notify();
                    }
                }
                placeCardsOnTable(); // placing new cards instead of the removed cards
                updateTimerDisplay(true); // the timer should be reset after a legal set was found
                point(playerToCheckSet); // add a point to the player's score, and freeze him for a certain amount of time
            }
            // false if the set is illegal or does not exist because there is not card in this slot (thus table.slotToCard[slot] = null)
            else 
            {
                penalty(playerToCheckSet); // freeze the player for a bigger amount of time (penalty time)
            }
            
        }
    }
    /**
     * Check if any cards can be removed from the deck and placed on the table.
     */
    private void placeCardsOnTable() 
    {
        // TODO implement
        boolean tableChanged = false;
         // need to check if there are any cards left in the deck and if there are any empty slots on the table
        for (int i = 0; deck.size() > 0 && i < table.slotToCard.length; i++) 
        {
            if (table.slotToCard[i] == null) 
            {
                tableChanged = true;
                // drawing the card from the deck is done randomly, simulating shuffling the deck
                int card = deck.remove(randomCardIndex());
                synchronized (table) 
                {
                    table.placeCard(card, i);
                }
            }
        }
        // converts the cards that are on the table to a list after removing the nulls
        List<Integer> cardsOnTable = Arrays.stream(table.slotToCard).filter(Objects::nonNull).collect(Collectors.toList()); 
        // find all the sets on the table, 220 = 12 choose 3, the maximum number of possible sets on the table
        List<int[]> sets = env.util.findSets(cardsOnTable, 220); 
        
        if (tableChanged)
        {
            reshuffleTime = System.currentTimeMillis() + env.config.turnTimeoutMillis;
        }

        // if there is not a single set on the table and there are still cards on the deck, remove all the cards from the table and place new cards
        if (sets.size() == 0 && deck.size() > 0)
        {
            removeAllCardsFromTable();
            placeCardsOnTable();
        }
        else if (tableChanged && sets.size() != 0 && env.config.hints ) // if the table was changed and hints is true - print them
        {
            table.hints();
        }
        if (sets.size() ==0 && deck.size() == 0)
        {
                terminate();
        }
    }

    /**
     * Sleep for a fixed amount of time or until the thread is awakened for some purpose.
     */
    private void sleepUntilWokenOrTimeout() 
    {
        long preSleepTime = System.currentTimeMillis(); // current time before the sleep
        long timeLeft = reshuffleTime - System.currentTimeMillis(); // the time left until the next reshuffle
        try 
        {
            // the dealer sleeps for the amount of time that left if it was interrupted last time, otherwise he sleeps for a fullSleepTime
            Thread.sleep(partialSleepTime);
            partialSleepTime = fullSleepTime;
            // if we got to the next lines, the dealer sleep was not interrupted
            // if the timer is red, the dealer sleep cycle shortens to redTimerSleepTime  
            if (env.config.turnTimeoutWarningMillis >= timeLeft)
            { 
                partialSleepTime = redTimerSleepTime;
            }
            // if the timer is not red, and the dealer sleep was not interrupted, in the next cycle the dealer will sleep for a fullSleepTime
            else
            {
                partialSleepTime = fullSleepTime;
            }

        } 
        catch (InterruptedException ignored) 
        {
            long acutalSleepTime = System.currentTimeMillis() - preSleepTime; // the actual time the dealer slept for
            // if the timer is red, next cycle will be according to the redTimerSleepTime
            if (env.config.turnTimeoutWarningMillis >= timeLeft)
            {
                partialSleepTime = redTimerSleepTime - acutalSleepTime;
            }
            // if the timer is not red, and the dealer sleep was interrupted, it will complete the cycle next time
            else
            {
                partialSleepTime = fullSleepTime - acutalSleepTime;
            }
            // if partialSleepTime is negative, the dealer overslept, so the next cycle will be a 0 sleep cycle 
            if (partialSleepTime < 0)
            {
                partialSleepTime = 0;
            }
        }
        
        boolean timeToReshuffle = System.currentTimeMillis() >= reshuffleTime;
        updateTimerDisplay(timeToReshuffle); // the time has changed because the dealer slept for a short period
        updateFreezeTimeDisplay();
        /* 
         *  if true it's time to reshuffle the deck and restart the countdown, we need to check if there are any players
            who asked to check their set before we remove all cards from the table
         */
        if (timeToReshuffle) 
        {
            removeCardsFromTable();
            removeAllCardsFromTable();
            if (env.util.findSets(deck, 1).size() == 0) // if there are no sets in the deck
            {
                terminate(); // so when we go back to timerLoop, terminate=true and we will go back to the run method and announce the winners
            }
            else
            {
                placeCardsOnTable();
            }
  
        }
    }


    /**
     * Reset and/or update the countdown and the countdown display.
     */

     private void updateTimerDisplay(boolean reset) 
     {
        if (reset) 
        {
            reshuffleTime = System.currentTimeMillis() + env.config.turnTimeoutMillis;
            env.ui.setCountdown(env.config.turnTimeoutMillis, env.config.turnTimeoutMillis <= env.config.turnTimeoutWarningMillis);
        }
        else if(reshuffleTime-System.currentTimeMillis() > env.config.turnTimeoutWarningMillis)
        { 
           // rounding the time left to the nearest second
            long countdown = (int)Math.round((reshuffleTime - System.currentTimeMillis())/1000.0)*1000; // the time left until the deck should be reshuffled
            if(countdown!=lastSecondUpdate)
               env.ui.setCountdown(countdown, false); // if the time left is less the warning time, the timer will be painted in red
            lastSecondUpdate=countdown;  
        }
        //  if the time left is less the warning time, the timer will be painted in red and no need to round the seconds
        else{
           long countdown = reshuffleTime - System.currentTimeMillis(); // the time left until the deck should be reshuffled
           env.ui.setCountdown(countdown, true); // if the time left is less the warning time, the timer will be painted in red  
        }
     }

    /**
     * Returns all the cards from the table to the deck.
     */
    private void removeAllCardsFromTable() 
    {
        for (int i = 0; i < table.slotToCard.length; i++) 
        {
            if (table.slotToCard[i] != null) // a slot could be empty if the deck is empty
            {
              synchronized (table) 
              {
                  deck.add(table.slotToCard[i]);
                  table.removeCard(i);
              }
            }
        }
        for (Player player : players) 
        {
            synchronized (player) 
            {
                player.notify();
                player.setTimeOfSetComplition(-1); //intrupt with the other players tokens
                table.removeAllTokensByPlayer(player.id);
            }
        }
    }

    /**
     * Check who is/are the winner/s and displays them.
     */
    private void announceWinners() 
    {
        int maxScore = findMaxScore(); 
        List<Integer> winners = new LinkedList<>();
        // adding the winners to the list
        for (int i = 0; i < players.length; i++) 
        {
            Player player = players[i];
            if (player.score() == maxScore) 
            {
                winners.add(i);
            }
        }
        int[] winnersArray = winners.stream().mapToInt(i -> i).toArray(); // convert the list of integers to an array of ints
        env.ui.announceWinner(winnersArray);
    }


    // ---------------------------------------------- added by Oshri ----------------------------------------------
    /*
     * updates set complition time of the player, freezes the him for a certain amount of time, then adds a point to his score 
     */

    private void point(int playerId)
    {
        players[playerId].setTimeOfSetComplition(-1); // signals that there is no set in the player's tokens
        players[playerId].point();
        synchronized (players[playerId]) 
        {
            players[playerId].notify();
        }
        playerUnfreezeTimeMap.put( playerId , System.currentTimeMillis() + env.config.pointFreezeMillis); // the time when the player should be unfrozen
        env.ui.setFreeze(playerId, env.config.pointFreezeMillis); // freeze the player for a certain amount of time
    }

    /*
     * updates set complition time of the player, freezes the him for a certain amount of time (penalty time)
     */
    private void penalty(int playerId)
    {
        
        players[playerId].setTimeOfSetComplition(-2); // signals that the set was already checked, no need to check it again
        players[playerId].penalty();
        synchronized (players[playerId]) 
        {
            players[playerId].notify();
        }
        playerUnfreezeTimeMap.put( playerId , System.currentTimeMillis() + env.config.penaltyFreezeMillis); // the time when the player should be unfrozen
        env.ui.setFreeze(playerId, env.config.penaltyFreezeMillis);
    }

    /*
     * convert the tokens of the player to an array of cards
     */

    private int[] tokensToCards(Integer playerToCheckSet)
    {
        List<Integer> sets;
        synchronized (table) 
        {
            sets = table.getTokensByPlayer(playerToCheckSet);
        }
        int[] cardsToCheck = new int[3];
        for (int i = 0; i < cardsToCheck.length; i++) 
        {
            // if one of the slot to check is null, the set is not legal and the player should be penalized
            if (table.slotToCard[sets.get(i)] == null)
            {
                return null;
            }
            cardsToCheck[i] = table.slotToCard[sets.get(i)];
            
            
        }
        return cardsToCheck;
    }


    /*
     * find the first player who asked to check his set, and return his index. if no player asked to check his set, return null
     */
    private Integer findFirstPlayerToCheckSet()
    {
        Integer playerToCheckSet = null;
        long minTime = Long.MAX_VALUE;
        for (int i = 0; i < players.length; i++) 
        {
            long timeSent = players[i].getTimeOfSetComplition();
            if (timeSent >= 0 && timeSent < minTime) 
            {
                minTime = timeSent;
                playerToCheckSet = i;
            }
        }
        return playerToCheckSet; 
    }
    
    
    /**
     * Returns a random card index from the deck.
     *
     * @return - a random card index.
     */
    private int randomCardIndex()
    {
        return (int) (Math.random() * deck.size());
    }

    /**
     * Update the freeze time display for all players and remove players that are not frozen anymore.
     */
    private void updateFreezeTimeDisplay() {
        Iterator<Integer> iterator = playerUnfreezeTimeMap.keySet().iterator();
        while (iterator.hasNext()) {
            Integer playerId = iterator.next();
            long timeLeft =(int)Math.ceil((playerUnfreezeTimeMap.get(playerId) - System.currentTimeMillis())/1000.0)*1000;
            if (timeLeft > 0) { // if the player is still frozen
                env.ui.setFreeze(playerId, timeLeft); // update the freeze time display
            } 
            // if the player is not frozen anymore, remove him from the hash map and update the freeze time display to 0
            else {
                env.ui.setFreeze(playerId, 0);
                iterator.remove();
            }
        }
    }

    /*
     * finds the highest score of all the players
     */
    private int findMaxScore()
    {
        int maxScore = 0;
        for (int i = 0; i < players.length; i++) 
        {
            if (players[i].score() > maxScore) 
            {
                maxScore = players[i].score();
            }
        }
        return maxScore;
    }
    /*
     * returns the dealer's thread
     */
    public Thread getThread()
    {
        return dealerThread;
    }
    /*
     * returns the terminate variable
     */
    public boolean getTerminate()
    {
        return terminate;
    } 
}
