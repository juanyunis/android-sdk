package co.tinode.tinodesdk.model;

/**
 * Created by gsokolov on 2/2/16.
 */
public class MsgClientNote {
    public enum Note {KP,READ,RECV};

    public String topic; // topic to notify, required
    public String what;  // one of "kp" (key press), "read" (read notification),
                // "recv" (received notification), any other string will cause
                // message to be silently dropped, required
    public int seq; // ID of the message being acknowledged, required for rcpt & read

    public MsgClientNote(String topic, Note what, int seq) {
        this.topic = topic;
        switch (what) {
            case KP:
                this.what = "kp";
                break;
            case READ:
                this.what = "read";
                this.seq = seq;
                break;
            case RECV:
                this.what = "recv";
                this.seq = seq;
        }
    }
}
