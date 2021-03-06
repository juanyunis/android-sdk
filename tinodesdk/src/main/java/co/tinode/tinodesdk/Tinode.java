package co.tinode.tinodesdk;

import android.os.Build;
import android.util.Log;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.TypeFactory;

import java.io.IOException;
import java.io.InvalidObjectException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import co.tinode.tinodesdk.model.*;

public class Tinode {
    private static final String TAG = "tinodesdk.Tinode";

    protected static final String TOPIC_NEW = "new";
    protected static final String TOPIC_ME = "me";

    private static final String PROTOVERSION = "0";
    private static final String VERSION = "0.5";
    private static final String LIBRARY = "tindroid/" + VERSION;

    private static ObjectMapper sJsonMapper;
    private static TypeFactory sTypeFactory;

    private String mApiKey;
    private String mServerHost;
    private String mAppName;

    private Connection mConnection;

    private String mServerVersion = null;
    private String mServerBuild = null;

    private String mMyUid;
    private int mPacketCount;
    private int mMsgId;
    private boolean nNoEchoOnPub = false;

    private EventListener mListener;

    private ConcurrentMap<String, PromisedReply<ServerMessage>> mFutures;
    private HashMap<String, Topic> mTopics;

    static {
        sJsonMapper = new ObjectMapper();
        // Silently ignore unknown properties
        sJsonMapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        // Skip null fields from serialization
        sJsonMapper.setSerializationInclusion(JsonInclude.Include.NON_EMPTY);
        sTypeFactory = sJsonMapper.getTypeFactory();
    }
    /**
     * Initialize Tinode package
     *
     * @param appname name of the application to include in User Agent on login.
     * @param host    host name of the server, e.g. 'api.tinode.co' or 'localhost:8080'
     * @param apikey  API key generate by key-gen utility
     */
    public Tinode(String appname, String host, String apikey) {

        mAppName = appname;
        mApiKey = apikey;
        mServerHost = host;

        mFutures = new ConcurrentHashMap<>(16, 0.75f, 4);
        mTopics = new HashMap<>();
    }

    public PromisedReply<Void> connect() {
        final PromisedReply<Void> connected = new PromisedReply<>();
        if (mConnection == null) {
            try {
                mConnection = new Connection(
                        new URI("ws://" + mServerHost + "/v" + PROTOVERSION + "/"),
                        mApiKey, new Connection.WsListener() {

                    @Override
                    protected void onConnect() {
                        try {
                            connected.resolve(null);
                        } catch (Exception e) {
                            onError(e);
                        }
                    }

                    @Override
                    protected void onMessage(String message) {
                        try {
                            dispatchPacket(message);
                        } catch (Exception e) {
                            Log.e(TAG, "Exception in dispatchPacket: ", e);
                        }
                    }

                    @Override
                    protected void onDisconnect(boolean byServer, int code, String reason) {
                        mFutures.clear();
                        if (mListener != null) {
                            mListener.onDisconnect(byServer, code, reason);
                        }
                    }

                    @Override
                    protected void onError(Exception err) {
                        mFutures.clear();
                        if (mListener != null) {
                            mListener.onDisconnect(true, 0, err.getMessage());
                        }
                        try {
                            connected.reject(err);
                        } catch (Exception e) {
                            Log.e(TAG, "Exception in Connection.onError: ", e);
                        }
                    }
                });
            } catch (URISyntaxException | IOException e) {
                try {
                    connected.reject(e);
                } catch (Exception ignored) {}
            }
        }

        mConnection.connect(true);

        return connected;
    }

