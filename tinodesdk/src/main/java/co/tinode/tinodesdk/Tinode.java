package co.tinode.tinodesdk;

import android.os.Build;
import android.util.Log;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonFactory;
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
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;

import co.tinode.tinodesdk.model.*;

public class Tinode {
    private static final String TAG = "co.tinode.tinodesdk.Tinode";

    protected static final String TOPIC_NEW = "new";
    protected static final String TOPIC_ME = "me";
    protected static final String USER_NEW = "new";

    private static final String PROTOVERSION = "0";
    private static final String VERSION = "0.5";
    private static final String LIBRARY = "tindroid/0.5";

    private static ObjectMapper sJsonMapper;
    private static JsonFactory sJsonFactory;
    private static TypeFactory sTypeFactory;

    private String mApiKey;
    private String mServerHost;
    private String mAppName;

    private String mMyUid;
    private int mPacketCount;
    private int mMsgId;
    private EventListener mListener;

    private ConcurrentMap<String,MessageFuture> mFutures;
    private HashMap<String,Topic> mTopics;
    private Executor mExecutor;

    /**
     * Initialize Tinode package
     *
     * @param appname name of the application to include in User Agent on login.
     * @param host host name of the server, e.g. 'api.tinode.co' or 'localhost:8080'
     * @param apikey API key generate by key-gen utility
     */
    public Tinode(String appname, String host, String apikey) {
        sJsonMapper = new ObjectMapper();
        // Silently ignore unknown properties
        sJsonMapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        // Skip null fields from serialization
        sJsonMapper.setSerializationInclusion(JsonInclude.Include.NON_EMPTY);
        sTypeFactory = sJsonMapper.getTypeFactory();

        mAppName = appname;
        mApiKey = apikey;
        mServerHost = host;

        mFutures = new ConcurrentHashMap<String,MessageFuture>(16, 0.75f, 4);
        mTopics = new HashMap<String,Topic>();
    }

