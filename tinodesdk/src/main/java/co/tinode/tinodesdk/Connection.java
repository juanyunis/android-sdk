/**
 * Created by gene on 01/02/16.
 */
package co.tinode.tinodesdk;

import android.os.Handler;
import android.util.Log;

import com.neovisionaries.ws.client.WebSocket;
import com.neovisionaries.ws.client.WebSocketAdapter;
import com.neovisionaries.ws.client.WebSocketException;
import com.neovisionaries.ws.client.WebSocketFactory;
import com.neovisionaries.ws.client.WebSocketFrame;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Map;
import java.util.Random;

import co.tinode.tinodesdk.model.ServerMessage;

/**
 * Singleton class representing a streaming communication channel between a client and a server
 *
 * First call {@link Tinode#initialize(java.net.URL, String)}, then call {@link #getInstance()}
 *
 * Created by gene on 2/12/14.
 */
public class Connection {
    private static final String TAG = "co.tinode.Connection";

    private static Connection sConnection;

    private int mPacketCount = 0;
    private int mMsgId = 0;

    private Handler mHandler;
    private WebSocket mWsClient;

    // A list of outstanding requests, indexed by id
    protected SimpleArrayMap<String, Cmd> mRequests;

    // List of live subscriptions, key=[topic name], value=[topic, subscribed or
    // subscription pending]
    protected ArrayMap<String, co.tinode.tinodesdk.Topic<?>> mSubscriptions;

    // Exponential backoff/reconnecting
    // TODO(gene): implement autoreconnect
    private boolean autoreconnect;
    private ExpBackoff backoff;

    public static final String TOPIC_NEW = "new";
    public static final String TOPIC_ME = "me";
    public static final String TOPIC_GRP = "grp";
    public static final String TOPIC_P2P = "usr";

    protected Connection(URI endpoint, String apikey) throws IOException {

        String path = endpoint.getPath();
        if (path.equals("")) {
            path = "/";
        } else if (path.lastIndexOf("/") != path.length() - 1) {
            path += "/";
        }
        path += "channels"; // http://www.example.com/v0/channels

        URI uri;
        try {
            uri = new URI(endpoint.getScheme(),
                    endpoint.getUserInfo(),
                    endpoint.getHost(),
                    endpoint.getPort(),
                    path,
                    endpoint.getQuery(),
                    endpoint.getFragment());
        } catch (URISyntaxException e) {
            e.printStackTrace();
            return;
        }

        WebSocketFactory wsf = new WebSocketFactory();
        mWsClient = wsf.createSocket(uri, 5000);
        mWsClient.addHeader("X-Tinode-APIKey", apikey);
        mWsClient.addListener(new WebSocketAdapter() {
            @Override
            public void onConnected(WebSocket ws, Map<String, List<String>> headers) {
                Log.d(TAG, "Websocket connected!");
                if (mHandler != null) {
                    mHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            onWsConnect();
                        }
                    });
                } else {
                    onWsConnect();
                }
            }

