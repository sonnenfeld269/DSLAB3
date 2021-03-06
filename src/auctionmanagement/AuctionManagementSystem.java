package auctionmanagement;

/**
 *
 * @author sanker
 */
import Event.*;
import MyLogger.Log;
import RMI.AnalyticsServerInterface;
import RMI.BillingServerInterface;
import RMI.BillingServerSecure;
import RMI.RMIRegistry;
import RMI.RMIRegistryException;
import communication.Client;
import communication.ClientException;
import java.rmi.RemoteException;
import java.util.Date;
import java.util.Iterator;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.locks.ReentrantLock;

public class AuctionManagementSystem implements Runnable {

    private LinkedBlockingQueue<CommandTask> incomingrequest = null;
    private ConcurrentHashMap<String, Account> account_map = null;
    private ConcurrentHashMap<Long, Auction> auction_map = null;
    private Timer timer = null;
    private LinkedBlockingQueue<Answer> outgoingmessagechannel = null;
    private AuctionTCPMessageServer outgoingMessageServer = null;
    private AuctionUDPMessageServer NotificationServer = null;
    private LinkedBlockingQueue<Notification> notificationchannel = null;
    private final ExecutorService pool;
    private TentativeBids tentativeBids = null;
    private ReentrantLock lockForOnlineUser = null;
    private ReentrantLock lockForBlockedUser = null;
    private int onlineUser = 0;
    private int blockedUser = 0;
    private Log logger = null;
    /**
     * ********* RMI ******************************
     */
    private String propertyFile = "./src/registry.properties";
    private RMIRegistry registry = null;
    private AnalyticsServerInterface analytic = null;
    private BillingServerInterface billing = null;
    private BillingServerSecure bss = null;
    boolean rmiAvailable = false;
    boolean rmiAnalyticAvailable = false;
    boolean rmiBillingAvailable = false;

    /**
     * ********* RMI ******************************
     */
    private CommandTask readRequest() {
        CommandTask r = null;
        try {
            r = incomingrequest.take();
        } catch (InterruptedException ex) {
        }
        return r;
    }

    //public ----------------------------------------- 
    public AuctionManagementSystem(String analyticBinding, String billingBinding, LinkedBlockingQueue<CommandTask> incomingrequest, ExecutorService pool, Log output) {
        this.pool = pool;
        this.logger = output;
        this.incomingrequest = incomingrequest;
        this.account_map = new ConcurrentHashMap<String, Account>();
        this.auction_map = new ConcurrentHashMap<Long, Auction>();
        this.timer = new Timer(true);
        this.notificationchannel = new LinkedBlockingQueue<Notification>();
        //this.NotificationServer = new AuctionUDPMessageServer(notificationchannel,this.logger);
        this.outgoingmessagechannel = new LinkedBlockingQueue<Answer>();
        this.outgoingMessageServer = new AuctionTCPMessageServer(outgoingmessagechannel, this.logger);
        lockForOnlineUser = new ReentrantLock();
        lockForBlockedUser = new ReentrantLock();
        tentativeBids = new TentativeBids();
        //this.pool.execute(NotificationServer);
        this.pool.execute(outgoingMessageServer);
        this.logger.output("AuctionManagementSystem created...", 2);
        /*
         * ***************RMI**********************
         */
        try {

            registry = new RMIRegistry(propertyFile);
            if (registry.getRegistry() != null) {
                rmiAvailable = true;
                try {
                    analytic = registry.getAnalyticsInterface(analyticBinding);
                    rmiAnalyticAvailable = true;
                } catch (RMIRegistryException ex) {
                    this.logger.output("AuctionManagementSystem:RMIRegistryException:" + ex.getMessage(), 2);
                    analytic = null;
                    rmiAnalyticAvailable = false;
                }

                try {

                    billing = registry.getBillingInterface(billingBinding);
                    bss = registry.getBillingInterface(billingBinding).login("auctionServer", "44");
                    if (bss != null) {
                        logger.output("AuctionManagementSystem:RMI: bss aviable", 2);

                    } else {
                        logger.output("AuctionManagementSystem:RMI: No BillingInterface aviable");
                        throw new RMIRegistryException("BSS not aviable!");

                    }

                    rmiBillingAvailable = true;
                } catch (RMIRegistryException ex) {
                    this.logger.output("AuctionManagementSystem:RMIRegistryException:" + ex.getMessage(), 2);
                    billing = null;
                    rmiBillingAvailable = false;
                }

            }



        } catch (RemoteException ex) {
            this.logger.output("AuctionManagementSystem:RemoteException:" + ex.getMessage(), 2);
        }
        /*
         * ***************RMI**********************
         */

    }
    //returns true if the queue had enough space 

    @Override
    public void run() {
        this.logger.output("AuctionManagementSystem started...", 2);
        CommandTask newRequestTask = null;
        AMS_Handler amshandler = null;

        while (!Thread.currentThread().isInterrupted()) {
            try {

                newRequestTask = incomingrequest.take();
                amshandler = new AMS_Handler(newRequestTask);
                pool.execute(amshandler);


            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }

        }
        this.logger.output("AuctionManagementSystem end...", 2);

    }

