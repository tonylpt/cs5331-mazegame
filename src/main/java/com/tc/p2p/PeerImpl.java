package com.tc.p2p;

import com.sun.scenario.effect.impl.sw.sse.SSEBlend_SRC_OUTPeer;

import javax.swing.*;
import java.awt.*;
import java.rmi.AlreadyBoundException;
import java.rmi.NotBoundException;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;
import java.util.*;

import static com.tc.p2p.Reply.*;
import static com.tc.p2p.Reply.MoveReply.PromotionStatus.*;

/**
 * @author lpthanh
 */
public class PeerImpl extends UnicastRemoteObject implements Peer {

    public static final String NAME_PEER = "YUMMY_PEAR";

    private final RMIServer rmiServer;

    private GameState gameState;


    /**
     * Will be null if this peer is not the primary server
     */
    private PrimaryServer primaryServer;

    /**
     * Will be null if this peer is not the backup server
     */
    private BackupServer backupServer;

    public PeerImpl(boolean primary, String host, int port, int N, int M) throws RemoteException {
        this.rmiServer = createServer(this, primary, port);

        if (this.rmiServer == null) {
            throw new RuntimeException("Unable to create Peer object");
        }

        if (primary) {
            // init the waiting / the game state
            initialStartPrimary(N,M);
        }

        // connect to the primary
        initialConnectToPrimary(host, port);
    }

    private void checkPrimary() {
        if (primaryServer == null) {
            throw new Error("Primary Server is unavailable.");
        }
    }

    private void checkBackup() {
        if (backupServer == null) {
            throw new Error("Backup Server is unavailable.");
        }
    }

    /**
     * Performed by Primary server
     */
    @Override
    public Reply callPrimaryJoin(Peer peer) throws RemoteException {
        checkPrimary();
        return primaryServer.join(peer);
    }

    @Override
    public Reply callPrimaryMove(Peer peer, char direction, String playerID) throws RemoteException {
        checkPrimary();
        return primaryServer.move(peer, direction, playerID);
    }

    @Override
    public PingReply ping() throws RemoteException {
        PingReply pingReply = null;
        return null;
    }

    @Override
    public Reply callBackupUpdate(GameState gameState) throws RemoteException {
        checkBackup();
        return backupServer.update(gameState);
    }


    public void startPollingTimer() {
        final java.util.Timer timer = new java.util.Timer();
        final TimerTask task = new TimerTask() {

            @Override
            public void run() {
                System.out.println("poll server and update primary & backup server ");
                //ping primary, if fails, ping backup. get latest gamestate/serverConfig

                try {
                    PingReply reply = (PingReply) ping();
                    gameState = reply.getGameState();
                } catch (RemoteException e) {
                    e.printStackTrace();
                }

            }
        };


        timer.schedule(task, 0, 30000);
    }

    @Override
    public Reply callClientGameStarted(String playerID, GameState gameState) throws RemoteException {
        System.out.println("Game is started");

        this.gameState = gameState;
        Player me = null;
        for(Player onePlayer : gameState.getPlayerList()){
            if(onePlayer.getPlayerID().equals(playerID)){
                me = onePlayer;
                break;
            }
        }
        System.out.println("My playerID: " + playerID);
        System.out.println("My Coordinate " + me.getCordx() + ":" + me.getCordy());
        //startPollingTimer();

        SwingUtilities.invokeLater(new Runnable() {

            class Step {
                public void step(char ch) {
                    MoveReply moveReply;
                    System.out.println("moving");
                    try {
                        moveReply = (MoveReply) PeerImpl.this.gameState.getServerConfig().getPrimaryServer().callPrimaryMove(PeerImpl.this, ch, playerID);
                        if (moveReply.getPromotionStatus() == MoveReply.PromotionStatus.PROMOTED_TO_BACKUP) {
                            becomeBackup(moveReply.getGameState());
                        }
                        PeerImpl.this.gameState = moveReply.getGameState();

                        if (moveReply.isIllegalMove()) {
                            System.out.println("Move is illegal");
                        } else {
                            PeerImpl.this.gameState.printGamestate();
                        }

                    } catch (RemoteException e1) {
                        //primary down,
                        System.out.println("Primary is down");
                        try {
                            moveReply = (MoveReply) PeerImpl.this.gameState.getServerConfig().getBackupServer().primaryDied(PeerImpl.this);
                            if (moveReply.getPromotionStatus() == MoveReply.PromotionStatus.PROMOTED_TO_PRIMARY) {
                                becomePrimary(moveReply.getGameState());
                                step(ch);
                            } else if (moveReply.getPromotionStatus() == IS_BACKUP) {
                                System.out.println("I am Backup, no primary, no move");
                                //no rerun step
                            } else {
                                // update new primary
                                PeerImpl.this.gameState = moveReply.getGameState();
                                step(ch);
                            }
                        } catch (RemoteException e2) {
                            e2.printStackTrace();
                        }
                    }
                }
            }

            @Override
            public void run() {
                JFrame jFrame = new JFrame();
                JButton upButton = new JButton("UP");
                JButton downButton = new JButton("DOWN");
                JButton leftButton = new JButton("LEFT");
                JButton rightButton = new JButton("RIGHT");
                final Step step = new Step();
                upButton.addActionListener(e -> step.step('N'));
                downButton.addActionListener(e -> step.step('S'));
                leftButton.addActionListener(e -> step.step('W'));
                rightButton.addActionListener(e -> step.step('E'));

                jFrame.getContentPane().add(upButton, BorderLayout.NORTH);
                jFrame.getContentPane().add(downButton, BorderLayout.SOUTH);
                jFrame.getContentPane().add(leftButton, BorderLayout.WEST);
                jFrame.getContentPane().add(rightButton, BorderLayout.EAST);
                jFrame.setTitle("game");
                jFrame.setSize(500, 300);
                jFrame.setVisible(true);
            }
        });

        return new GameStartReply();
    }