    /**
     * Finds topic for the packet and calls topic's appropriate routeXXX method.
     * This method can be safely called from the UI thread after overriding
     * {@link Connection.WsListener#onMessage(String)}
     **
     * @param message message to be parsed dispatched
     */
    private void dispatchPacket(String message) throws Exception {
        if (message == null || message.equals(""))
            return;

        Log.d(TAG, "in: " + message);

        mPacketCount++;

        if (mListener != null) {
            mListener.onRawMessage(message);
        }

        ServerMessage pkt = parseServerMessageFromJson(message);
        if (pkt == null) {
            Log.i(TAG, "Failed to parse packet");
            return;
        }

        if (mListener != null) {
            mListener.onMessage(pkt);
        }

        if (pkt.ctrl != null) {

            if (mListener != null) {
                mListener.onCtrlMessage(pkt.ctrl);
            }
            if (mPacketCount == 1) {
                if (pkt.ctrl.params != null) {
                    mServerVersion = (String) pkt.ctrl.params.get("ver");
                    mServerBuild = (String) pkt.ctrl.params.get("build");
                }
                if (mListener != null) {
                    mListener.onConnect(pkt.ctrl.code, pkt.ctrl.text, pkt.ctrl.params);
                }
            }

            PromisedReply<ServerMessage> r = mFutures.remove(pkt.ctrl.id);
            if (r != null) {
                r.resolve(pkt);
            }
        } else if (pkt.meta != null) {
            Topic topic = mTopics.get(pkt.meta.topic);
            if (topic != null) {
                topic.routeMeta(pkt.meta);
            }

            if (mListener != null) {
                mListener.onMetaMessage(pkt.meta);
            }
        } else if (pkt.data != null) {
            Topic topic = mTopics.get(pkt.data.topic);
            if (topic != null) {
                topic.routeData(pkt.data);
            }

            if (mListener != null) {
                mListener.onDataMessage(pkt.data);
            }
        } else if (pkt.pres != null) {
            Topic topic = mTopics.get(pkt.pres.topic);
            if (topic != null) {
                topic.routePres(pkt.pres);
            }

            if (mListener != null) {
                mListener.onPresMessage(pkt.pres);
            }
        } else if (pkt.info != null) {
            Topic topic = mTopics.get(pkt.info.topic);
            if (topic != null) {
                topic.routeInfo(pkt.info);
            }

            if (mListener != null) {
                mListener.onInfoMessage(pkt.info);
            }
        }

        // TODO(gene): decide what to do on unknown message type
    }

    public String getApiKey() {
        return mApiKey;
    }

    public String getServerHost() {
        return mServerHost;
    }

    public String getMyId() {
        return mMyUid;
    }

    public boolean isAuthenticated() {
        return (mMyUid != null);
    }

    public static TypeFactory getTypeFactory() {
        return sTypeFactory;
    }

    public static ObjectMapper getJsonMapper() {
        return sJsonMapper;
    }

    protected String makeUserAgent() {
        return mAppName + " (Android " + Build.VERSION.RELEASE + "; "
                + Locale.getDefault().toString() + "; "
                + Build.MANUFACTURER + " " + Build.MODEL + "/" + Build.PRODUCT +
                ") " + LIBRARY;
    }

    /**
     * Send a basic login packet to the server. A connection must be established prior to calling
     * this method. Success or failure will be reported through {@link EventListener#onLogin(int, String)}
     *
     *  @param uname user name
     *  @param password password
     *  @return PromisedReply of the reply ctrl message
     *  @throws IOException if there is no connection
     */
    public PromisedReply<ServerMessage> loginBasic(String uname, String password) throws IOException, Exception {
        return login(MsgClientLogin.LOGIN_BASIC, MsgClientLogin.makeBasicToken(uname, password));
    }

