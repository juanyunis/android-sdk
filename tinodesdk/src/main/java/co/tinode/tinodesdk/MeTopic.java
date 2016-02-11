package co.tinode.tinodesdk;

import com.fasterxml.jackson.databind.JavaType;

import co.tinode.tinodesdk.model.Invitation;

/**
 * Created by gsokolov on 2/10/16.
 */
public class MeTopic<T> extends Topic<Invitation<T>> {

    public MeTopic(Tinode tinode, JavaType typeOfInviteInfo, Listener<Invitation<T>> l) {
        super(tinode,
                Tinode.TOPIC_ME,
                Tinode.getTypeFactory().constructParametricType(Invitation.class,
                        Tinode.getTypeFactory().constructType(Invitation.class), typeOfInviteInfo),
                l);
    }

    public MeTopic(Tinode tinode, Class<?> typeOfT, Listener<Invitation<T>> l) {
        this(tinode, Tinode.getTypeFactory().constructType(typeOfT), l);
    }
}
