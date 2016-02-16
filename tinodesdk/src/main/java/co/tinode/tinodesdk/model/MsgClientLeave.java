package co.tinode.tinodesdk.model;

/**
 * Created by gene on 31/01/16.
 */
public class MsgClientLeave {
    public String id;
    public String topic;
    public boolean unsub;

    public MsgClientLeave(String id, String topic, boolean unsub) {
        this.id = id;
        this.topic = topic;
        this.unsub = unsub;
    }
}