    /**
     * Finds topic for the packet and calls topic's {@link Topic#dispatch(ServerMessage)} method.
     * This method can be safely called from the UI thread after overriding
     * {@link Connection.EventListener#onMessage(ServerMessage)}
     **
     * @param message message to be parsed dispatched
     * @return true if packet was successfully dispatched, false if topic was not found
     */
    private boolean dispatchPacket(String message) {
        if (message == null)
            return false;

        Log.d(TAG, "in: " + message);

        mPacketCount++;

        if (mListener != null) {
            mListener.onRawMessage(message);
        }

        ServerMessage pkt = parseServerMessageFromJson(message);
        if (pkt == null) {
            Log.i(TAG, "Failed to parse packet");
            return false;
        }

        if (mListener != null) {
            mListener.onMessage(pkt);
        }

        if (pkt.ctrl != null) {

            if (mListener != null) {
                mListener.onCtrlMessage(pkt.ctrl);
            }
            if (mPacketCount == 1) {
                if (mListener != null) {
                    mListener.onConnect(pkt.ctrl.code, pkt.ctrl.text, pkt.ctrl.params);
                }
            }

            MessageFuture m = mFutures.remove(pkt.ctrl.id);
            if (m != null) {
                m.resolve(pkt.ctrl);
            }
            return true;
        }
        if (pkt.meta != null) {
            Topic topic = mTopics.get(pkt.meta.topic);
            if (topic != null) {
                topic.routeMeta(pkt.meta);
            }

            if (mListener != null) {
                mListener.onMetaMessage(pkt.meta);
            }

            return true;
        }
        if (pkt.data != null) {
            Topic topic = mTopics.get(pkt.data.topic);
            if (topic != null) {
                topic.routeData(pkt.data);
            }

            if (mListener != null) {
                mListener.onDataMessage(pkt.data);
            }
            return true;
        }
        if (pkt.pres != null) {
            Topic topic = mTopics.get(pkt.pres.topic);
            if (topic != null) {
                topic.routePres(pkt.pres);
            }

            if (mListener != null) {
                mListener.onPresMessage(pkt.pres);
            }
            return true;
        }
        if (pkt.info != null) {
            Topic topic = mTopics.get(pkt.info.topic);
            if (topic != null) {
                topic.routeInfo(pkt.info);
            }

            if (mListener != null) {
                mListener.onInfoMessage(pkt.info);
            }
            return true;
        }

        // TODO(gene): decide what to do on unknown message type
        return false;
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

    public void setExecutor(Executor exec) {
        mExecutor = exec;
    }
    protected String makeUserAgent() {
        return mAppName + " (Android " + Build.VERSION.RELEASE + "; "
                + Locale.getDefault().toString() + "; "
                + Build.MANUFACTURER + " " + Build.MODEL + "/" + Build.PRODUCT +
                ") tinodesdk/" + VERSION;
    }

    /**
     * Send a basic login packet to the server. A connection must be established prior to calling
     * this method. Success or failure will be reported through {@link Connection.EventListener#onLogin(int, String)}
     *
     *  @param uname user name
     *  @param password password
     *  @param done callback to call when the response packet arrives
     *  @return MessageFuture of the reply ctrl message
     *  @throws IOException if there is no connection
     */
    public MessageFuture LoginBasic(String uname, String password,
                                    MessageFuture.CompletionListener done) throws IOException {
        return login(MsgClientLogin.LOGIN_BASIC,
                MsgClientLogin.makeBasicToken(uname, password), done);
    }

    /**
     * Send a basic login packet to the server. A connection must be established prior to calling
     * this method. Success or failure will be reported through {@link Connection.EventListener#onLogin(int, String)}
     *
     *  @param uname user name
     *  @param password password
     *  @return MessageFuture of the reply ctrl message
     *  @throws IOException if there is no connection
     */
    public MessageFuture LoginBasic(String uname, String password) throws IOException {
        return login(MsgClientLogin.LOGIN_BASIC,
                MsgClientLogin.makeBasicToken(uname, password), null);
    }

    protected MessageFuture login(String scheme, String secret,
                                  MessageFuture.CompletionListener done) throws IOException {
        ClientMessage msg = new ClientMessage();
        msg.login = new MsgClientLogin(getNextId(), scheme, secret, makeUserAgent());
        try {
            send(Tinode.getJsonMapper().writeValueAsString(msg));
            MessageFuture future = null;
            if (msg.login.id != null) {
                future = new MessageFuture(done, mExecutor);
                mFutures.put(msg.login.id, future);
            }
            return future;
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    /**
     * Low-level subscription request. The subsequent messages on this topic will not
     * be automatically dispatched. A {@link Topic#Subscribe()} should be normally used instead.
     *
     * @param topicName name of the topic to subscribe to
     * @return id of the sent subscription packet, if {@link #wantAkn(boolean)} is set to true, null otherwise
     * @throws IOException
     */
    public MessageFuture Subscribe(String topicName,
                                   MessageFuture.CompletionListener done) throws IOException {
        ClientMessage msg = new ClientMessage();
        msg.sub = new MsgClientSub();
        msg.sub.id = getNextId();
        msg.sub.topic = topicName;
        try {
            send(Tinode.getJsonMapper().writeValueAsString(msg));
            MessageFuture future = new MessageFuture(done, mExecutor);
            mFutures.put(msg.sub.id, future);
            return future;
        } catch (JsonProcessingException e) {
            Log.i(TAG, "Failed to serialize message", e);
            return null;
        }
    }

    /**
     * Execute subscription request for a topic.
     *
     * Users should call {@link Topic#Subscribe()} instead
     *
     * @param topic to subscribe
     * @return request id
     * @throws IOException
     */
    protected MessageFuture subscribe(Topic<?> topic,
                                      MessageFuture.CompletionListener done) throws IOException {
        wantAkn(true);

        String name = topic.getName();
        if (name == null || name.equals("")) {
            Log.i(TAG, "Empty topic name");
            return null;
        }
        return Subscribe(name, done);
    }

    /**
     * Low-level request to unsubscribe topic. A {@link com.tinode.streaming.Topic#Unsubscribe()} should be normally
     * used instead.
     *
     * @param topicName name of the topic to subscribe to
     * @return id of the sent subscription packet, if {@link #wantAkn(boolean)} is set to true, null otherwise
     * @throws IOException
     */
    public MessageFuture Leave(String topicName, boolean unsub,
                        MessageFuture.CompletionListener done) throws IOException {
        ClientMessage msg = new ClientMessage();
        msg.leave = new MsgClientLeave();
        msg.leave.id = getNextId();
        msg.leave.topic = topicName;
        msg.leave.unsub = unsub;
        try {
            send(Tinode.getJsonMapper().writeValueAsString(msg));
            MessageFuture future = new MessageFuture(done, mExecutor);
            mFutures.put(msg.sub.id, future);
            return future;
        } catch (JsonProcessingException e) {
            return null;
        }
    }


    protected MessageFuture unsubscribe(Topic<?> topic, boolean unsub,
                                 MessageFuture.CompletionListener done)  throws IOException {
        wantAkn(true);
        return Leave(topic.getName(), unsub, done);
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
    public String Publish(String topicName, Object data) throws IOException {
        ClientMessage msg = new ClientMessage();
        msg.pub = new MsgClientPub<Object>(topicName, data);
        msg.pub.setId(getNextId());
        try {
            send(Tinode.getJsonMapper().writeValueAsString(msg));
            return msg.pub.getId();
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    protected String publish(Topic<?> topic, Object content) throws IOException {
        wantAkn(true);
        String id = Publish(topic.getName(), content);
        if (id != null) {
            expectReply(Cmd.PUB, id, topic);
        }
        return id;
    }


    /**
     * Assigns packet id, if needed, converts {@link com.tinode.streaming.model.ClientMessage} to Json string,
     * then calls {@link #send(String)}
     *
     * @param msg message to send
     * @return id of the packet (could be null)
     * @throws IOException
     */
    protected String sendPacket(ClientMessage<?> msg) throws IOException {
        String id = getNextId();

        if (id !=null) {
            if (msg.pub != null) {
                msg.pub.setId(id);
            } else if (msg.sub != null) {
                msg.sub.setId(id);
            } else if (msg.unsub != null) {
                msg.unsub.setId(id);
            } else if (msg.login != null) {
                msg.login.setId(id);
            }
        }

        try {
            send(Tinode.getJsonMapper().writeValueAsString(msg));
            return id;
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    /**
     * Writes a string to websocket.
     *
     * @param data string to write to websocket
     * @throws IOException
     */
    protected void send(String data) throws IOException {
        if (mWsClient.isConnected()) {
            mWsClient.send(data);
        } else {
            throw new IOException("Send called without a live connection");
        }
    }

    /**
     * Request server to send acknowledgement packets. Server responds with such packets if
     * client includes non-empty id fiel0d into outgoing packets.
     * If set to true, {@link #getNextId()} will return a string representation of a random integer between
     * 16777215 and 33554430
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
     * Check if there is a live connection.
     *
     * @return true if underlying websocket is connected
     */
    public boolean isConnected() {
        return mWsClient.isConnected();
    }

    /**
     * Obtain a subscribed !me topic ({@link MeTopic}).
     *
     * @return subscribed !me topic or null if !me is not subscribed
     */
    public MeTopic<?> getSubscribedMeTopic() {
        return (MeTopic) mSubscriptions.get(TOPIC_ME);
    }

    /**
     * Obtain a subscribed !pres topic {@link PresTopic}.
     *
     * @return subscribed !pres topic or null if !pres is not subscribed
     */
    public PresTopic<?> getSubscribedPresTopic() {
        return (PresTopic) mSubscriptions.get(TOPIC_PRES);
    }

    /**
     * Obtain a subscribed topic by name
     *
     * @param name name of the topic to find
     * @return subscribed topic or null if no such topic was found
     */
    public Topic<?> getSubscribedTopic(String name) {
        return mSubscriptions.get(name);
    }

    protected void registerP2PTopic(MeTopic<?> topic) {
        mSubscriptions.put(topic.getName(), topic);
    }

    /**
     * Enumerate subscribed topics and inform each one that it was disconnected.
     */
    private void disconnectTopics() {
        for (Map.Entry<String, Topic<?>> e : mSubscriptions.entrySet()) {
            e.getValue().disconnected();
        }
    }

    /**
     * Parse JSON received from the server into {@link ServerMessage}
     *
     * @param jsonMessage
     * @return ServerMessage or null
     */
    @SuppressWarnings("unchecked")
    protected ServerMessage<?> parseServerMessageFromJson(String jsonMessage) {
        MsgServerCtrl ctrl = null;
        MsgServerData<?> data = null;
        try {
            ObjectMapper mapper = Tinode.getJsonMapper();
            JsonParser parser = mapper.getFactory().createParser(jsonMessage);
            // Sanity check: verify that we got "Json Object":
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                throw new JsonParseException("Packet must start with an object",
                        parser.getCurrentLocation());
            }
            // Iterate over object fields:
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                String name = parser.getCurrentName();
                parser.nextToken();
                if (name.equals("ctrl")) {
                    ctrl = mapper.readValue(parser, MsgServerCtrl.class);
                } else if (name.equals("data")) {
                    data = parseMsgServerData(parser);
                } else { // Unrecognized field, ignore
                    Log.i(TAG, "Unknown field in packet: '" + name +"'");
                }
            }
            parser.close(); // important to close both parser and underlying reader
        } catch (JsonParseException e) {
            e.printStackTrace();
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

    protected MsgServerData<?> parseMsgServerData(JsonParser parser) throws JsonParseException,
            IOException {
        ObjectMapper mapper = Tinode.getJsonMapper();
        JsonNode data = mapper.readTree(parser);
        if (data.has("topic")) {
            String topicName = data.get("topic").asText();
            Topic<?> topic = getSubscribedTopic(topicName);
            // Is this a topic we are subscribed to?
            if (topic == null) {
                // This is a new topic

                // Try to find a topic pending subscription by packet id
                if (data.has("id")) {
                    String id = data.get("id").asText();
                    Cmd cmd = mRequests.get(id);
                    if (cmd != null) {
                        topic = cmd.source;
                    }
                }

                // If topic was not found among pending subscriptions, try to create it
                if (topic == null && mListener != null) {
                    topic = mListener.onNewTopic(topicName);
                    if (topic != null) {
                        topic.setStatus(Topic.STATUS_SUBSCRIBED);
                        mSubscriptions.put(topicName, topic);
                    } else if (topicName.startsWith(TOPIC_P2P)) {
                        // Client refused to create topic. If this is a P2P topic, assume
                        // the payload is the same as "!me"
                        topic = getSubscribedMeTopic();
                    }
                }
            }

            JavaType typeOfData;
            if (topic == null) {
                Log.i(TAG, "Data message for unknown topic [" + topicName + "]");
                typeOfData = mapper.getTypeFactory().constructType(Object.class);
            } else {
                typeOfData = topic.getDataType();
            }
            MsgServerData packet = new MsgServerData();
            if (data.has("id")) {
                packet.id = data.get("id").asText();
            }
            packet.topic = topicName;
            if (data.has("origin")) {
                packet.origin = data.get("origin").asText();
            }
            if (data.has("content")) {
                packet.content = mapper.readValue(data.get("content").traverse(), typeOfData);
            }
            return packet;
        } else {
            throw new JsonParseException("Invalid data packet: missing topic name",
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

    static class Cmd {
        static final int LOGIN = 1;
        static final int SUB = 2;
        static final int UNSUB = 3;
        static final int PUB = 4;

        int type;
        String id;
        Topic<?> source;

        Cmd(int type, String id, Topic<?> src) {
            this.type = type;
            this.id = id;
            this.source = src;
        }
        Cmd(int type, String id) {
            this(type, id, null);
        }
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
         * Result of successful or unsuccessful {@link #Login(String)} attempt.
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