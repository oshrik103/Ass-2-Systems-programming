package bguspl.set.ex;

import bguspl.set.Env;

import java.util.concurrent.ArrayBlockingQueue;
/**
 * This class manages the players' threads and data
 *
 * @inv id >= 0
 * @inv score >= 0
 */
public class Player implements Runnable {

    /**
     * The game environment object.
     */
    private final Env env;

    /**
     * Game entities.
     */
    private final Table table;

    /**
     * The id of the player (starting from 0).
     */
    public final int id;

    /**
     * The thread representing the current player.
     */
    private Thread playerThread;

    /**
     * The thread of the AI (computer) player (an additional thread used to generate key presses).
     */
    private Thread aiThread;

    /**
     * True iff the player is human (not a computer player).
     */
    private final boolean human;

    /**
     * True iff game should be terminated.
     */
    private volatile boolean terminate;

    /**
     * The current score of the player.
     */
    private int score;
    //--------------------------------------------------------------- added by me ---------------------------------------------------------------
    /**
     * The queue maintaining the incoming key presses, size = legel set size(3).
     */
    private ArrayBlockingQueue<Integer> keyPresses;
    /**
     * The dealer object.
     */
    private final Dealer dealer;
    /**
     * The time the player has finished his turn and the dealer has been notified.
     * for cases the player not finished yet the value is -1;
     */
    private long timeOfSetComplition;
    /**
     * True iff the key presses are blocked.
     */
    private boolean isSleaping;
    /**
     * The duration of the sleep.
     */
    private long sleepDuration;
    /**
     * The class constructor.
     *
     * @param env    - the environment object.
     * @param dealer - the dealer object.
     * @param table  - the table object.
     * @param id     - the id of the player.
     * @param human  - true iff the player is a human player (i.e. input is provided manually, via the keyboard).
     */
    public Player(Env env, Dealer dealer, Table table, int id, boolean human) {
        this.env = env;
        this.table = table;
        this.id = id;
        this.human = human;
        this.dealer = dealer;
        this.score = 0;
        this.terminate = false;
        this.timeOfSetComplition = -1;
        this.isSleaping = true;
        this.sleepDuration =env.config.tableDelayMillis*env.config.tableSize;
        //using a queue to maintain the incoming key presses , implemented as a linked list
        this.keyPresses = new ArrayBlockingQueue<Integer>(env.config.featureSize);
    }

    /**
     * The main player thread of each player starts here (main loop for the player thread).
     */
    @Override
    public void run() {
        playerThread = Thread.currentThread();
        env.logger.info("thread " + Thread.currentThread().getName() + " starting.");
        if (!human) createArtificialIntelligence();
        while (!terminate) {
            if(isSleaping)
            {
                try {
                    Thread.sleep(sleepDuration);
                    isSleaping = false;
                    sleepDuration = 0;
                } catch (InterruptedException ignored) {}
            }
            if (keyPresses.size() > 0 && table.getTokensNumberByPlayer(id) < env.config.featureSize)
            {
                //it need to check if this player has a token in the slot and remove it if its has one, otherwise put a token in the slot
                int slot= keyPresses.poll();
                synchronized(table)
                {
                    if(!dealer.getTerminate() && table.slotToCard[slot]!=null && !table.removeToken(id,slot))
                        table.placeToken(id,slot);
                } 
            }
            if (table.getTokensNumberByPlayer(id) == env.config.featureSize && timeOfSetComplition == -2)
            {
                Integer slot= keyPresses.peek();
                try{
                    slot=keyPresses.take();
                }catch (InterruptedException ignored) {}
                //allows only to remove one token
                synchronized(table){
                    if(slot != null && table.slotToCard[slot]!=null &&table.removeToken(id,slot))
                        timeOfSetComplition = -1;
                }
                
            }
            if(table.getTokensNumberByPlayer(id) == env.config.featureSize && timeOfSetComplition == -1)
            {
                this.timeOfSetComplition = System.currentTimeMillis();
                isSleaping = true;
                synchronized (dealer) {
                    dealer.getThread().interrupt();
                }
                synchronized (this) { 
                    try {
                        if(timeOfSetComplition >0)
                            this.wait();
                    } catch (InterruptedException ignored) {}                }
                keyPresses.clear();
            } 
            
                
            
        }
        if (!human) try { aiThread.join(); } catch (InterruptedException ignored) {}
        env.logger.info("thread " + Thread.currentThread().getName() + " terminated.");
    }

    /**
     * Creates an additional thread for an AI (computer) player. The main loop of this thread repeatedly generates
     * key presses. If the queue of key presses is full, the thread waits until it is not full.
     */
    private void createArtificialIntelligence() {
        // note: this is a very, very smart AI (!)
        aiThread = new Thread(() -> {
            env.logger.info("thread " + Thread.currentThread().getName() + " starting.");
            while (!terminate) {
                //if the player is sleaping the computer should sleep too
                if (isSleaping){
                    try {
                        Thread.sleep(sleepDuration);
                        isSleaping = false;
                        sleepDuration = 0;
                    } catch (InterruptedException ignored) {}
                }
                if (keyPresses.size() < env.config.featureSize) {
                     //The slot number is: 𝒄𝒐𝒍𝒖𝒎𝒏 + 𝒕𝒐𝒕𝒂𝒍 𝒄𝒐𝒍𝒖𝒎𝒏𝒔 ∗ 𝒓𝒐w so generating random slot
                    int genSlot = (int) (Math.random() * env.config.columns) + (int) (Math.random() * env.config.rows) * env.config.columns;
                    keyPressed(genSlot);
                }
            }
            env.logger.info("thread " + Thread.currentThread().getName() + " terminated.");
        }, "computer-" + id);
        aiThread.start();
    }

    /**
     * Called when the game should be terminated.
     */
    public void terminate() {
        terminate = true;
        if (!human)
            try{
                aiThread.interrupt();
                aiThread.join();
            }
            catch (InterruptedException ignored) {}
    }

    /**
     * This method is called when a key is pressed.
     *
     * @param slot - the slot corresponding to the key pressed.
     */
    public void keyPressed(int slot) {
        try {
            keyPresses.put(slot);
        } catch (InterruptedException e) {}
    }

    /**
     * Award a point to a player and perform other related actions.
     *
     * @post - the player's score is increased by 1.
     * @post - the player's score is updated in the ui.
     */
    public void point() {
        int ignored = table.countCards(); // this part is just for demonstration in the unit tests
        env.ui.setScore(id, ++score);
        setSleap(env.config.pointFreezeMillis);
    }

    /**
     * Penalize a player and perform other related actions.
     */
    public void penalty() {
        // penalizing the player by making the thread sleep for a certain time
       setSleap(env.config.penaltyFreezeMillis);
        
    }

    public int score() {
        return score;
    }


//----------------------------------added by me----------------------------------
    /**
     * @return the time the player has finished his turn.
     */
    public long getTimeOfSetComplition(){
        return timeOfSetComplition;
    }
    /**
     * set the time the player has finished his turn.
     * @param time - the time the player has finished his turn.
     */
    public void setTimeOfSetComplition(long time){
        timeOfSetComplition = time;
    }
    /**
     * @return the player thread.
     */
    public Thread getPlayerThread(){
        return playerThread;
    }
    /**
     *make player sleep for a certain time.
     */
    public void setSleap(long duration){
        isSleaping = true;
        sleepDuration+= duration;
    }
}