    @Override
    public Reply callClientGameEnded() throws RemoteException {
        return null;
    }


    @Override
    public Reply primaryDied(Peer peer) throws RemoteException {

        return backupServer.primaryDied(peer);
    }

    private void initialStartPrimary(int N, int M) {
        this.primaryServer = new PrimaryServer(this, N, M);
        this.primaryServer.startInitialTimer();
    }

    private void initialConnectToPrimary(String host, int port) {
        try {
            System.out.println("Connecting to Primary Server at " + host + ":" + port);
            Registry registry = LocateRegistry.getRegistry(host, port);
            Peer primaryServer = (Peer) registry.lookup(NAME_PEER);
            Reply reply = primaryServer.callPrimaryJoin(this);

            if (reply instanceof JoinDeclined) {
                JoinDeclined joinReply = (JoinDeclined) reply;
                System.out.println("Join Failed. Reason: " + joinReply.reason);
            } else if (reply instanceof JoinSucceeded) {
                JoinSucceeded joinReply = (JoinSucceeded) reply;
                System.out.println("Join success. Player ID = " + joinReply.getPlayerId());
            } else if (reply instanceof JoinAsBackup) {
                JoinAsBackup joinReply = (JoinAsBackup) reply;
                System.out.println("Join success. Should become backup. Player ID = " + joinReply.getPlayerId());
                joinAsBackup(joinReply.getGameState());
            } else {
                throw new RuntimeException("Unrecognized response: " + reply.getClass());
            }

        } catch (RemoteException | NotBoundException e) {
            System.err.println("Unable to connect to the Primary Server");
            e.printStackTrace();
            System.exit(0);
        }
    }

    private void joinAsBackup(GameState gameState) {
        System.out.println("Joining as backup server");
        gameState.getServerConfig().setBackupServer(this);
        this.gameState = gameState;
        this.backupServer = new BackupServer(this, gameState);
    }

    private void becomeBackup(GameState gameState) {
        System.out.println("Becoming backup server");
//        this.gameState.getServerConfig().setBackupServer(this);
        this.gameState = gameState;
        this.backupServer = new BackupServer(this, gameState);
    }

    private void becomePrimary(GameState gameState) {
        System.out.println("Becoming primary server");
        this.primaryServer = new PrimaryServer(this, gameState);
        this.gameState = gameState;
        this.gameState.getServerConfig().setPrimaryServer(this);
        //update backup after becoming primary
        try {
            this.gameState.getServerConfig().getBackupServer().callBackupUpdate(this.gameState);
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }

    /**
     * @return null if error
     */
    private static RMIServer createServer(Peer peer, boolean primary, int port) {
        Registry registry = null;
        int listenPort = 0;

        if (primary) {
            // try to create registry on the port

            try {
                listenPort = port;
                registry = LocateRegistry.createRegistry(listenPort);
            } catch (RemoteException e) {
                throw new RuntimeException("Cannot create Primary peer", e);
            }

        } else {

            // try to find an available port to create the registry on
            Random randomizer = new Random();
            boolean retry = false;
            do {
                try {
                    listenPort = port + randomizer.nextInt(1000);
                    registry = LocateRegistry.createRegistry(listenPort);
                } catch (RemoteException e) {
                    // the exception may have been caused by the port's unavailability
                    retry = true;
                }
            } while (retry);
        }

        try {
            registry.bind(NAME_PEER, peer);
        } catch (RemoteException | AlreadyBoundException e) {
            System.err.println("Unable to bind the peer");
            e.printStackTrace();
            return null;
        }

        return new RMIServer(registry, listenPort);
    }

    /**
     * Every peer acts as an RMI Server. This class is to contain the port that the peer listens on,
     * and the registry created on that port.
     */
    private static class RMIServer {

        private final Registry registry;

        private final int listenPort;

        public RMIServer(Registry registry, int listenPort) {
            this.registry = registry;
            this.listenPort = listenPort;
        }

        public Registry getRegistry() {
            return registry;
        }

        public int getListenPort() {
            return listenPort;
        }

    }
}