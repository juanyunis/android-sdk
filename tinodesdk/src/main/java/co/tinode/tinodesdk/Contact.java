package co.tinode.tinodesdk;

/**
 * Created by gsokolov on 2/5/16.
 */

import java.util.Date;

/**
 * Created by gsokolov on 2/4/16.
 */
public class Contact<T,U> {
    public String topic;
    public Date updated;
    public String mode;

    public boolean online;

    public int seq;
    public int read;
    public int recv;

    public T pub;
    public U priv;

    /* p2p only */
    public String with;
    public Seen seen;

    public class Seen {
        public Date when;
        public String ua;
    }
}