    protected PromisedReply<ServerMessage> login(String scheme, String secret) throws IOException, Exception {
        ClientMessage msg = new ClientMessage(new MsgClientLogin(getNextId(), scheme, secret, makeUserAgent()));
        try {
            send(Tinode.getJsonMapper().writeValueAsString(msg));
            PromisedReply<ServerMessage> future = null;
            if (msg.login.id != null) {
                future = new PromisedReply<ServerMessage>().
                        thenApply(
                                new PromisedReply.SuccessListener<ServerMessage>() {
                                    @Override
                                    public PromisedReply<ServerMessage> onSuccess(ServerMessage pkt) throws Exception {
                                        if (pkt.ctrl == null) {
                                            throw new InvalidObjectException("Unexpected type of reply packet in login");
                                        }
                                        mMyUid = (String) pkt.ctrl.params.get("uid");
                                        return null;
                                    }
                                }, null);
                mFutures.put(msg.login.id, future);
            }
            return future;
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    /**
     * Low-level subscription request. The subsequent messages on this topic will not
     * be automatically dispatched. A {@link Topic#subscribe()} should be normally used instead.
     *
     * @param topicName name of the topic to subscribe to
     * @return id of the sent subscription packet, if {@link #wantAkn(boolean)} is set to true, null otherwise
     * @throws IOException
     */
    public PromisedReply subscribe(String topicName) throws IOException {
        ClientMessage msg = new ClientMessage(new MsgClientSub());
        msg.sub.id = getNextId();
        msg.sub.topic = topicName;
        try {
            send(Tinode.getJsonMapper().writeValueAsString(msg));
            PromisedReply<ServerMessage> future = new PromisedReply<>();
            mFutures.put(msg.sub.id, future);
            return future;
        } catch (JsonProcessingException e) {
            Log.i(TAG, "Failed to serialize message", e);
            return null;
        }
    }

    /**
     * Low-level request to unsubscribe topic. A {@link Topic#leave(boolean)} should be normally
     * used instead.
     *
     * @param topicName name of the topic to subscribe to
     * @return id of the sent subscription packet, if {@link #wantAkn(boolean)} is set to true, null otherwise
     * @throws IOException
     */
    public PromisedReply leave(String topicName, boolean unsub) throws IOException {
        ClientMessage msg = new ClientMessage(new MsgClientLeave());
        msg.leave.id = getNextId();
        msg.leave.topic = topicName;
        msg.leave.unsub = unsub;
        try {
            send(Tinode.getJsonMapper().writeValueAsString(msg));
            PromisedReply<ServerMessage> future = new PromisedReply<>();
            mFutures.put(msg.sub.id, future);
            return future;
        } catch (JsonProcessingException e) {
            return null;
        }
    }


    /**
     * Low-level request to publish data. A {@link Topic#Publish(Object)} should be normally
     * used instead.
     *
     * @param topicName name of the topic to publish to
     * @param data payload to publish to topic
     * @return id of the sent packet, if {@link #wantAkn(boolean)} is set to true, null otherwise
     * @throws IOException
     */
    @SuppressWarnings("unchecked")
    public PromisedReply publish(String topicName, Object data) throws IOException {
        ClientMessage msg = new ClientMessage(new MsgClientPub<>(getNextId(), topicName, nNoEchoOnPub, data));
        try {
            send(Tinode.getJsonMapper().writeValueAsString(msg));
            PromisedReply<ServerMessage> future = new PromisedReply<>();
            mFutures.put(msg.pub.id, future);
            return future;
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    /**
     * Writes a string to websocket.
     *
     * @param data string to write to websocket
     */
    protected void send(String data) {
        mConnection.send(data);
    }

    /**
     * Request server to send acknowledgement packets. Server responds with such packets if
     * client includes non-empty id fiel0d into outgoing packets.
     * If set to true, {@link #getNextId()} will return a string representation of a random integer
     * between 64K and 128K
     *
     * @param akn true to request akn packets, false otherwise
     * @return previous value
     */
    public boolean wantAkn(boolean akn) {
        boolean prev = (mMsgId != 0);
        if (akn) {
            mMsgId = 0xFFFF + (int) (Math.random() * 0xFFFF);
        } else {
            mMsgId = 0;
        }
        return prev;
    }

    /**
     * Obtain a subscribed !me topic ({@link MeTopic}).
     *
     * @return subscribed !me topic or null if !me is not subscribed
     */
    public MeTopic<?> getMeTopic() {
        return (MeTopic) mTopics.get(TOPIC_ME);
    }


    /**
     * Obtain a subscribed topic by name
     *
     * @param name name of the topic to find
     * @return subscribed topic or null if no such topic was found
     */
    public Topic<?> getTopic(String name) {
        return mTopics.get(name);
    }

    protected void putTopic(Topic<?> topic) {
        mTopics.put(topic.getName(), topic);
    }

    /**
     * Parse JSON received from the server into {@link ServerMessage}
     *
     * @param jsonMessage message to parse
     * @return ServerMessage or null
     */
    @SuppressWarnings("unchecked")
    protected ServerMessage parseServerMessageFromJson(String jsonMessage) {
        MsgServerCtrl ctrl = null;
        MsgServerData<?> data = null;
        try {
            ObjectMapper mapper = Tinode.getJsonMapper();
            JsonParser parser = mapper.getFactory().createParser(jsonMessage);
            // Sanity check: verify that we got "Json Object":
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                throw new JsonParseException(parser, "Packet must start with an object",
                        parser.getCurrentLocation());
            }
            // Iterate over object fields:
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                String name = parser.getCurrentName();
                parser.nextToken();
                switch (name) {
                    case "ctrl":
                        ctrl = mapper.readValue(parser, MsgServerCtrl.class);
                        break;
                    case "data":
                        data = parseMsgServerData(parser);
                        break;
                    default:  // Unrecognized field, ignore
                        Log.i(TAG, "Unknown field in packet: '" + name + "'");
                        break;
                }
            }
            parser.close(); // important to close both parser and underlying reader
        } catch (IOException e) {
            e.printStackTrace();
        }

        if (ctrl != null) {
            return new ServerMessage(ctrl);
        } else if (data != null) {
            // This generates the "unchecked" warning
            return new ServerMessage(data);
        }
        return null;
    }

    protected MsgServerData<?> parseMsgServerData(JsonParser parser) throws IOException {
        ObjectMapper mapper = Tinode.getJsonMapper();
        JsonNode data = mapper.readTree(parser);
        if (data.has("topic")) {
            String topicName = data.get("topic").asText();
            Topic<?> topic = getTopic(topicName);
            JavaType typeOfDataContent = topic.getTypeOfDataContent();
            MsgServerData packet = new MsgServerData();
            if (data.has("id")) {
                packet.id = data.get("id").asText();
            }
            packet.topic = topicName;
            if (data.has("origin")) {
                packet.from = data.get("origin").asText();
            }
            if (data.has("content")) {
                packet.content = mapper.readValue(data.get("content").traverse(), typeOfDataContent);
            }
            return packet;
        } else {
            throw new JsonParseException(parser, "Invalid data packet: missing topic name",
                    parser.getCurrentLocation());
        }
    }

    /**
     * Get a string representation of a random number, to be used as a packet id.
     *
     * @return reasonably unique id
     * @see #wantAkn(boolean)
     */
    synchronized private String getNextId() {
        if (mMsgId == 0) {
            return null;
        }
        return String.valueOf(++mMsgId);
    }

    /**
     * Create promise which will be resolved or rejected when a message with the given id is
     * received.
     *
     * @param id id of the message which will resolve/reject this promise
     * @return created promise
     */
    private PromisedReply<ServerMessage> makePromise(String id) {
        PromisedReply<ServerMessage> promise = new PromisedReply<>();
        mFutures.put(id, promise);
        return promise;
    }

    /**
     * Callback interface called by Connection when it receives events from the websocket.
     *
     */
    public static class EventListener {
        /**
         * Connection was established successfully
         *
         * @param code should be always 201
         * @param reason should be always "Created"
         * @param params server parameters, such as protocol version
         */
        public void onConnect(int code, String reason, Map<String, Object> params) {
        }

        /**
         * Connection was dropped
         *
         * @param byServer true if connection was closed by server
         * @param code numeric code of the error which caused connection to drop
         * @param reason error message
         */
        public void onDisconnect(boolean byServer, int code, String reason) {
        }

        /**
         * Result of successful or unsuccessful {@link #login} attempt.
         *
         * @param code a numeric value between 200 and 2999 on success, 400 or higher on failure
         * @param text "OK" on success or error message
         */
        public void onLogin(int code, String text) {
        }

        /**
         * Handle generic server message.
         *
         * @param msg message to be processed
         */
        public void onMessage(ServerMessage<?,?,?> msg) {
        }

        /**
         * Handle unparsed message. Default handler calls {@code #dispatchPacket(...)} on a
         * websocket thread.
         * A subclassed listener may wish to call {@code dispatchPacket()} on a UI thread
         *
         * @param msg message to be processed
         */
        public void onRawMessage(String msg) {
        }

        /**
         * Handle control message
         *
         * @param ctrl control message to process
         */
        public void onCtrlMessage(MsgServerCtrl ctrl) {
        }

        /**
         * Handle data message
         *
         * @param data control message to process
         */
        public void onDataMessage(MsgServerData<?> data) {
        }

        /**
         * Handle info message
         *
         * @param info info message to process
         */
        public void onInfoMessage(MsgServerInfo info) {
        }

        /**
         * Handle meta message
         *
         * @param meta meta message to process
         */
        public void onMetaMessage(MsgServerMeta<?,?> meta) {
        }

        /**
         * Handle presence message
         *
         * @param pres control message to process
         */
        public void onPresMessage(MsgServerPres pres) {
        }

    }

}