package co.tinode.tinodesdk.model;

/**
 * Created by gsokolov on 2/2/16.
 */
public class ServerMessage<T,U,V> {
    public MsgServerData<T> data;
    public MsgServerMeta<U,V> meta;
    public MsgServerCtrl ctrl;
    public MsgServerPres pres;
    public MsgServerInfo info;
}
