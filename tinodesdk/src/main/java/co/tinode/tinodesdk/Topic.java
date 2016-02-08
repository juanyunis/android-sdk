package co.tinode.tinodesdk;

/**
 * Created by gsokolov on 2/5/16.
 */
import android.util.Log;

import com.fasterxml.jackson.databind.JavaType;

import java.io.IOException;

import co.tinode.tinodesdk.model.ServerMessage;

/**
 *
 * Class for handling communication on a single topic
 *
 */
public class Topic<T> {
    private static final String TAG = "com.tinode.Topic";

    protected static final int STATUS_SUBSCRIBED = 2;
    protected static final int STATUS_PENDING = 1;
    protected static final int STATUS_UNSUBSCRIBED = 0;

    protected static final int PACKET_TYPE_PUB = 1;
    protected static final int PACKET_TYPE_SUB = 2;
    protected static final int PACKET_TYPE_UNSUB = 3;

    protected JavaType mTypeOfData;
    protected String mName;
    protected Connection mConnection;
    protected int mStatus;

    private Listener<T> mListener;

    public Topic(Connection conn, String name, JavaType typeOfT, Listener<T> l) {
        mTypeOfData = typeOfT;
        mConnection = conn;
        mName = name;
        mListener = l;
        mStatus = STATUS_UNSUBSCRIBED;
    }

    public Topic(Connection conn, String name, Class<?> typeOfT, Listener<T> l) {
        this(conn, name, Tinode.getTypeFactory().constructType(typeOfT), l);
    }

    /**
     * Construct a topic for a group chat. Use this constructor if payload is non-trivial, such as
     * collection or a generic class. If content is trivial (POJO), use constructor which takes
     * Class&lt;?&gt; as a typeOfT parameter.
     *
     * Construct {@code }typeOfT} with one of {@code
     * com.fasterxml.jackson.databind.type.TypeFactory.constructXYZ()} methods such as
     * {@code mMyConnectionInstance.getTypeFactory().constructType(MyPayloadClass.class)} or see
     * source of {@link com.tinode.streaming.PresTopic} constructor.
     *
     * The actual topic name will be set after completion of a successful Subscribe call
     *
     * @param conn connection
     * @param typeOfT type of content
     * @param l event listener
     */
    public Topic(Connection conn, JavaType typeOfT, Listener<T> l) {
        this(conn, Connection.TOPIC_NEW, typeOfT, l);
    }

    /**
     * Create topic for a new group chat. Use this constructor if payload is trivial (POJO)
     * Topic will not be usable until Subscribe is called
     *
     */
    public Topic(Connection conn, Class<?> typeOfT, Listener<T> l) {
        this(conn, Tinode.getTypeFactory().constructType(typeOfT), l);
    }

    /**
     * Subscribe topic
     *
     * @throws IOException
     */
    public void Subscribe() throws IOException {
        if (mStatus == STATUS_UNSUBSCRIBED) {
            String id = mConnection.subscribe(this);
            if (id != null) {
                mReplyExpected.put(id, PACKET_TYPE_SUB);
            }
            mStatus = STATUS_PENDING;
        }
    }

    /**
     * Unsubscribe topic
     *
     * @throws IOException
     */
    public void Unsubscribe() throws IOException {
        if (mStatus == STATUS_SUBSCRIBED) {
            String id = mConnection.unsubscribe(this);
            if (id != null) {
                mReplyExpected.put(id, PACKET_TYPE_UNSUB);
            }
        }
    }

    /**
     * Publish message to a topic. It will attempt to publish regardless of subscription status.
     *
     * @param content payload
     * @throws IOException
     */
    public void Publish(T content) throws IOException {
        String id = mConnection.publish(this, content);
        if (id != null) {
            mReplyExpected.put(id, PACKET_TYPE_PUB);
        }
    }

    public JavaType getDataType() {
        return mTypeOfData;
    }

    public String getName() {
        return mName;
    }
    protected void setName(String name) {
        mName = name;
    }

    public Listener<T> getListener() {
        return mListener;
    }
    protected void setListener(Listener<T> l) {
        mListener = l;
    }

    public int getStatus() {
        return mStatus;
    }
    public boolean isSubscribed() {
        return mStatus == STATUS_SUBSCRIBED;
    }
    protected void setStatus(int status) {
        mStatus = status;
    }

    protected void disconnected() {
        mReplyExpected.clear();
        if (mStatus != STATUS_UNSUBSCRIBED) {
            mStatus = STATUS_UNSUBSCRIBED;
            if (mListener != null) {
                mListener.onUnsubscribe(503, "connection lost");
            }
        }
    }

    @SuppressWarnings("unchecked")
    protected boolean dispatch(ServerMessage<?,?,?> pkt) {
        Log.d(TAG, "Topic " + getName() + " dispatching");
        ServerMessage<T,?,?> msg = null;
        try {
            // This generates the "unchecked" warning
            msg = (ServerMessage<T>)pkt;
        } catch (ClassCastException e) {
            Log.i(TAG, "Invalid type of content in Topic [" + getName() + "]");
            return false;
        }
        if (mListener != null) {
            if (msg.data != null) { // Incoming data packet
                mListener.onData(msg.data.origin, msg.data.content);
            } else if (msg.ctrl != null && msg.ctrl.id != null) {
                Integer type = mReplyExpected.get(msg.ctrl.id);
                if (type == PACKET_TYPE_SUB) {
                    if (msg.ctrl.code >= 200 && msg.ctrl.code < 300) {
                        mStatus = STATUS_SUBSCRIBED;
                    } else if (mStatus == STATUS_PENDING) {
                        mStatus = STATUS_UNSUBSCRIBED;
                    }
                    mListener.onSubscribe(msg.ctrl.code, msg.ctrl.text);
                } else if (type == PACKET_TYPE_UNSUB) {
                    mStatus = STATUS_UNSUBSCRIBED;
                    mListener.onUnsubscribe(msg.ctrl.code, msg.ctrl.text);
                } else if (type == PACKET_TYPE_PUB) {
                    mListener.onPublish(msg.ctrl.topic, msg.ctrl.code, msg.ctrl.text);
                } else {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    public interface Listener<T> {
        public void onSubscribe(int code, String text);
        public void onUnsubscribe(int code, String text);
        public void onPublish(String topicName, int code, String text);
        public void onData(String from, T content);
    }
}
