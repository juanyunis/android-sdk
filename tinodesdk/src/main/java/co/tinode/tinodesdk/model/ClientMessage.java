package co.tinode.tinodesdk.model;

/**
 * Created by gene on 31/01/16.
 */

public class ClientMessage<T> {
    public MsgClientLogin login;
    public MsgClientPub<T> pub;
    public MsgClientSub sub;
    public MsgClientLeave leave;
    public MsgClientNote note;

    public ClientMessage() {
    }
}