            @Override
            public void onTextMessage(WebSocket ws, final String message) {
                Log.d(TAG, message);
                mPacketCount++;
                if (mHandler != null) {
                    mHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            onWsMessage(message);
                        }
                    });
                } else {
                    onWsMessage(message);
                }
            }

            @Override
            public void onBinaryMessage(WebSocket ws, byte[] data) {
                // do nothing, server does not send binary frames
                Log.i(TAG, "binary message received (should not happen)");
            }

            @Override
            public void onDisconnected(WebSocket ws,
                                       WebSocketFrame serverCloseFrame,
                                       WebSocketFrame clientCloseFrame,
                                       final boolean closedByServer) {
                Log.d(TAG, "Disconnected :(");
                // Reset packet counter
                mPacketCount = 0;
                final WebSocketFrame frame = closedByServer ? serverCloseFrame : clientCloseFrame;
                if (mHandler != null) {
                    mHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            onWsDisconnect(closedByServer, frame.getCloseCode(), frame.getCloseReason());
                        }
                    });
                } else {
                    onWsDisconnect(closedByServer, frame.getCloseCode(), frame.getCloseReason());
                }

                if (autoreconnect) {
                    // TODO(gene): add autoreconnect
                }
            }

            @Override
            public void onError(WebSocket ws, final WebSocketException error) {
                Log.i(TAG, "Connection error", error);
                if (mHandler != null) {
                    mHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            onWsError(error);
                        }
                    });
                } else {
                    onWsError(error);
                }
            }
        });
    }

    protected void onWsConnect() {
        if (backoff != null) {
            backoff.reset();
        }
    }

    protected void onWsMessage(String message) {
        ServerMessage pkt = parseServerMessageFromJson(message);
        if (pkt == null) {
            Log.i(TAG, "Failed to parse packet");
            return;
        }

        boolean dispatchDone = false;
        if (pkt.ctrl != null) {
            if (mPacketCount == 1) {
                // The first packet from a fresh connection
                if (mListener != null) {
                    mListener.onConnect(pkt.ctrl.code, pkt.ctrl.text, pkt.ctrl.getParams());
                    dispatchDone = true;
                }
            }
        } else if (pkt.data == null) {
            Log.i(TAG, "Empty packet received");
        }

        if (!dispatchDone) {
            // Dispatch message to topics
            if (mListener != null) {
                dispatchDone = mListener.onMessage(pkt);
            }

            if (!dispatchDone) {
                dispatchPacket(pkt);
            }
        }
    }

    protected void onWsDisconnect(boolean byServer, int code, String reason) {
        // Dump all records of pending requests, responses won't be coming anyway
        mRequests.clear();
        // Inform topics that they were disconnected, clear list of subscribe topics
        disconnectTopics();
        mSubscriptions.clear();

        Tinode.clearMyId();

        if (mListener != null) {
            mListener.onDisconnect(code, reason);
        }
    }

    protected void onWsError(Exception err) {
        // do nothing
    }

    /**
     * Listener for connection-level events. Don't override onMessage for default behavior.
     *
     * By default all methods are called on the websocket thread. If you want to change that
     * set handler with {@link #setHandler(android.os.Handler)}
     */
    public void setListener(EventListener l) {
        mListener = l;
    }
    public EventListener getListener() {
        return mListener;
    }

    /**
     * By default all {@link Connection.EventListener} methods are called on websocket thread.
     * If you want to change that and, for instance, have them called on the main application thread, do something
     * like this: {@code mConnection.setHandler(new Handler(Looper.getMainLooper()));}
     *
     * @param h handler to use
     * @see android.os.Handler
     * @see android.os.Looper
     */
    public void setHandler(Handler h) {
        mHandler = h;
    }

    /**
     * Get an instance of Connection if it already exists or create a new instance.
     *
     * @return Connection instance
     */
    public static Connection getInstance() {
        if (sConnection == null) {
            sConnection = new Connection(Tinode.getEndpointUri(), Tinode.getApiKey());
        }
        return sConnection;
    }

    /**
     * Establish a connection with the server. It opens a websocket in a separate
     * thread. Success or failure will be reported through callback set by
     * {@link #setListener(Connection.EventListener)}.
     *
     * This is a non-blocking call.
     *
     * @param autoreconnect not implemented yet
     * @return true if a new attempt to open a connection was performed, false if connection already exists
     */
    public boolean Connect(boolean autoreconnect) {
        // TODO(gene): implement autoreconnect
        this.autoreconnect = autoreconnect;

        if (!mWsClient.getState().) {
            mWsClient.connect();
            return true;
        }

        return false;
    }

    /**
     * Gracefully close websocket connection
     *
     * @return true if an actual attempt to disconnect was made, false if there was no connection already
     */
    public boolean Disconnect() {
        if (mWsClient.isConnected()) {
            mWsClient.disconnect();
            return true;
        }

        return false;
    }

    /**
     * TODO(gene): implement autoreconnect with exponential backoff
     */
    class ExpBackoff {
        private int mRetryCount = 0;
        final private long SLEEP_TIME_MILLIS = 500; // 500 ms
        final private long MAX_DELAY = 1800000; // 30 min
        private Random random = new Random();

        void reset() {
            mRetryCount = 0;
        }

        /**
         *
         * @return
         */
        long getSleepTimeMillis() {
            int attempt = mRetryCount;
            return Math.min(SLEEP_TIME_MILLIS * (random.nextInt(1 << attempt) + (1 << (attempt+1))),
                    MAX_DELAY);
        }
    }
}