    public void close() {
        this.account_map.clear();
        this.auction_map.clear();
        timer.cancel();
        this.logger.output("AuctionManagementSystem closed...", 2);
    }

    protected void addOnlineUser() {
        this.lockForOnlineUser.lock();
        this.onlineUser++;
        this.lockForOnlineUser.unlock();
    }

    protected void deleteOnlineUser() {
        this.lockForOnlineUser.lock();
        if (onlineUser > 0) {
            this.onlineUser--;
        }
        this.lockForOnlineUser.unlock();
    }

    protected int getNumberofOnlineUser() {
        this.lockForOnlineUser.lock();
        int a = this.onlineUser;
        this.lockForOnlineUser.unlock();
        return a;
    }

    protected void addBlockedUser() {
        this.lockForBlockedUser.lock();
        this.blockedUser++;
        this.lockForBlockedUser.unlock();
    }

    protected void deleteBlockedUser() {
        this.lockForBlockedUser.lock();
        if (blockedUser > 0) {
            this.blockedUser--;
        }
        this.lockForBlockedUser.unlock();
    }

    protected int getNumberofBlockedUser() {
        this.lockForBlockedUser.lock();
        int a = this.blockedUser;
        this.lockForBlockedUser.unlock();
        return a;
    }

    public class Task extends TimerTask {

        private Long auction_id;
        private Auction auc = null;
        private Account owner = null;
        private Account bidder = null;
        private Log logger = null;

        public Task(long auction_id, Log logger) {
            this.auction_id = new Long(auction_id);
            this.logger = logger;
            // account_map

        }

        public void run() {
            this.logger.output("TimerTaskHandleThread started a schedule...", 2);
            this.auc = auction_map.remove(this.auction_id);
            this.logger.output("Close auction:" + "id:" + auc.getID() + ",desc:" + auc.getDescription(), 3);
            this.logger.output("Owner:" + auc.getOwner() + ",Hbidder:" + auc.getHighestBidder(), 3);
            owner = account_map.get(this.auc.getOwner());
            if (auc.getHighestBidder().contains("none")) {
                bidder = null;
            } else {
                bidder = account_map.get(this.auc.getHighestBidder());
            }
            Answer answer=null;
            if(tentativeBids.isalreadyinsertedAuctionID(auction_id))
            {
                //if a auction ends , all blocked User must be rejected
                TentativeBidEntry tbe=tentativeBids.remove(auction_id);
                String BlockedUser=tbe.getFirstConfirmUser();
                if(BlockedUser!=null)
                {
                   Account acc=account_map.get(new String(BlockedUser));
                   if(acc.isOnline())
                   {
                       answer=new Answer("!reject Auction has expanded.",acc.getClient());
                       outgoingmessagechannel.offer(answer);
                   }
                   
                }
            }

            /**
             * ********* RMI ******************************
             */
            try {
                if (rmiAvailable) {
                    if (rmiAnalyticAvailable) {
                        //BID_WON
                        if (bidder != null) {
                            analytic.processEvents(
                                    new BidEvent(bidder.getName(),
                                    auc.getID(),
                                    auc.getHighestBid(),
                                    BidEvent.BidEventType.BID_WON));
                            logger.output("TimerTask:RMI"
                                    + ":processEvent:Invoke::"
                                    + "AuctionID:" + auc.getID()
                                    + "\nUser:" + auc.getHighestBidder()
                                    + "\nPrice:" + auc.getHighestBid()
                                    + "\nType:BID_WON", 3);
                        }
                        //AUCTION_ENDED                        
                        AuctionEvent auctionevent = new AuctionEvent(
                                AuctionEvent.AuctionType.AUCTION_ENDED,
                                auc.getID());
                        auctionevent.setTime(auc.getPeriodofTime() * 1000);
                        analytic.processEvents(auctionevent);
                        logger.output("TimerTask:RMI"
                                + ":processEvent:Invoke::"
                                + "AuctionID:" + auc.getID() + "\n"
                                + "Type:AUCTION_ENDED", 3);

                        //rmiAnalyticsAvaible
                    }

                    if (rmiBillingAvailable) {
                        /*BillingServer RMI Method Invocation*/
                        logger.output("rmiBilling Aviable, billAuction to happen soon.", 2);

                        if (bss != null) {
                            bss.billAuction(auc.getOwner(), auc.getID(), auc.getHighestBid());

                        } else {
                            logger.output("AuctionManagementSystem: No bss aviable", 2);
                        }
                        /*BillingServer RMI Method Invocation*/
                    }//rmiBillingAvaible
                }//rmiAvaible
            } catch (RemoteException ex) {
                logger.output("TimerTaskHandle:RMI:RemoteException:" + ex.getMessage(), 2);
            } catch (NullPointerException e) {
                logger.output("TimerTaskHandle:RMI:NullPointerException:" + e.getMessage());

            }

            this.logger.output("TimerTaskHandleThread finished a schedule...", 2);
            /**
             * ********* RMI ******************************
             */
            //Notification notificationOwner=null;
            //Notification notificationBidder=null;
            /*       
             String messageforBidderOwner = new String("!auction-ended"+" "
             +auc.getHighestBidder()
             +" "
             +Double.toString(auc.getHighestBid())
             +" "
             +auc.getDescription());
           
             try
             {
             if(owner.isOnline())
             notificationOwner=new Notification(messageforBidderOwner,
             owner.getClientUDP());
             if(bidder!=null)
             {
             if(bidder.isOnline())
             notificationBidder=new Notification(messageforBidderOwner,
             bidder.getClientUDP());
             }
             
             if(notificationOwner!=null)
             notificationchannel.offer(notificationOwner);
             else
             owner.addNotification(messageforBidderOwner);
             if(notificationBidder!=null)
             notificationchannel.offer(notificationBidder);
             else{
             if(bidder!=null)
             bidder.addNotification(messageforBidderOwner);
             }
             
             
             }catch(ClientUDPException e)
             {
             this.logger.output("TimerTaskThread:ClientUDPException:"+e.getMessage());
             }*/
        }//run()
    }
    
