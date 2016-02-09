/**
 * Created by gene on 06/02/16.
 */

package co.tinode.tinodesdk;

import android.util.Log;

import co.tinode.tinodesdk.model.MsgServerData;
import co.tinode.tinodesdk.model.MsgServerInfo;
import co.tinode.tinodesdk.model.MsgServerMeta;
import co.tinode.tinodesdk.model.MsgServerPres;
import co.tinode.tinodesdk.model.ServerMessage;

import com.fasterxml.jackson.databind.JavaType;

import java.io.IOException;
import java.util.HashMap;


/**
 *
 * Class for handling communication on a single topic
 *
 */
public class Topic<T> {
    private static final String TAG = "co.tinode.tinodesdk.Topic";

    protected JavaType mTypeOfData;
    protected String mName;
    protected Tinode mTinode;
    protected boolean mSubscribed;

    private Listener<T> mListener;

    public Topic(Tinode tinode, String name, JavaType typeOfT, Listener<T> l) {
        mTypeOfData = typeOfT;
        mTinode = tinode;
        mName = name;
        mListener = l;
        mSubscribed = false;
    }

    public Topic(Tinode tinode, String name, Class<?> typeOfT, Listener<T> l) {
        this(tinode, name, Tinode.getTypeFactory().constructType(typeOfT), l);
    }

    /**
     * Construct a topic for a group chat. Use this constructor if payload is non-trivial, such as
     * collection or a generic class. If content is trivial (POJO), use constructor which takes
     * Class&lt;?&gt; as a typeOfT parameter.
     *
     * Construct {@code }typeOfT} with one of {@code
     * com.fasterxml.jackson.databind.type.TypeFactory.constructXYZ()} methods such as
     * {@code mMyConnectionInstance.getTypeFactory().constructType(MyPayloadClass.class)}.
     *
     * The actual topic name will be set after completion of a successful subscribe call
     *
     * @param tinode tinode instance
     * @param typeOfT type of content
     * @param l event listener
     */
    public Topic(Tinode tinode, JavaType typeOfT, Listener<T> l) {
        this(tinode, Tinode.TOPIC_NEW, typeOfT, l);
    }

    /**
     * Create topic for a new group chat. Use this constructor if payload is trivial (POJO)
     * Topic will not be usable until Subscribe is called
     *
     */
    public Topic(Tinode tinode, Class<?> typeOfT, Listener<T> l) {
        this(tinode, Tinode.getTypeFactory().constructType(typeOfT), l);
    }

    /**
     * Subscribe topic
     *
     * @throws IOException
     */
    public PromisedReply subscribe() throws IOException {
        if (!mSubscribed) {
            return mTinode.subscribe(getName());
        }
        return null;
    }

    /**
     * Leave topic
     * @param unsub true to disconnect and unsubscribe from topic, otherwise just disconnect
     *
     * @throws IOException
     */
    public PromisedReply leave(boolean unsub) throws IOException {
        if (mSubscribed) {
            return mTinode.leave(getName(), unsub);
        }
        return null;
    }

    /**
     * Publish message to a topic. It will attempt to publish regardless of subscription status.
     *
     * @param content payload
     * @throws IOException
     */
    public PromisedReply Publish(T content) throws IOException {
        return mTinode.publish(getName(), content);
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

    public boolean isSubscribed() {
        return mSubscribed;
    }

    protected void disconnected() {
        if (mSubscribed) {
            mSubscribed = false;
            if (mListener != null) {
                mListener.onUnsubscribe(503, "connection lost");
            }
        }
    }

    protected void routeMeta(MsgServerMeta meta) {
    }

    protected void routeData(MsgServerData data) {
    }

    protected void routePres(MsgServerPres pres) {
    }

    protected void routeInfo(MsgServerInfo info) {
    }

    @SuppressWarnings("unchecked")
    protected boolean dispatch(ServerMessage<?,?,?> pkt) {
        Log.d(TAG, "Topic " + getName() + " dispatching");
        ServerMessage<T,?,?> msg = null;

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
                    mListener.onLeave(msg.ctrl.code, msg.ctrl.text);
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
        public void onData(String from, T content);
        public void onPres();
        public void onMeta();
    }
}