    public class TentativeClose extends TimerTask {

        

        public TentativeClose() {
            

        }

        public void run() {
            
            Answer a =null;
            lockForBlockedUser.lock();
            while(blockedUser>0)
            {
                TentativeBidEntry tbe= tentativeBids.getnextEntrywithBlockedUser();
                Account acc=account_map.get(new String(tbe.FirstConfirmUser));
                if(acc.isOnline())
                {
                    a=new Answer("!rejected Deadlock state reached.",acc.getClient());
                    outgoingmessagechannel.offer(a);
                    tentativeBids.resetFirstConfirmUser(tbe);
                    blockedUser--;
                }
            }
            lockForBlockedUser.unlock();
            
        }
    }
          
    
    

    public class AMS_Handler implements Runnable {

        private CommandTask commandtask = null;
        /*
         this.pool=pool;
         this.logger=output;
       
         this.account_map=new ConcurrentHashMap<String,Account>();
         this.auction_map=new ConcurrentHashMap<Integer,Auction>();
         this.timer = new Timer(true);
         this.notificationchannel = new LinkedBlockingQueue<Notification>();
         this.NotificationServer = new AuctionUDPMessageServer(notificationchannel,this.logger);
         this.outgoingmessagechannel = new LinkedBlockingQueue<Answer>();
         this.outgoingMessageServer = 
         * 
         */

        public AMS_Handler(CommandTask commandtask) {
            this.commandtask = commandtask;
            logger.output("AMS_HandlerThread Created", 2);
        }

        public void run() {
            logger.output("AMS_HandlerThread started", 2);
            if (this.commandtask.list != null) {
                try {
                    StringBuffer list = new StringBuffer();
                    list.append("list%");
                    Iterator<Map.Entry<Long, Auction>> iterator = auction_map.entrySet().iterator();
                    if (iterator.hasNext()) {//if no auction entry avaible, no sending
                        while (iterator.hasNext()) {
                            Map.Entry<Long, Auction> entry = iterator.next();
                            list.append(entry.getKey().toString() + "." + " '"
                                        + entry.getValue().getDescription() + "' "
                                        + entry.getValue().getOwner() + " "
                                        + entry.getValue().getEndDate() + " "
                                        + Double.toString(entry.getValue().getHighestBid()) + " "
                                        + entry.getValue().getHighestBidder() + " ");
                            if (entry.getValue().isGroupBid()) {
                                list.append(
                                        "GroupBid"+" "
                                      + entry.getValue().getFirstConfirmUsername() + " "
                                      + entry.getValue().getSecondConfirmUsername()
                                      + "\n");
                            } else {
                                list.append("RegularBid\n");
                            }
                        }
                        list.setCharAt(list.length() - 1, ' ');
                    }
                    Answer a = new Answer(list.toString(), this.commandtask.list.client);
                    outgoingmessagechannel.offer(a);
                } catch (Exception e) {
                    logger.output("AMSHandlerThread:list:Exception:" + e.getMessage());
                }
            } else if (this.commandtask.clientList != null) {
                logger.output("AMSHandlerThread:clientList", 2);
                try {
                    //TODO Stage4:test this block
                    StringBuffer clientList = new StringBuffer();
                    Iterator<Map.Entry<String, Account>> iterator = account_map.entrySet().iterator();
                    if (iterator.hasNext()) {
                        clientList.append("Active Clients:\n");
                        while (iterator.hasNext()) {
                            Map.Entry<String, Account> entry = iterator.next();
                            Client client_ = entry.getValue().getClient();
                            if (entry.getValue().isOnline() && !this.commandtask.clientList.name.equals(entry.getValue().getName())) {
                                clientList.append(
                                        entry.getValue().getHost()
                                        + ":"
                                        + entry.getValue().getUdpPort()
                                        + " - "
                                        + entry.getValue().getName()
                                        + "\n");
                            }
                        }
                    }

                    Answer a = new Answer(clientList.toString(), this.commandtask.clientList.client);
                    outgoingmessagechannel.offer(a);
                } catch (Exception e) {
                    logger.output("AMSHandlerThread:clientList:Exception:" + e.getMessage());
                }
            } else if (this.commandtask.login != null) {
                Account debug = null;
                try {
                    logger.output("AMSHandlerThread:login for user:" + commandtask.login.user, 3);
                    if (account_map.containsKey(this.commandtask.login.user)) {
                        logger.output("AMSHandlerThread:login start with existing account ", 3);
                        Account ac = null;
                        if ((ac = account_map.get(commandtask.login.user)) != null) {
                            ac.activateAccount(this.commandtask.login.client,
                                    this.commandtask.login.udpPort);
                            if (account_map.replace(commandtask.login.user, ac) != null) {
                                String answer = new String("Succesfully logged in as"
                                        + " " + commandtask.login.user + "!");
                                Answer a = new Answer(answer, commandtask.login.client);
                                outgoingmessagechannel.offer(a);
                                addOnlineUser();
                                logger.output("AMSHandlerThread:login finished:"
                                        + "user " + commandtask.login.user, 3);

                                /**
                                 * ********* RMI ******************************
                                 */
                                try {
                                    if (rmiAvailable && rmiAnalyticAvailable) {

                                        analytic.processEvents(
                                                new UserEvent(commandtask.login.user,
                                                UserEvent.UserEventType.USER_LOGIN));

                                        //TODO Stage4:test this block
                                        StringBuffer clientList = new StringBuffer();
                                        Iterator<Map.Entry<String, Account>> iterator = account_map.entrySet().iterator();
                                        if (iterator.hasNext()) {
                                            clientList.append("Active Clients:\n");
                                            while (iterator.hasNext()) {
                                                Map.Entry<String, Account> entry = iterator.next();
                                                Client client_ = entry.getValue().getClient();
                                                if (entry.getValue().isOnline() && !this.commandtask.clientList.name.equals(entry.getValue().getName())) {
                                                    clientList.append(
                                                            client_.getDestinationHost()
                                                            + ":"
                                                            + client_.getDestinationPort()
                                                            + " - "
                                                            + entry.getValue().getName()
                                                            + "\n");
                                                }
                                            }
                                        }

                                        a = new Answer(clientList.toString(), this.commandtask.clientList.client);
                                        outgoingmessagechannel.offer(a);

                                        logger.output("AMSHandlerThread:login:RMI"
                                                + ":processEvent:Invoke::"
                                                + "\nUser:" + commandtask.login.user, 3);

                                    }
                                } catch (RemoteException e) {
                                    logger.output("AMS_HandlerThread:login:RMI:RemoteException"
                                            + ":" + e.getMessage(), 2);
                                } catch (NullPointerException e) {
                                    logger.output("AMS_HandlerThread:login:RMI:NullPointerException"
                                            + ":" + e.getMessage(), 2);
                                }


                                /**
                                 * ********* RMI ******************************
                                 */
                            }
                        }
                    } else {
                        String name = null;
                        logger.output("AMSHandlerThread:login start with new account", 3);
                        Account acc = new Account(commandtask.login.user,
                                commandtask.login.client,
                                commandtask.login.udpPort,
                                notificationchannel);
                        name = acc.getName();
                        debug = account_map.put(name, acc);
                        if (account_map.containsKey(name)) {
                            String answer = new String("Succesfully logged in as"
                                    + " " + commandtask.login.user + "!");
                            Answer a = new Answer(answer, acc.getClient());
                            outgoingmessagechannel.offer(a);
                            addOnlineUser();
                            logger.output("AMSHandlerThread:login finished:"
                                    + "user " + commandtask.login.client, 3);
                            /**
                             * ********* RMI ******************************
                             */
                            try {
                                if (rmiAvailable && rmiAnalyticAvailable) {

                                    analytic.processEvents(
                                            new UserEvent(commandtask.login.user,
                                            UserEvent.UserEventType.USER_LOGIN));

                                    //TODO Stage4:test this block
                                    StringBuffer clientList = new StringBuffer();
                                    Iterator<Map.Entry<String, Account>> iterator = account_map.entrySet().iterator();
                                    if (iterator.hasNext()) {
                                        clientList.append("Active Clients:\n");
                                        while (iterator.hasNext()) {
                                            Map.Entry<String, Account> entry = iterator.next();
                                            Client client_ = entry.getValue().getClient();
                                            if (entry.getValue().isOnline() && !this.commandtask.clientList.name.equals(entry.getValue().getName())) {
                                                clientList.append(
                                                        client_.getDestinationHost()
                                                        + ":"
                                                        + client_.getDestinationPort()
                                                        + " - "
                                                        + entry.getValue().getName()
                                                        + "\n");
                                            }
                                        }
                                    }

                                    a = new Answer(clientList.toString(), this.commandtask.clientList.client);
                                    outgoingmessagechannel.offer(a);

                                    logger.output("AMSHandlerThread:login:RMI"
                                            + ":processEvent:Invoke::"
                                            + "\nUser:" + commandtask.login.user, 3);
                                }
                            } catch (RemoteException e) {
                                logger.output("AMS_HandlerThread:login:RMI:RemoteException"
                                        + ":" + e.getMessage(), 2);
                            } catch (NullPointerException e) {
                                logger.output("AMS_HandlerThread:login:RMI:NullPointerException"
                                        + ":" + e.getMessage(), 2);
                            }

                            /**
                             * ********* RMI ******************************
                             */
                        }
                    }
                } catch (AccountException e) {
                    logger.output("AMSHandlerThread:login:" + e.getMessage());
                } catch (NullPointerException e) {
                    logger.output("AMS_HandlerThread:login:NullPointerException"
                            + ":" + e.getMessage(), 2);
                } catch (Exception e) {
                    logger.output("AMSHandlerThread:login:Exception:" + e.getMessage());
                }


            } else if (this.commandtask.logout != null) {
                try {
                    Account ac = null;
                    if ((ac = account_map.get(commandtask.logout.user)) != null) {
                        ac.deactivateAccount();
                        if (account_map.replace(commandtask.logout.user, ac) != null) {
                            String answer = new String("Succesfully logged out as"
                                    + " " + commandtask.logout.user + "!");
                            Answer a = new Answer(answer, commandtask.logout.client);
                            outgoingmessagechannel.offer(a);
                            deleteOnlineUser();
                            /**Check Deadlock status again***/
                            int online=getNumberofOnlineUser();
                            int blocked=getNumberofBlockedUser();
                            if(online==blocked)
                            {
                                timer.schedule((new TentativeClose()), (10000));
                            }

                            /**
                             * ********* RMI ******************************
                             */
                            try {
                                if (rmiAvailable && rmiAnalyticAvailable) {
                                    Date now = new Date();
                                    UserEvent userevent = new UserEvent(
                                            commandtask.logout.user,
                                            UserEvent.UserEventType.USER_LOGOUT);
                                    long time = now.getTime() - ac.getLastLoginTime();
                                    userevent.setTime(time);
                                    logger.output("RMI:logout:Login time of "
                                            + ac.getName()
                                            + " is " + time + " ms.", 3);
                                    analytic.processEvents(userevent);
                                    logger.output("AMSHandlerThread:logout:RMI"
                                            + ":processEvent:Invoke::"
                                            + "User:" + commandtask.logout.user, 3);
                                }
                            } catch (RemoteException e) {
                                logger.output("AMS_HandlerThread:logout:RMI:RemoteException:"
                                        + e.getMessage(), 2);
                            } catch (NullPointerException e) {
                                logger.output("AMS_HandlerThread:logout:RMI:NullPointerException"
                                        + ":" + e.getMessage(), 2);
                            }
                            /**
                             * ********* RMI ******************************
                             */
                        } else {
                            throw new Exception("Could not deactivate User!");
                        }
                    } else {
                        throw new Exception("Didn't find user!");
                    }
                } catch (NullPointerException e) {
                    logger.output("AMSHandlerThread:logout:NullPointerException:" + e.getMessage());
                } catch (Exception e) {
                    logger.output("AMSHandlerThread:logout:Exception:" + e.getMessage());
                }
            } else if (this.commandtask.create != null) {
                try {
                    if (account_map.containsKey(commandtask.create.user)) {
                        Auction auc = new Auction(commandtask.create.user,
                                commandtask.create.expire, commandtask.create.description);
                        auction_map.put((new Long(auc.getID())), auc);
                        timer.schedule((new Task(auc.getID(), logger)), (auc.getPeriodofTime() * 1000));
                        String answer = new String("An auction" + " '"
                                + auc.getDescription() + "' " + "with id" + " "
                                + auc.getID() + " " + "has been created and will end on" + " "
                                + auc.getEndDate() + ".");
                        Answer a = new Answer(answer, commandtask.create.client);
                        outgoingmessagechannel.offer(a);

                        /**
                         * ********* RMI ******************************
                         */
                        try {
                            if (rmiAvailable && rmiAnalyticAvailable) {

                                analytic.processEvents(
                                        new AuctionEvent(AuctionEvent.AuctionType.AUCTION_STARTED,
                                        auc.getID()));
                                logger.output("AMSHandlerThread:create:RMI"
                                        + ":processEvent:Invoke::"
                                        + "AuctionID:" + auc.getID(), 3);
                            }
                        } catch (RemoteException e) {
                            logger.output("AMS_HandlerThread:RMI:create:RemoteException"
                                    + ":" + e.getMessage(), 2);
                        } catch (NullPointerException e) {
                            logger.output("AMS_HandlerThread:create:RMI:NullPointerException"
                                    + ":" + e.getMessage(), 2);
                        }
                        /**
                         * ********* RMI ******************************
                         */
                    }
                } catch (NullPointerException e) {
                    logger.output("AMS_HandlerThread:create:NullPointerException"
                            + ":" + e.getMessage(), 2);
                } catch (Exception e) {
                    logger.output("AMSHandlerThread:create:Exception:" + e.getMessage());
                }
            } else if (this.commandtask.bid != null) {

                Auction auc = null;
                boolean issuccesfullyBid;
                boolean isOnline;
                try {
                    logger.output("AMSHandlerThread:Bid:start", 2);
                    Long id = new Long(commandtask.bid.id);
                    //Get Auction from Hashmap
                    if ((auc = auction_map.get(id)) == null) {
                        throw new Exception("No Auction with id " + id.toString() + " found.");
                    }
                    Account oldBidder = null;
                    logger.output("AMSHandlerThread:Bid:oldBidder:"
                            + auc.getHighestBidder() + "," + auc.getHighestBidder().contains("none"), 2);
                    //boolean b = false;
                    //safe data about oldBidder
                    if (!(auc.getHighestBidder().contains("none"))) {
                        oldBidder = account_map.get(auc.getHighestBidder());
                        logger.output("AMSHandlerThread:Bid:oldBidder:" + oldBidder.getName(), 3);
                    }
                    //auction doesnt exist
                    if (auc == null) {
                        throw new Exception("Auction id is not avaible,canbot bid.");
                    }
                    Account acc=account_map.get(new String(commandtask.bid.user));
                    isOnline=acc.isOnline();
                    //Set a Normal Bid or set a GroupBid
                    if(this.commandtask.bid.isGroupBid)
                    {
                        issuccesfullyBid=auc.setnewGroupBid(commandtask.bid.user,
                                commandtask.bid.firstconfirmer, 
                                commandtask.bid.secondconfirmer, 
                                commandtask.bid.amount);
                    }else
                    {
                        issuccesfullyBid=auc.setnewBid(commandtask.bid.user, commandtask.bid.amount);
                    }
                    
                    if (issuccesfullyBid) {
                        logger.output("AMSHandlerThread:Bid:setNewBid:newBidder" + auc.getHighestBidder(), 3);
                        logger.output("AMSHandlerThread:Bid:setNewBid:newBid" + auc.getHighestBid(), 3);
                        auction_map.replace(id, auc);

                        String not = "!new-bid" + " " + auc.getDescription();
                        if(isOnline)
                        {
                            String ans;
                            ans = "You succesfully bid with"
                                    + " " + auc.getHighestBid() + " "
                                    + "on" + " '" + auc.getDescription() + "'.";

                            Answer a = new Answer(ans, commandtask.bid.client);
                            outgoingmessagechannel.offer(a);
                        }

                        /**
                         * ********* RMI ******************************
                         */
                        try {
                            if (rmiAvailable && rmiAnalyticAvailable) {

                                analytic.processEvents(
                                        new BidEvent(auc.getHighestBidder(),
                                        auc.getID(), auc.getHighestBid(),
                                        BidEvent.BidEventType.BID_PLACED));
                                logger.output("AMSHandlerThread:bid:RMI"
                                        + ":processEvent:Invoke::"
                                        + "AuctionID:" + auc.getID()
                                        + "\nUser:" + auc.getHighestBidder()
                                        + "\nPrice:" + auc.getHighestBid()
                                        + "\nType:BID_PLACED", 3);
                            }
                        } catch (RemoteException e) {
                            logger.output("AMS_HandlerThread:RMI:bid:RemoteException"
                                    + ":" + e.getMessage(), 2);
                        } catch (NullPointerException e) {
                            logger.output("AMS_HandlerThread:RMI:bid:NullPointerException"
                                    + ":" + e.getMessage(), 2);
                        }


                        /**
                         * ********* RMI ******************************
                         */
                        if (oldBidder != null) {
                            /*
                             if(oldBidder.getClient()!=null)
                             {
                                
                             Notification n = new Notification(not,oldBidder.getClientUDP());
                             notificationchannel.offer(n);
                             logger.output("AMSHandlerThread:Bid:Notification send to user"+oldBidder.getName(),3);
                                
                                
                             }else
                             {
                             oldBidder.addNotification(not);
                             logger.output("AMSHandlerThread:Bid:Notification store for user"+oldBidder.getName(),3);
                             }
                             */

                            /**
                             * ********* RMI ******************************
                             */
                            try {
                                if (rmiAvailable && rmiAnalyticAvailable) {

                                    analytic.processEvents(
                                            new BidEvent(oldBidder.getName(),
                                            auc.getID(),
                                            auc.getHighestBid(),
                                            BidEvent.BidEventType.BID_OVERBID));
                                    logger.output("AMSHandlerThread:bid:RMI"
                                            + ":processEvent:Invoke::"
                                            + "AuctionID:" + auc.getID()
                                            + "\nUser:" + auc.getHighestBidder()
                                            + "\nPrice:" + auc.getHighestBid()
                                            + "\nType:BID_OVERBID", 3);
                                }
                            } catch (RemoteException e) {
                                logger.output("AMS_HandlerThread:RMI:bid:RemoteException"
                                        + ":" + e.getMessage(), 2);
                            } catch (NullPointerException e) {
                                logger.output("AMS_HandlerThread:RMI:bid:NullPointerException"
                                        + ":" + e.getMessage(), 2);
                            }


                            /**
                             * ********* RMI ******************************
                             */
                        }

                    } else {
                        logger.output("AMSHandlerThread:Bid:unsuccesful", 3);
                         if(isOnline)
                        {
                            String ans = new String("You unsuccesfully bid with"
                                    + " " + commandtask.bid.amount + " "
                                    + "on" + " '" + auc.getDescription() + "'."
                                    + " Current highest bid is" + " "
                                    + auc.getHighestBid() + ".");

                            Answer a = new Answer(ans, commandtask.bid.client);
                            outgoingmessagechannel.offer(a);
                        }
                    }

                } catch (NullPointerException e) {
                    logger.output("AMS_HandlerThread:bid:NullPointerException"
                            + ":" + e.getMessage(), 2);
                } catch (Exception e) {
                    logger.output("AMSHandlerThread:bid:Exception:" + e.getMessage(), 2);
                }
//////////////////////////////////////////////////////////////////////////////////////////////////////////////
                /**
                 * *****************GROUP BID*********************************
                 */
            } else if (this.commandtask.groupbid != null) {
                boolean b;
               Answer a=null;
               Auction auc=null;
                boolean isvalidAuction=auction_map.containsKey(new Long(commandtask.groupbid.id));

                /*Number of group bids must be less or equal to the number of active users*/
                if(isvalidAuction){
                    auc=auction_map.get(new Long(commandtask.groupbid.id));
                    int countGroupBid=tentativeBids.getNumberofTentativeBids();
                    int onlineUser=getNumberofOnlineUser();
                    if(( (countGroupBid+1)<=onlineUser) )
                    {
                        if(!tentativeBids.isalreadyinsertedAuctionID(commandtask.groupbid.id))
                        {
                            if(!tentativeBids.isalreadyregistratedgroupBidUser(commandtask.groupbid.user))
                            {
                                b=tentativeBids.insertnewTentativeBid(
                                        commandtask.groupbid.id,
                                        commandtask.groupbid.user,
                                        commandtask.groupbid.amount);
                                if(b)
                                {
                                    a=new Answer("You successfully processed a new TentativeBid"+
                                            " on AuctionID "+commandtask.groupbid.id
                                            +" with "+commandtask.groupbid.amount
                                        ,commandtask.groupbid.client); 

                                }else
                                {
                                    a=new Answer("!rejected Failure in setting up"+
                                            "a new GroupBid on AuctionID "+commandtask.groupbid.id
                                        ,commandtask.groupbid.client);  
                                }
                            }else{
                                a=new Answer
                                        ("!rejected \n"+
                                        "Fairness Restriction allow every user a maximum\n"+
                                        "of one group Bid at every time.\n"+
                                        "This solution should be a prevention for starvation.\n"
                                        ,commandtask.groupbid.client);   
                            }
                        }else
                        {
                            a=new Answer("!rejected Cannot set multiple GroupBid"+
                                              " on the same Auction."
                                        ,commandtask.groupbid.client);
                        }
                    }else
                    {
                        a=new Answer("!rejected Maximum number of groupBid reached."
                                ,commandtask.groupbid.client);
                    }
                }else
                {
                     a=new Answer("!rejected AuctionID not valid."
                                ,commandtask.groupbid.client);
                }
                
                outgoingmessagechannel.offer(a);
                
////////////////////////////////////////////////////////////////////////////////////
            }else if(this.commandtask.confirmbid!=null){
                boolean validConfirm=tentativeBids.isvalidConfirm(commandtask.confirmbid.id,
                        commandtask.confirmbid.amount);
                //cannot be a confirmer if the user is blocked
                boolean isalredyFirstConfirmer=tentativeBids.isalreadyregistratedAsFirstConfirmedUser(commandtask.confirmbid.user);
                //boolean isalreadygroupBidUser =tentativeBids.isalreadyregistratedgroupBidUser(commandtask.confirmbid.user);
                
                boolean auctionhasOneConfirmer=tentativeBids.AuctionhasOneConfirmer(commandtask.confirmbid.id);
                int onlineUser=getNumberofOnlineUser();
                int blockedUser=getNumberofBlockedUser();
                
                Answer FirstConfirmer=null;
                Answer SecondConfirmer=null;
                CommandTask commandtask_=null;
                //if a other CommandTask must be triggered
                LinkedBlockingQueue<CommandTask> outgoingrequest=incomingrequest;
                
                /**Check DeadLock status if auction has no confirmer**/
                //if no DeadLock status will be reached
                if(validConfirm)
                {   //a confirm would block completely, because no other confirm is avaible
                    if(!auctionhasOneConfirmer) 
                    {   //proof Deadlock state
                        if((onlineUser>(blockedUser+1)))
                        {
                            if(!isalredyFirstConfirmer)
                            {
                                tentativeBids.confirm(commandtask.confirmbid.user,
                                            commandtask.confirmbid.id);
                                //isfirstConfirm
                                FirstConfirmer=new Answer("Confirm successfuly.",
                                commandtask.confirmbid.client);
                                addBlockedUser();  
  
                            }else
                            {
                                FirstConfirmer=new Answer("!rejected  Broken user statemachine.",commandtask.confirmbid.client);
                            }  

                        }else
                        {
                           ////A DeadLock status would be entered
                            SecondConfirmer=new Answer("!rejected Deadlock state reached.",commandtask.confirmbid.client); 
                        }
                    }else{
                       
                        TentativeBidEntry tbe=tentativeBids.confirm(commandtask.confirmbid.user,
                                            commandtask.confirmbid.id);
                        //is secondConfirm
                        if(tbe!=null)
                        {

                            Account acc=account_map.get(new String(tbe.getFirstConfirmUser()));
                            Account acc2=account_map.get(new String(tbe.groupBidUser));
                            Client clientFistConfirmer=acc.getClient();
                            FirstConfirmer=new Answer("!confirmed",clientFistConfirmer);
                            SecondConfirmer=new Answer("!confirmed",commandtask.confirmbid.client);
                            CommandTask.Bid bid = new CommandTask.Bid(
                                    acc2.getClient(),
                                    tbe.groupBidUser,
                                    tbe.getFirstConfirmUser(),
                                    tbe.getSecondConfirmUser(),
                                    tbe.getAuctionID(),
                                    tbe.getBid());
                            commandtask_=new CommandTask(bid);
                            deleteBlockedUser();
                        }else
                        {
                           FirstConfirmer=new Answer("!rejected  Broken user statemachine.",commandtask.confirmbid.client);
                        } 

                    }
                }else
                {
                    FirstConfirmer=new Answer("!rejected  Confirm doesn't correspond to a valid auction.",commandtask.confirmbid.client);                    
                }

                if(FirstConfirmer!=null)
                    outgoingmessagechannel.offer(FirstConfirmer);
                if(SecondConfirmer!=null)
                    outgoingmessagechannel.offer(SecondConfirmer);
                if(commandtask_!=null)
                    outgoingrequest.offer(commandtask_);
                    
/////////////////////////////////////////////////////////////////////////////////////////////////77             
            }else if (this.commandtask.end != null) {
                try {
                    String host = null;
                    int port = 0;
                    Account auc = null;
                    Iterator<Map.Entry<String, Account>> iterator = account_map.entrySet().iterator();
                    if (iterator.hasNext()) {//if no auction entry avaible, no sending
                        while (iterator.hasNext()) {
                            Map.Entry<String, Account> entry = iterator.next();
                            auc = entry.getValue();
                            if (auc.getClient() != null) {
                                host = auc.getClient().getDestinationHost();
                                port = auc.getClient().getDestinationPort();


                                if (this.commandtask.end.client.getDestinationHost().contentEquals(host)
                                        && (this.commandtask.end.client.getDestinationPort() == port)) {
                                    auc.deactivateAccount();
                                    account_map.replace(entry.getKey(), auc);
                                    deleteOnlineUser();
                                    int online=getNumberofOnlineUser();
                                    int blocked=getNumberofBlockedUser();
                                    if(online==blocked)
                                    {
                                        timer.schedule((new TentativeClose()), (10000));
                                    }
                                    logger.output("AMSHandlerThread:end:USER_DISCONNECTED:" + "\n"
                                            + "User " + auc.getName() + " was still logged in.", 2);
                                    /**
                                     * ********* RMI
                                     * ******************************
                                     */
                                    try {
                                        if (rmiAvailable && rmiAnalyticAvailable) {
                                            Date now = new Date();
                                            UserEvent userevent = new UserEvent(
                                                    auc.getName(),
                                                    UserEvent.UserEventType.USER_DISCONNECTED);
                                            long time = now.getTime() - auc.getLastLoginTime();
                                            userevent.setTime(time);
                                            logger.output("RMI:logout:Login time of "
                                                    + auc.getName()
                                                    + " is " + time + " ms.", 2);
                                            analytic.processEvents(userevent);

                                            logger.output("AMSHandlerThread:end:RMI"
                                                    + ":processEvent:Invoke::"
                                                    + "\nUser:" + auc.getName()
                                                    + "\nType:USER_DISCONNECTED", 3);
                                        }
                                    } catch (RemoteException e) {
                                        logger.output("AMS_HandlerThread:RMI:end:RemoteException"
                                                + ":" + e.getMessage(), 2);
                                    } catch (NullPointerException e) {
                                        logger.output("AMS_HandlerThread:RMI:end:NullPointerException"
                                                + ":" + e.getMessage(), 2);
                                    }


                                    /**
                                     * ********* RMI
                                     * ******************************
                                     */
                                }
                            }
                        }

                    }

                } catch (NullPointerException e) {
                    logger.output("AMS_HandlerThread:end:NullPointerException"
                            + ":" + e.getMessage(), 2);
                } catch (Exception e) {
                    logger.output("AMS_HandlerThread:end:Exception"
                            + ":" + e.getMessage(), 2);
                }
            } else if (this.commandtask.dummy != null) {

                Answer a = new Answer("!dummy", this.commandtask.dummy.client);
                outgoingmessagechannel.offer(a);

            } else if (this.commandtask.closeConnection != null) {
                Iterator<Map.Entry<String, Account>> iterator = account_map.entrySet().iterator();
                if (iterator.hasNext()) {
                    while (iterator.hasNext()) {
                        try {
                            Map.Entry<String, Account> entry = iterator.next();
                            entry.getValue().deactivateAccountandCloseConnection();
                        } catch (ClientException ex) {
                            logger.output("ClientException:" + ex.getMessage());
                        }
                    }
                }
                logger.output("All active connections to clients are closed.");
            }


            logger.output("AMS_HandlerThread finished", 2);
        }
    }
}
